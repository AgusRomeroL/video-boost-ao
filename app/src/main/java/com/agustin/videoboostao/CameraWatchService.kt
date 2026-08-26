package com.agustin.videoboostao

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Motor del modo **bajo demanda** ([ActivationMode.ON_DEMAND]): el servicio de
 * accesibilidad está APAGADO salvo mientras Pixel Camera está en primer plano.
 *
 * Es lo que hace el modo posible: sin él nadie podría encender el servicio,
 * porque una app no puede activarse sola por API pública. Acá sí, porque el modo
 * exige los dos permisos especiales que la app se auto-concede una vez por
 * ADB/Shizuku ([Capabilities.canRunOnDemand]): "Acceso de uso" para leer el
 * primer plano y `WRITE_SECURE_SETTINGS` para escribir la lista de servicios de
 * accesibilidad ([SecureSettings]).
 *
 * **Detección en dos capas**, porque ninguna sola alcanza:
 * - *Push*: [CameraManager.AvailabilityCallback]. Avisa en el instante en que
 *   alguien abre la cámara, sin sondear. Pero avisa de CUALQUIER app (Instagram,
 *   WhatsApp), así que nunca se actúa sin confirmar el primer plano.
 * - *Sondeo*: [ForegroundApp] cada [POLL_IDLE_MS] con la pantalla encendida, por
 *   si el callback no se entrega en segundo plano; y a [POLL_ACTIVE_MS] mientras
 *   el servicio está encendido, que es la vía por la que se detecta la salida.
 *   Con la pantalla apagada no se sondea nada: ahí no se abre ninguna cámara.
 *
 * **Salida con gracia** ([Prefs.onDemandLingerSec]): salir un momento a la
 * galería y volver es lo normal, y apagar/encender en cada ida y vuelta hace que
 * la cámara se encuentre un servicio recién activado. Una app sensible en primer
 * plano corta la gracia y apaga al instante, que es justo el caso que la app
 * viene evitando desde la v2.4.
 *
 * Es un foreground service porque el proceso tiene que sobrevivir con el
 * servicio de accesibilidad apagado: sin él no queda nada vivo en el proceso.
 */
class CameraWatchService : Service() {

    companion object {
        private const val TAG = "VideoBoostAO"

        /** Cadencia de respaldo con la pantalla encendida y el servicio apagado:
         *  solo tiene que atrapar una apertura que el callback no haya avisado. */
        private const val POLL_IDLE_MS = 3_000L

        /** Cadencia con el servicio encendido: acá sí importa la latencia, es
         *  como se detecta que la cámara dejó el primer plano. */
        private const val POLL_ACTIVE_MS = 1_000L

        /** Lecturas seguidas sin la cámara para darla por ida (evita falsos
         *  positivos por diálogos de permisos o de SystemUI sobre el visor). */
        private const val AWAY_CONFIRM = 2

        private const val FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        /** Para que la UI sepa si el vigilante está vivo sin preguntarle al
         *  sistema, que no expone de forma fiable si un servicio corre. Mismo
         *  patrón que [VideoBoostService.instance]. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CameraWatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraWatchService::class.java))
        }

        /**
         * Arranca o para el vigilante según el modo guardado y el interruptor
         * maestro. Único punto de decisión: lo llaman la UI, el arranque del
         * sistema ([BootReceiver]) y el propio servicio de accesibilidad.
         */
        fun sync(context: Context) {
            if (Capabilities.onDemandMode(context) && Prefs.featureEnabled(context)) {
                start(context)
            } else {
                stop(context)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = Runnable { poll() }
    private val disableRunnable = Runnable { disableNow("gracia cumplida") }

    private var screenOn = true
    private var awayCount = 0

    private val cameraManager: CameraManager? by lazy {
        getSystemService(CameraManager::class.java)
    }

    /**
     * Avisos del sistema sobre el uso de las cámaras: llegan en el instante en
     * que una app toma o suelta una, sin sondear nada.
     *
     * Lo que NO dicen es quién la tomó (la variante `onCameraOpened` que trae el
     * paquete del cliente es `@SystemApi`, no está en el SDK público), así que
     * cada aviso solo adelanta una comprobación del primer plano: quien decide
     * siempre es [ForegroundApp]. Es justo lo que evita encender el servicio
     * porque Instagram o WhatsApp abrieron su cámara.
     */
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            // Alguna app tomó una cámara: puede ser la de Pixel o cualquier otra.
            repoll()
        }

        override fun onCameraAvailable(cameraId: String) {
            // Se liberó una cámara. No implica que el visor se haya cerrado
            // (Pixel Camera la suelta y la retoma sola), así que esto solo
            // adelanta un sondeo; quien decide sigue siendo el primer plano.
            repoll()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    repoll()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    handler.removeCallbacks(pollRunnable)
                    // Con la pantalla apagada no hay sesión de cámara que
                    // sostener, pero se respeta la gracia igual: apagar y
                    // encender la pantalla en dos segundos no debe costar un
                    // ciclo completo de apagado y encendido del servicio.
                    if (serviceOn()) scheduleDisable()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            // Broadcasts protegidos del sistema, nadie más puede emitirlos:
            // NOT_EXPORTED es lo correcto y lo que Android 14+ exige declarar.
            Context.RECEIVER_NOT_EXPORTED,
        )
        try {
            cameraManager?.registerAvailabilityCallback(cameraCallback, handler)
        } catch (t: Throwable) {
            // Sin el callback el modo sigue funcionando por sondeo, solo con
            // algo más de latencia: no es motivo para caerse.
            Log.w(TAG, "Watch: no se pudo registrar el callback de cámara", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Dentro de la ventana de 5 s, antes que cualquier otra cosa.
        startForeground(
            Notifications.NOTIF_ID_WATCH,
            Notifications.buildWatchOngoing(this),
            FGS_TYPE,
        )

        // El modo puede haber cambiado entre el start y esta entrega (o venir de
        // un START_STICKY posterior a un cambio de ajustes): comprobarlo acá es
        // lo que evita dejar un vigilante zombie.
        if (!Capabilities.onDemandMode(this) || !Prefs.featureEnabled(this)) {
            Log.i(TAG, "Watch: el modo bajo demanda ya no aplica; me paro")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "Watch: vigilando la apertura de la cámara")
        repoll()
        return START_STICKY
    }

    // --- Sondeo y decisión ---

    private fun repoll() {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun poll() {
        if (!screenOn) return
        val fg = ForegroundApp.current(this)
        // Una sola lectura del setting por vuelta: es una consulta al provider y
        // esto corre cada segundo mientras la cámara está abierta.
        val on = serviceOn()
        when {
            // Sin lectura no se decide nada: null no significa "la cámara no
            // está", significa "no hubo transiciones en la ventana" (una app
            // quieta en primer plano deja de generar eventos).
            fg == null -> Unit

            fg == Selectors.CAMERA_PACKAGE -> {
                awayCount = 0
                cancelPendingDisable()
                if (!on) enableNow("cámara en primer plano")
            }

            on -> {
                // Una app sensible no espera la gracia: es exactamente el
                // bloqueo anti-fraude que este modo viene a evitar.
                if (SensitiveApps.isSensitive(this, fg)) {
                    disableNow("app sensible en primer plano ($fg)")
                } else {
                    awayCount++
                    if (awayCount >= AWAY_CONFIRM) scheduleDisable()
                }
            }
        }
        // La cadencia se decide con el estado ya actualizado: si esta vuelta
        // encendió el servicio, hay que pasar al ritmo rápido ahora, no en la
        // siguiente.
        handler.postDelayed(pollRunnable, if (serviceOn()) POLL_ACTIVE_MS else POLL_IDLE_MS)
    }

    // --- Encendido y apagado del servicio de accesibilidad ---

    private fun serviceOn(): Boolean =
        Capabilities.accessibilityServiceEnabled(this, SecureSettings.ownComponent(this))

    private fun enableNow(why: String) {
        // No se consulta el permiso, se intenta y se verifica el efecto: mismo
        // criterio que el resto de la app (ver [SecureSettings]).
        if (SecureSettings.enableOwnAccessibilityService(this)) {
            Log.i(TAG, "Watch: servicio encendido ($why)")
            awayCount = 0
        } else {
            // Sin el permiso el modo no se sostiene. La preferencia no se toca
            // (el permiso puede volver): [Capabilities.activationMode] ya
            // degrada sola a "siempre activo" y la UI lo explica.
            Log.w(TAG, "Watch: no se pudo encender el servicio; falta WRITE_SECURE_SETTINGS")
        }
    }

    private fun scheduleDisable() {
        if (handler.hasCallbacks(disableRunnable)) return
        val linger = Prefs.onDemandLingerSec(this)
        if (linger <= 0) {
            disableNow("salida de la cámara")
            return
        }
        Log.d(TAG, "Watch: cámara fuera; apago en ${linger}s")
        handler.postDelayed(disableRunnable, linger * 1000L)
    }

    private fun cancelPendingDisable() {
        if (handler.hasCallbacks(disableRunnable)) {
            Log.d(TAG, "Watch: la cámara volvió; cancelo el apagado")
            handler.removeCallbacks(disableRunnable)
        }
    }

    /**
     * Apaga el servicio de accesibilidad. Primero la escritura local, que se
     * verifica releyendo el setting; `disableSelf()` queda de respaldo porque es
     * asíncrono y no confirma nada, aunque es la única vía si el permiso se
     * perdió.
     */
    private fun disableNow(why: String) {
        handler.removeCallbacks(disableRunnable)
        awayCount = 0
        if (!serviceOn()) return
        if (SecureSettings.disableOwnAccessibilityService(this)) {
            Log.i(TAG, "Watch: servicio apagado ($why)")
        } else {
            Log.w(TAG, "Watch: el apagado local falló; pruebo disableSelf ($why)")
            VideoBoostService.instance?.disableSelf()
        }
        Notifications.cancelActivated(this)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(disableRunnable)
        try {
            cameraManager?.unregisterAvailabilityCallback(cameraCallback)
        } catch (_: Throwable) {
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
