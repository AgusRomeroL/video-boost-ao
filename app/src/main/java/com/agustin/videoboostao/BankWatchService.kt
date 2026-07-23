package com.agustin.videoboostao

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Monitor de primer plano que corre mientras el servicio de accesibilidad está
 * apagado por una app sensible. Detecta cuándo esa app deja el primer plano y:
 * - con full-auto (Shizuku o ADB): re-activa el servicio de accesibilidad solo, y
 * - siempre: cancela la notificación silenciosa y muestra la normal.
 *
 * Es un foreground service porque el proceso puede ser matado tras `disableSelf`;
 * se arranca DESDE el servicio de accesibilidad (aún activo) para esquivar la
 * restricción de FGS-en-background de Android 12+.
 *
 * El acceso privilegiado se abstrae en [PrivilegedShell]: Shizuku via su binder,
 * ADB via una conexión TLS al propio daemon. La vía ADB es I/O de red bloqueante,
 * así que sus lecturas/escrituras corren en [ioScope] (IO) y postean el resultado
 * al [handler] del hilo principal.
 */
class BankWatchService : Service() {

    companion object {
        private const val TAG = "VideoBoostAO"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_LABEL = "label"
        private const val POLL_MS = 1200L
        /** Lecturas seguidas sin la app sensible para declararla cerrada
         *  (evita falsos positivos por SystemUI / diálogos transitorios). */
        private const val AWAY_CONFIRM = 2
        /** Tope de vida por si el monitoreo nunca ve el primer plano (binder
         *  de Shizuku caído, ADB desconectado, etc.): no dejar un FGS zombie. */
        private const val MAX_SESSION_MS = 15 * 60 * 1000L

        fun start(context: Context, pkg: String, label: String?) {
            val i = Intent(context, BankWatchService::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sensitivePkg: String? = null
    private var sensitiveLabel: String? = null
    private var mechanism = Capabilities.Mechanism.NONE
    private var startedAt = 0L
    private var sawSensitive = false
    private var awayCount = 0
    private var finished = false
    private var adbReadInFlight = false

    /** El acceso privilegiado activo, o null si aún no conecta / no hay. */
    @Volatile
    private var shell: PrivilegedShell? = null

    // Conexión al UserService de Shizuku (solo vía SHIZUKU).
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shell = if (binder != null && binder.pingBinder()) {
                ShizukuPrivilegedShell(IUserService.Stub.asInterface(binder))
            } else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PKG)
        if (pkg == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        sensitivePkg = pkg
        sensitiveLabel = intent.getStringExtra(EXTRA_LABEL)
        startedAt = System.currentTimeMillis()

        startForeground(
            Notifications.NOTIF_ID_PAUSED,
            Notifications.buildPausedOngoing(this, sensitiveLabel),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        mechanism = Capabilities.fullAutoMechanism(this)
        when (mechanism) {
            Capabilities.Mechanism.SHIZUKU -> bindShizuku()
            Capabilities.Mechanism.ADB -> connectAdb()
            Capabilities.Mechanism.NONE -> Unit
        }

        handler.postDelayed(pollRunnable, POLL_MS)
        return START_NOT_STICKY
    }

    private val pollRunnable = Runnable { poll() }

    private fun poll() {
        if (finished) return
        if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) {
            Log.w(TAG, "BankWatch: tope de sesión; termino")
            onSensitiveClosed()
            return
        }
        if (mechanism == Capabilities.Mechanism.ADB) {
            // Lectura de red bloqueante -> fuera del hilo principal, con guard
            // para que un round-trip lento no apile lecturas.
            if (adbReadInFlight) {
                scheduleNext()
                return
            }
            adbReadInFlight = true
            ioScope.launch {
                val fg = readForegroundBlocking()
                withContext(Dispatchers.Main) {
                    adbReadInFlight = false
                    if (finished) return@withContext
                    if (!processForeground(fg)) scheduleNext()
                }
            }
        } else {
            // Shizuku: binder rápido en el hilo principal (comportamiento previo).
            // NONE: solo UsageStats.
            val fg = readForegroundBlocking()
            if (!processForeground(fg)) scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (!finished) handler.postDelayed(pollRunnable, POLL_MS)
    }

    /** Aplica la lógica de away-count. Devuelve true si la app sensible se
     *  declaró cerrada (el poll debe detenerse). */
    private fun processForeground(fg: String?): Boolean {
        when {
            fg == null -> Unit // sin lectura; reintentar
            fg == sensitivePkg -> {
                sawSensitive = true
                awayCount = 0
            }
            sawSensitive -> {
                awayCount++
                if (awayCount >= AWAY_CONFIRM) {
                    onSensitiveClosed()
                    return true
                }
            }
        }
        return false
    }

    /** Primer plano vía el mecanismo privilegiado (preferido) o UsageStats. */
    private fun readForegroundBlocking(): String? {
        shell?.let { s ->
            try {
                s.getForegroundPackage().takeIf { it.isNotBlank() }?.let { return it }
            } catch (_: Exception) {
            }
        }
        if (Capabilities.hasUsageAccess(this)) return foregroundViaUsageStats()
        return null
    }

    private fun foregroundViaUsageStats(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                last = e.packageName
            }
        }
        return last
    }

    private fun onSensitiveClosed() {
        if (finished) return
        finished = true
        handler.removeCallbacks(pollRunnable)
        Notifications.cancelPaused(this)

        val component = ComponentName(this, VideoBoostService::class.java).flattenToString()
        if (mechanism == Capabilities.Mechanism.ADB) {
            // Re-activar es un round-trip TLS -> fuera del hilo principal.
            ioScope.launch {
                val reenabled = try {
                    shell?.enableAccessibilityService(component) ?: false
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) { finishSession(reenabled) }
            }
        } else {
            val reenabled = if (mechanism == Capabilities.Mechanism.SHIZUKU) {
                try {
                    shell?.enableAccessibilityService(component) ?: false
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
            finishSession(reenabled)
        }
    }

    private fun finishSession(reenabled: Boolean) {
        if (reenabled) {
            // onServiceConnected del a11y service limpia disabledByBank y cancela
            // notifs; el aviso "reactivado" (id distinto) no lo toca.
            Notifications.showReenabled(this)
        } else {
            Notifications.showClosedReminder(this, sensitiveLabel)
        }
        teardownShell()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun connectAdb() {
        ioScope.launch {
            val ok = AdbManager.connect(this@BankWatchService).isSuccess
            if (ok) {
                shell = AdbManager.shell(this@BankWatchService)
            } else {
                // Sin conexión: readForegroundBlocking cae a UsageStats y el
                // cierre mostrará el recordatorio (mismo fallback que Shizuku).
                Log.w(TAG, "BankWatch: ADB no conectó; fallback a UsageStats")
            }
        }
    }

    private fun bindShizuku() {
        try {
            Shizuku.bindUserService(ShizukuManager.userServiceArgs(this), conn)
        } catch (t: Throwable) {
            Log.w(TAG, "BankWatch: no se pudo bindear el UserService de Shizuku", t)
        }
    }

    private fun teardownShell() {
        when (mechanism) {
            Capabilities.Mechanism.SHIZUKU -> unbindShizuku()
            Capabilities.Mechanism.ADB -> {
                try {
                    AdbManager.disconnect()
                } catch (_: Exception) {
                }
                shell = null
            }
            Capabilities.Mechanism.NONE -> Unit
        }
    }

    private fun unbindShizuku() {
        shell = null
        try {
            Shizuku.unbindUserService(ShizukuManager.userServiceArgs(this), conn, true)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        teardownShell()
        ioScope.cancel()
        super.onDestroy()
    }
}
