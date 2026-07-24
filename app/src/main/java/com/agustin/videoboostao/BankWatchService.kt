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
import kotlinx.coroutines.delay
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
        /** Lecturas nulas seguidas antes de dar la conexión ADB por muerta y
         *  reconectar desde cero (~5 s con POLL_MS = 1200). */
        private const val RECONNECT_AFTER_NULLS = 4
        /** Intentos de reactivación por ADB al cerrarse la app sensible. */
        private const val ENABLE_ATTEMPTS = 8
        private const val ENABLE_RETRY_MS = 500L

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

    /** La app sensible ESTABA en primer plano al arrancar (el servicio de
     *  accesibilidad la detectó ahí): partir de true. Antes partía de false y,
     *  si la primera lectura útil llegaba cuando el usuario ya había cerrado
     *  la app (conexión lenta o sesión corta), el cierre no se detectaba nunca
     *  y la reactivación esperaba el tope de sesión de 15 minutos. */
    private var sawSensitive = true
    private var awayCount = 0
    private var finished = false
    private var adbReadInFlight = false
    private var nullReads = 0

    /** Hay un connect/reconnect ADB en vuelo. */
    private var connecting = false

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
            // Con "Acceso de uso" el sondeo es local: la conexión ADB solo hace
            // falta al final, y abrirla entonces evita 15 min de streams
            // inútiles (y de oportunidades de que la conexión se rompa).
            Capabilities.Mechanism.ADB -> if (!Capabilities.hasUsageAccess(this)) connectAdb()
            Capabilities.Mechanism.NONE -> Unit
        }

        handler.postDelayed(pollRunnable, POLL_MS)
        // Si el sistema mata el proceso durante la sesión (pasa: tras
        // disableSelf ya no hay servicio de accesibilidad que lo proteja),
        // re-entregar el intent para retomar el monitoreo donde quedó.
        return START_REDELIVER_INTENT
    }

    private val pollRunnable = Runnable { poll() }

    private fun poll() {
        if (finished) return
        if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) {
            Log.w(TAG, "BankWatch: tope de sesión; termino")
            onSensitiveClosed()
            return
        }
        if (Capabilities.hasUsageAccess(this)) {
            // Vía normal: lectura local e instantánea, sin tocar ADB.
            if (!processForeground(foregroundViaUsageStats())) scheduleNext()
            return
        }
        if (mechanism == Capabilities.Mechanism.ADB) {
            // Sin "Acceso de uso": queda leer por ADB. Es I/O de red bloqueante
            // -> fuera del hilo principal, con guard para que un round-trip
            // lento no apile lecturas. Tampoco se lee mientras se está
            // (re)conectando: el shell de esa conexión aún no es válido.
            if (adbReadInFlight || connecting) {
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
            fg == null -> {
                // Sin lectura. En la vía ADB, varios fallos seguidos delatan
                // una conexión zombie: reconectar en vez de esperar el tope.
                // Mientras haya un connect en vuelo no se cuenta: al arrancar
                // no hay conexión todavía y disparar otra ahí era justamente lo
                // que hacía que las dos se cerraran mutuamente.
                if (!connecting) {
                    nullReads++
                    if (mechanism == Capabilities.Mechanism.ADB && nullReads >= RECONNECT_AFTER_NULLS) {
                        reconnectAdb()
                    }
                }
            }
            fg == sensitivePkg -> {
                nullReads = 0
                sawSensitive = true
                awayCount = 0
            }
            sawSensitive -> {
                nullReads = 0
                awayCount++
                if (awayCount >= AWAY_CONFIRM) {
                    onSensitiveClosed()
                    return true
                }
            }
        }
        return false
    }

    /** Descarta la conexión ADB actual y prueba una nueva. Con el guard de
     *  [connecting], a lo sumo un intento en vuelo; si falla, el contador de
     *  nulls volverá a dispararlo (~cada 5 s) hasta el tope de sesión. */
    private fun reconnectAdb() {
        if (connecting || finished) return
        nullReads = 0
        Log.w(TAG, "BankWatch: lecturas ADB muertas; reconectando")
        AdbManager.disconnect()
        openAdb("reconectado")
    }

    /**
     * Primer plano vía UsageStats (preferido) o el mecanismo privilegiado.
     *
     * El orden importa: UsageStats es una lectura local, instantánea y sin
     * estado, mientras que cada lectura por ADB abre un stream nuevo sobre el
     * túnel TLS y puede colgarse (ver [AdbManager.execRetrying]). Sondear por
     * ADB cada 1.2 s era justamente lo que rompía la conexión. Por eso el
     * full-auto se auto-concede el permiso al arrancar ([ensureUsageAccess]) y
     * ADB queda para lo que de verdad lo necesita: reactivar el servicio.
     */
    private fun readForegroundBlocking(): String? {
        if (Capabilities.hasUsageAccess(this)) {
            foregroundViaUsageStats()?.let { return it }
        }
        shell?.let { s ->
            try {
                s.getForegroundPackage().takeIf { it.isNotBlank() }?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Con shell privilegiado y sin "Acceso de uso", concedérselo: un comando
     * una sola vez a cambio de no volver a sondear por ADB nunca más.
     *
     * Solo en la vía ADB. Shizuku corre los comandos sobre su binder, que no
     * sufre la fragilidad de streams que motiva todo esto, así que ahí no hay
     * nada que optimizar ni, por tanto, permiso que conceder.
     */
    private fun ensureUsageAccess() {
        if (Capabilities.hasUsageAccess(this)) return
        val s = shell ?: return
        s.grantUsageAccess(packageName)
        // Confirmar por efecto, no por la salida del comando: esta se pierde a
        // menudo aunque el comando sí se ejecute (ver [AdbManager.exec]).
        if (Capabilities.hasUsageAccess(this)) {
            // Queda anotado como concedido por nosotros para poder revocarlo si
            // el usuario apaga el full-auto (ver [Prefs.usageAccessSelfGranted]).
            Prefs.setUsageAccessSelfGranted(this, true)
            Log.i(TAG, "BankWatch: 'Acceso de uso' concedido; sondeo local")
        }
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
            // Re-activar es un round-trip TLS -> fuera del hilo principal. Es el
            // único punto del flujo donde ADB es imprescindible, así que aquí se
            // insiste; el resultado se comprueba en local (ver enableViaAdb).
            ioScope.launch {
                val reenabled = enableViaAdb(component)
                withContext(Dispatchers.Main) { finishSession(reenabled) }
            }
        } else {
            if (mechanism == Capabilities.Mechanism.SHIZUKU) tryEnable(component)
            // Verificar por efecto, no por la salida del comando: mismo criterio
            // que la vía ADB (ver [enableViaAdb]).
            finishSession(Capabilities.accessibilityServiceEnabled(this, component))
        }
    }

    private fun tryEnable(component: String): Boolean = try {
        shell?.enableAccessibilityService(component) ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Reactiva el servicio por ADB y confirma leyendo secure settings EN LOCAL.
     *
     * La comprobación local es lo que hace fiable esta parte: abrir un stream
     * ADB falla de forma intermitente (ver [AdbManager.exec]), pero cuando
     * falla al devolver la salida el comando normalmente ya se ejecutó. Antes
     * se verificaba con la salida del propio comando, así que un éxito real se
     * leía como fallo y se reintentaba, reactivando el servicio en bucle.
     */
    private suspend fun enableViaAdb(component: String): Boolean {
        val ctx = this@BankWatchService
        val script = AdbShellCommands.enableScript(component)
        repeat(ENABLE_ATTEMPTS) { i ->
            if (Capabilities.accessibilityServiceEnabled(ctx, component)) return true
            if (!AdbManager.isConnected()) AdbManager.connect(ctx)
            // Sin esperar la salida ni reintentar el stream: el comando se
            // ejecuta igual, y quien manda es la comprobación local de abajo.
            AdbManager.exec(script, AdbManager.FIRE_TIMEOUT_MS, streamAttempts = 1)
            // Dar tiempo a que el provider publique la escritura antes de leer.
            delay(300)
            if (Capabilities.accessibilityServiceEnabled(ctx, component)) {
                Log.i(TAG, "BankWatch: reactivado (intento ${i + 1})")
                return true
            }
            // No funcionó: rehacer la conexión antes del siguiente intento.
            AdbManager.disconnect()
            if (i < ENABLE_ATTEMPTS - 1) delay(ENABLE_RETRY_MS)
        }
        return Capabilities.accessibilityServiceEnabled(ctx, component)
    }

    private fun finishSession(reenabled: Boolean) {
        Log.i(TAG, "BankWatch: sesión terminada; reactivado=$reenabled")
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

    private fun connectAdb() = openAdb("conectado")

    /** Abre la conexión ADB en IO y publica el shell resultante. [connecting]
     *  se marca en el hilo principal ANTES de lanzar, para que el poll no
     *  cuente lecturas nulas ni dispare una reconexión en paralelo. */
    private fun openAdb(what: String) {
        connecting = true
        ioScope.launch {
            val ok = AdbManager.connect(this@BankWatchService).isSuccess
            withContext(Dispatchers.Main) {
                connecting = false
                nullReads = 0
                if (finished) return@withContext
                if (ok) {
                    shell = AdbManager.shell(this@BankWatchService)
                    Log.i(TAG, "BankWatch: ADB $what")
                    // Con el shell ya vivo, asegurar el sondeo local para no
                    // volver a depender de ADB durante la sesión.
                    ioScope.launch { ensureUsageAccess() }
                } else {
                    // Sin conexión: readForegroundBlocking cae a UsageStats y el
                    // cierre mostrará el recordatorio (mismo fallback que Shizuku).
                    shell = null
                    Log.w(TAG, "BankWatch: ADB no conectó; fallback a UsageStats")
                }
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
