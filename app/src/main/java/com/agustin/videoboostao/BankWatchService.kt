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
import rikka.shizuku.Shizuku

/**
 * Monitor de primer plano que corre mientras el servicio de accesibilidad está
 * apagado por una app sensible. Detecta cuándo esa app deja el primer plano y:
 * - con full-auto (Shizuku): re-activa el servicio de accesibilidad solo, y
 * - siempre: cancela la notificación silenciosa y muestra la normal.
 *
 * Es un foreground service porque el proceso puede ser matado tras `disableSelf`;
 * se arranca DESDE el servicio de accesibilidad (aún activo) para esquivar la
 * restricción de FGS-en-background de Android 12+.
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
         *  de Shizuku caído, etc.): no dejar un FGS zombie. */
        private const val MAX_SESSION_MS = 15 * 60 * 1000L

        fun start(context: Context, pkg: String, label: String?) {
            val i = Intent(context, BankWatchService::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sensitivePkg: String? = null
    private var sensitiveLabel: String? = null
    private var useShizuku = false
    private var startedAt = 0L
    private var sawSensitive = false
    private var awayCount = 0
    private var finished = false

    private var userService: IUserService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = if (binder != null && binder.pingBinder()) {
                IUserService.Stub.asInterface(binder)
            } else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
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

        useShizuku = Capabilities.fullAutoReady(this)
        if (useShizuku) bindShizuku()

        handler.postDelayed(pollRunnable, POLL_MS)
        return START_NOT_STICKY
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (finished) return
            if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) {
                Log.w(TAG, "BankWatch: tope de sesión; termino")
                onSensitiveClosed()
                return
            }
            val fg = currentForeground()
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
                        return
                    }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Primer plano vía Shizuku (preferido) o UsageStats. */
    private fun currentForeground(): String? {
        userService?.let { us ->
            try {
                us.foregroundPackage?.takeIf { it.isNotBlank() }?.let { return it }
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

        var reenabled = false
        if (useShizuku) {
            val component = ComponentName(this, VideoBoostService::class.java).flattenToString()
            reenabled = try {
                userService?.enableAccessibilityService(component) ?: false
            } catch (_: Exception) {
                false
            }
        }

        if (reenabled) {
            // onServiceConnected del a11y service limpia disabledByBank y cancela
            // notifs; el aviso "reactivado" (id distinto) no lo toca.
            Notifications.showReenabled(this)
        } else {
            Notifications.showClosedReminder(this, sensitiveLabel)
        }

        unbindShizuku()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun bindShizuku() {
        try {
            Shizuku.bindUserService(ShizukuManager.userServiceArgs(this), conn)
        } catch (t: Throwable) {
            Log.w(TAG, "BankWatch: no se pudo bindear el UserService de Shizuku", t)
        }
    }

    private fun unbindShizuku() {
        userService = null
        try {
            Shizuku.unbindUserService(ShizukuManager.userServiceArgs(this), conn, true)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        if (useShizuku) unbindShizuku()
        super.onDestroy()
    }
}
