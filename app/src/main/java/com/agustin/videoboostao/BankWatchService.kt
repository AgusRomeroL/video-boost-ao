package com.agustin.videoboostao

import android.app.Service
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
 * apagado por una app sensible. Dos fases:
 *
 * - [Phase.MONITORING]: sondea el primer plano hasta detectar que la app sensible
 *   se cerró.
 * - [Phase.COUNTDOWN]: espera de gracia ([Prefs.reenableDelayMin]) antes de
 *   reactivar, con notificación interactiva. Salir del banco cinco segundos a
 *   mirar un dato y volver hacía que la app encontrara un servicio de
 *   accesibilidad recién activado y mostrara su error anti-fraude. Durante la
 *   espera se sigue vigilando: si vuelve una app sensible se cancela, y si
 *   aparece la cámara se reactiva al instante (para eso existe la app).
 *
 * Es un foreground service porque el proceso puede ser matado tras `disableSelf`;
 * se arranca DESDE el servicio de accesibilidad (aún activo) para esquivar la
 * restricción de FGS-en-background de Android 12+.
 *
 * Para reactivar se prefiere siempre la escritura local ([SecureSettings]), que
 * no abre ninguna conexión. El acceso privilegiado ([PrivilegedShell]: Shizuku
 * via su binder, ADB via una conexión TLS al propio daemon) es el fallback. La
 * vía ADB es I/O de red bloqueante, así que sus lecturas/escrituras corren en
 * [ioScope] (IO) y postean el resultado al [handler] del hilo principal.
 */
class BankWatchService : Service() {

    companion object {
        private const val TAG = "VideoBoostAO"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_LABEL = "label"

        /** Acciones de la notificación de cuenta atrás (ver [Notifications]). */
        const val ACTION_SNOOZE = "com.agustin.videoboostao.bank.SNOOZE"
        const val ACTION_NOW = "com.agustin.videoboostao.bank.NOW"

        private const val POLL_MS = 1200L
        /** Cadencia durante la cuenta atrás: nada urgente que detectar, pero la
         *  vía rápida es ver la cámara en primer plano y ahí la latencia la nota
         *  el usuario. */
        private const val COUNTDOWN_POLL_MS = 3_000L
        /** Sin "Acceso de uso" no se sondea NADA durante la cuenta atrás:
         *  sondear por ADB reintroduciría el ruido de la notificación de
         *  depuración inalámbrica del sistema. Solo se mira el reloj. */
        private const val COUNTDOWN_IDLE_POLL_MS = 30_000L
        /** Lecturas seguidas sin la app sensible para declararla cerrada
         *  (evita falsos positivos por SystemUI / diálogos transitorios). */
        private const val AWAY_CONFIRM = 2
        /** Tope de la FASE DE MONITOREO, por si nunca se ve el primer plano
         *  (binder de Shizuku caído, ADB desconectado, etc.): no dejar un FGS
         *  zombie. La cuenta atrás tiene su propio tope. */
        private const val MAX_MONITOR_MS = 15 * 60 * 1000L
        private const val MAX_SNOOZES = 3
        /** Tope duro de la fase de cuenta atrás, esperas incluidas: el peor caso
         *  legítimo más un minuto de holgura. */
        private const val MAX_COUNTDOWN_MS =
            (1 + MAX_SNOOZES) * Prefs.MAX_REENABLE_DELAY_MIN * 60_000L + 60_000L
        /** Lecturas nulas seguidas antes de dar la conexión ADB por muerta y
         *  reconectar desde cero (~5 s con POLL_MS = 1200). Se duplica en cada
         *  reconexión. */
        private const val RECONNECT_AFTER_NULLS = 4
        /** Tope de reconexiones ADB por sesión: insistir cada pocos segundos
         *  solo repetía la notificación del sistema sin arreglar nada. */
        private const val MAX_RECONNECTS = 2
        /** Intentos de reactivación por ADB al cerrarse la app sensible. */
        private const val ENABLE_ATTEMPTS = 6
        private const val ENABLE_RETRY_MS = 500L
        /** Fallos seguidos antes de rehacer la conexión ADB (ver [enableViaAdb]). */
        private const val ENABLE_RECONNECT_AFTER = 3

        private const val FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        fun start(context: Context, pkg: String, label: String?) {
            val i = Intent(context, BankWatchService::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(i)
        }
    }

    private enum class Phase { MONITORING, COUNTDOWN }

    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sensitivePkg: String? = null
    private var sensitiveLabel: String? = null
    private var mechanism = Capabilities.Mechanism.NONE
    private var startedAt = 0L

    private var phase = Phase.MONITORING
    /** Minutos de gracia de esta sesión (se fija al arrancar; cambiarlos en la
     *  UI a mitad de sesión no debe mover un contador ya en marcha). */
    private var delayMin = 0
    private var deadlineAt = 0L
    private var countdownStartedAt = 0L
    private var snoozes = 0
    private var reconnects = 0

    /** La app sensible ESTABA en primer plano al arrancar (el servicio de
     *  accesibilidad la detectó ahí): partir de true. Antes partía de false y,
     *  si la primera lectura útil llegaba cuando el usuario ya había cerrado
     *  la app (conexión lenta o sesión corta), el cierre no se detectaba nunca
     *  y la reactivación esperaba el tope de sesión de 15 minutos. */
    private var sawSensitive = true
    private var awayCount = 0
    private var finished = false

    /** La sesión ya se cerró del todo ([finishSession] corrió). Distinto de
     *  [finished], que se marca al empezar a reactivar y puede tener todavía una
     *  reactivación por ADB en vuelo. */
    private var sessionClosed = false
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
            // Con el shell vivo, asegurar los permisos que evitan depender de él
            // (ver [ensureLocalPrivileges]).
            if (shell != null) ioScope.launch { ensureLocalPrivileges() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_SNOOZE || action == ACTION_NOW) {
            handleCountdownAction(action)
            // NOT_STICKY: un toque en la notificación no debe convertirse en el
            // intent que se re-entrega si el proceso muere.
            return START_NOT_STICKY
        }

        val pkg = intent?.getStringExtra(EXTRA_PKG)
        if (pkg == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        resetSession(pkg, intent.getStringExtra(EXTRA_LABEL))

        postPausedForeground()

        mechanism = Capabilities.fullAutoMechanism(this)
        when (mechanism) {
            Capabilities.Mechanism.SHIZUKU -> bindShizuku()
            // Con "Acceso de uso" el sondeo es local: la conexión ADB solo hace
            // falta al final, y abrirla entonces evita 15 min de streams
            // inútiles (y de oportunidades de que la conexión se rompa). Con el
            // permiso de escritura local ya concedido no hace falta ni eso.
            Capabilities.Mechanism.ADB -> if (!Capabilities.hasUsageAccess(this)) connectAdb()
            Capabilities.Mechanism.NONE -> Unit
        }

        handler.postDelayed(pollRunnable, POLL_MS)
        // Si el sistema mata el proceso durante la sesión (pasa: tras
        // disableSelf ya no hay servicio de accesibilidad que lo proteja),
        // re-entregar el intent para retomar el monitoreo donde quedó. Una
        // cuenta atrás en vuelo no se persiste: al retomar se vuelve a detectar
        // el cierre y se arranca otra, que es el lado seguro.
        return START_REDELIVER_INTENT
    }

    private fun resetSession(pkg: String, label: String?) {
        handler.removeCallbacks(pollRunnable)
        sensitivePkg = pkg
        sensitiveLabel = label
        startedAt = System.currentTimeMillis()
        phase = Phase.MONITORING
        delayMin = Prefs.reenableDelayMin(this)
        deadlineAt = 0L
        countdownStartedAt = 0L
        snoozes = 0
        reconnects = 0
        sawSensitive = true
        awayCount = 0
        nullReads = 0
        finished = false
        sessionClosed = false
    }

    // --- Acciones de la notificación de cuenta atrás ---

    /**
     * "Esperar +X" / "Activar ya". La entrega llega por `startForegroundService`,
     * así que hay que re-afirmar el foreground dentro de la ventana de 5 s antes
     * de cualquier otra cosa (mismo patrón que [AdbPairingService]).
     */
    private fun handleCountdownAction(action: String) {
        if (sessionClosed) {
            // Doble toque: la notificación ya se convirtió en "Video Boost
            // volvió". Re-postear la cuenta atrás la resucitaría y el aviso
            // final se perdería al pararse el servicio. Parar cierra la ventana
            // de los 5 s igual que un startForeground.
            stopSelf()
            return
        }
        if (sensitivePkg == null) {
            // Acción de una sesión que ya no existe (el proceso murió y esto
            // creó un servicio sin sesión): retirar la notificación huérfana y
            // parar, que también cierra la ventana de los 5 s.
            Log.w(TAG, "BankWatch: acción $action sin sesión; descarto")
            Notifications.cancelReenable(this)
            stopSelf()
            return
        }
        postForegroundForPhase()
        // PendingIntent viejo (la espera ya se canceló o la reactivación ya está
        // en marcha): el foreground queda re-afirmado y no se toca nada más.
        if (phase != Phase.COUNTDOWN || finished) return
        when (action) {
            ACTION_SNOOZE -> onSnooze()
            else -> reenableNow()
        }
    }

    private fun onSnooze() {
        snoozes++
        // Desde AHORA y no desde el límite viejo: el usuario tocó "esperar"
        // porque necesita más tiempo ahora.
        deadlineAt = System.currentTimeMillis() + delayMin * 60_000L
        Log.i(TAG, "BankWatch: espera +$delayMin min ($snoozes/$MAX_SNOOZES)")
        postCountdownForeground()
    }

    // --- Sondeo ---

    private val pollRunnable = Runnable { poll() }

    private fun poll() {
        if (finished) return
        if (phase == Phase.COUNTDOWN) {
            pollCountdown()
            return
        }
        if (System.currentTimeMillis() - startedAt > MAX_MONITOR_MS) {
            // El tope ya es una espera larguísima y delata un monitoreo roto: no
            // encadenar además una cuenta atrás sobre un FGS quizá zombie.
            Log.w(TAG, "BankWatch: tope de monitoreo; reactivo sin espera")
            reenableNow()
            return
        }
        if (Capabilities.hasUsageAccess(this)) {
            // Vía normal: lectura local e instantánea, sin tocar ADB.
            if (!processForeground(ForegroundApp.current(this))) scheduleNext()
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
     *  declaró cerrada (el poll de monitoreo debe detenerse). */
    private fun processForeground(fg: String?): Boolean {
        when {
            fg == null -> {
                // Sin lectura. En la vía ADB, varios fallos seguidos delatan
                // una conexión zombie: reconectar en vez de esperar el tope.
                // Mientras haya un connect en vuelo no se cuenta: al arrancar
                // no hay conexión todavía y disparar otra ahí era justamente lo
                // que hacía que las dos se cerraran mutuamente.
                //
                // Con "Acceso de uso" NO se reconecta nunca: ahí la lectura es
                // local y un null solo significa "no hubo eventos en la ventana
                // de 10 s" (la app sensible lleva un rato quieta en primer
                // plano), no que la conexión esté muerta — de hecho ni se usa
                // ADB para leer. Reconectar ahí era lo que hacía re-aparecer la
                // notificación de "Depuración inalámbrica" del sistema cada
                // pocos segundos mientras la app sensible estaba abierta.
                if (!connecting) {
                    nullReads++
                    if (mechanism == Capabilities.Mechanism.ADB &&
                        !Capabilities.hasUsageAccess(this) &&
                        nullReads >= (RECONNECT_AFTER_NULLS shl reconnects)
                    ) {
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

    /** Vigilancia durante la espera de gracia. Decide con reloj de pared: un
     *  poll que llegue tarde (el `postDelayed` no avanza en deep sleep) sigue
     *  decidiendo bien, solo reactiva más tarde, que es el lado seguro. */
    private fun pollCountdown() {
        val now = System.currentTimeMillis()
        if (now >= deadlineAt || now - countdownStartedAt > MAX_COUNTDOWN_MS) {
            reenableNow()
            return
        }
        val local = Capabilities.hasUsageAccess(this)
        if (local) {
            val fg = ForegroundApp.current(this)
            when {
                fg == null -> Unit
                fg == Selectors.CAMERA_PACKAGE -> {
                    // El usuario quiere grabar YA: el retardo no debe dejarlo
                    // grabar sin Video Boost, que es el sentido de la app. Y si
                    // abrió la cámara, la app sensible ya quedó atrás.
                    Log.i(TAG, "BankWatch: cámara en primer plano; reactivo ya")
                    reenableNow()
                    return
                }
                // Cualquier app sensible, no solo la de esta sesión: saltar del
                // banco a la wallet también debe cancelar la espera.
                SensitiveApps.isSensitive(this, fg) -> {
                    backToMonitoring(fg)
                    return
                }
            }
        }
        val cadence = if (local) COUNTDOWN_POLL_MS else COUNTDOWN_IDLE_POLL_MS
        val next = (deadlineAt - now).coerceIn(500L, cadence)
        handler.postDelayed(pollRunnable, next)
    }

    /**
     * Primer plano vía UsageStats (preferido) o el mecanismo privilegiado.
     *
     * El orden importa: UsageStats es una lectura local, instantánea y sin
     * estado, mientras que cada lectura por ADB abre un stream nuevo sobre el
     * túnel TLS y puede colgarse (ver [AdbManager.execRetrying]). Sondear por
     * ADB cada 1.2 s era justamente lo que rompía la conexión. Por eso el
     * full-auto se auto-concede el permiso al arrancar
     * ([ensureLocalPrivileges]) y ADB queda para lo que de verdad lo necesita.
     */
    private fun readForegroundBlocking(): String? {
        if (Capabilities.hasUsageAccess(this)) {
            ForegroundApp.current(this)?.let { return it }
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
     * Con shell privilegiado, concederse lo que evita seguir dependiendo de él:
     * "Acceso de uso" (sondeo local del primer plano) y `WRITE_SECURE_SETTINGS`
     * (reactivación local, ver [SecureSettings]). Dos comandos una sola vez a
     * cambio de no volver a abrir una conexión ADB en una sesión normal.
     *
     * También en la vía Shizuku: su binder no sufre la fragilidad de streams,
     * pero el permiso local sobrevive a los reinicios y Shizuku no (hay que
     * rearrancarlo tras cada boot), así que el full-auto aguanta más.
     *
     * Todo se confirma por efecto, no por la salida del comando: esa se pierde a
     * menudo aunque el comando sí se ejecute (ver [AdbManager.exec]).
     */
    private fun ensureLocalPrivileges() {
        val s = shell ?: return
        if (!Capabilities.hasUsageAccess(this)) {
            s.grantUsageAccess(packageName)
            if (Capabilities.hasUsageAccess(this)) {
                // Queda anotado como concedido por nosotros para poder revocarlo
                // si el usuario apaga el full-auto (ver [Prefs.usageAccessSelfGranted]).
                Prefs.setUsageAccessSelfGranted(this, true)
                Log.i(TAG, "BankWatch: 'Acceso de uso' concedido; sondeo local")
            }
        }
        if (!SecureSettings.canWriteNow(this)) {
            s.grantSecureSettings(packageName)
            if (SecureSettings.canWriteNow(this)) {
                Prefs.setSecureSettingsSelfGranted(this, true)
                Log.i(TAG, "BankWatch: WRITE_SECURE_SETTINGS concedido; reactivación local")
            }
        }
    }

    // --- Cierre de la app sensible: espera de gracia y reactivación ---

    private fun onSensitiveClosed() {
        if (finished) return
        handler.removeCallbacks(pollRunnable)
        // Sin capacidad de reactivar (Mechanism.NONE y sin permiso local) no hay
        // nada que demorar: reactiva el usuario a mano y la espera la pone él.
        if (delayMin <= 0 || !Capabilities.canReenableItself(this)) {
            reenableNow()
            return
        }
        startCountdown()
    }

    private fun startCountdown() {
        phase = Phase.COUNTDOWN
        countdownStartedAt = System.currentTimeMillis()
        deadlineAt = countdownStartedAt + delayMin * 60_000L
        snoozes = 0
        Log.i(TAG, "BankWatch: app sensible cerrada; espero $delayMin min")
        // Cambiar el id de la notificación del FGS hace que el sistema retire la
        // anterior (la de "en pausa"), así que solo hay una visible.
        postCountdownForeground()
        handler.postDelayed(pollRunnable, COUNTDOWN_POLL_MS)
    }

    /** Volvió una app sensible durante la espera: cancelarla y seguir vigilando
     *  (justo el caso que motiva todo esto). */
    private fun backToMonitoring(pkg: String) {
        Log.i(TAG, "BankWatch: volvió una app sensible ($pkg); cancelo la espera")
        phase = Phase.MONITORING
        if (pkg != sensitivePkg) {
            sensitivePkg = pkg
            sensitiveLabel = SensitiveApps.label(this, pkg)
        }
        sawSensitive = true
        awayCount = 0
        nullReads = 0
        // El tope de monitoreo cuenta desde ahora: es una sesión nueva de hecho.
        startedAt = System.currentTimeMillis()
        postPausedForeground()
        scheduleNext()
    }

    private fun reenableNow() {
        if (finished) return
        finished = true
        handler.removeCallbacks(pollRunnable)
        if (phase == Phase.MONITORING) Notifications.cancelPaused(this)

        val component = SecureSettings.ownComponent(this)
        // 1) Escritura local: instantánea y sin abrir ninguna conexión. No se
        //    consulta el permiso, se intenta y se verifica el efecto: la caché
        //    de permisos del proceso puede ir por detrás del `pm grant`.
        if (SecureSettings.enableAccessibilityService(this, component)) {
            Log.i(TAG, "BankWatch: reactivado en local")
            finishSession(true)
            return
        }
        if (mechanism == Capabilities.Mechanism.ADB) {
            // Re-activar por ADB es un round-trip TLS -> fuera del hilo
            // principal. El resultado se comprueba en local (ver enableViaAdb).
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
        var failures = 0
        var redials = 0
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
            failures++
            // Antes se rehacía la conexión en CADA intento: hasta 8
            // connect+disconnect por cierre, y cada connect hace re-sonar la
            // notificación de "Depuración inalámbrica" del sistema (que es de
            // Ajustes y no se puede silenciar). El fallo de libadb es por
            // stream, no por conexión (ver [AdbManager.exec]), así que casi
            // nunca ayudaba: ahora solo tras varios fallos y con tope.
            if (failures >= ENABLE_RECONNECT_AFTER && redials < MAX_RECONNECTS) {
                failures = 0
                redials++
                AdbManager.disconnect()
            }
            if (i < ENABLE_ATTEMPTS - 1) delay(ENABLE_RETRY_MS)
        }
        return Capabilities.accessibilityServiceEnabled(ctx, component)
    }

    private fun finishSession(reenabled: Boolean) {
        Log.i(TAG, "BankWatch: sesión terminada; reactivado=$reenabled")
        sessionClosed = true
        if (phase == Phase.COUNTDOWN) {
            // DETACH y no REMOVE: la notificación de la cuenta atrás es la del
            // FGS y hay que dejarla viva para reemplazarla EN EL SITIO por el
            // aviso final; mismo id = actualización = sin sonido (ver
            // [Notifications.showReenabled]).
            stopForeground(STOP_FOREGROUND_DETACH)
            // Si no se reactivó, el aviso de abajo va en otro id: no dejar una
            // cuenta atrás huérfana contando hacia atrás para siempre.
            if (!reenabled) Notifications.cancelReenable(this)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (reenabled) {
            // onServiceConnected del a11y service limpia disabledByBank y cancela
            // notifs; el aviso "reactivado" (id distinto) no lo toca.
            Notifications.showReenabled(this)
        } else {
            Notifications.showClosedReminder(this, sensitiveLabel)
        }
        teardownShell()
        stopSelf()
    }

    // --- Notificación del foreground service ---

    private fun postForegroundForPhase() {
        if (phase == Phase.COUNTDOWN) postCountdownForeground() else postPausedForeground()
    }

    private fun postPausedForeground() {
        startForeground(
            Notifications.NOTIF_ID_PAUSED,
            Notifications.buildPausedOngoing(this, sensitiveLabel),
            FGS_TYPE,
        )
    }

    private fun postCountdownForeground() {
        startForeground(
            Notifications.NOTIF_ID_REENABLE,
            Notifications.buildReenableCountdown(
                this,
                sensitiveLabel,
                deadlineAt,
                delayMin,
                canSnooze = snoozes < MAX_SNOOZES,
            ),
            FGS_TYPE,
        )
    }

    // --- Conexiones ---

    private fun connectAdb() = openAdb("conectado")

    /** Descarta la conexión ADB actual y prueba una nueva. Con el guard de
     *  [connecting], a lo sumo un intento en vuelo; con [MAX_RECONNECTS], un
     *  número acotado por sesión. */
    private fun reconnectAdb() {
        if (connecting || finished) return
        nullReads = 0
        if (reconnects >= MAX_RECONNECTS) {
            Log.w(TAG, "BankWatch: tope de reconexiones ADB; sigo con UsageStats")
            return
        }
        reconnects++
        Log.w(TAG, "BankWatch: lecturas ADB muertas; reconecto ($reconnects/$MAX_RECONNECTS)")
        AdbManager.disconnect()
        openAdb("reconectado")
    }

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
                    // Con el shell ya vivo, asegurar los permisos que evitan
                    // volver a depender de ADB durante la sesión.
                    ioScope.launch { ensureLocalPrivileges() }
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
                // Con reactivación local puede no haberse abierto nunca nada.
                if (shell != null || AdbManager.isConnected()) {
                    try {
                        AdbManager.disconnect()
                    } catch (_: Exception) {
                    }
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
