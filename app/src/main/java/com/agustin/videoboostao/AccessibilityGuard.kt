package com.agustin.videoboostao

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Defensa contra el secuestro de la accesibilidad por un `UiAutomation` ajeno.
 *
 * Cualquier proceso con identidad shell (uid 2000) puede registrar un
 * `UiAutomation`: lo hacen `uiautomator`, Appium, los runners de tests
 * instrumentados y varias herramientas de automatización de escritorio que
 * dejan un agente corriendo en el teléfono. Mientras ese registro vive, el
 * AccessibilityManagerService:
 *
 * 1. **suprime todos** los servicios de accesibilidad del dispositivo, y
 * 2. **vacía** `enabled_accessibility_services`.
 *
 * Las dos cosas pasan sin aviso y le ocurren a cualquier app de accesibilidad,
 * no solo a esta. Lo que se ve desde acá es desconcertante: la escritura del
 * setting dice que funcionó (releerlo confirma el componente), pero el servicio
 * nunca se enlaza, así que la app parece encendida y no hace nada. Y cuando el
 * sistema vacía la lista, el servicio queda apagado incluso después de que la
 * herramienta se cierre.
 *
 * Este objeto cubre las dos mitades del problema:
 * - **Diagnóstico honesto**: [reportBindOutcome] distingue "el setting se
 *   escribió" de "el servicio está vivo" y avisa una sola vez por episodio, en
 *   vez de dejar al usuario mirando una app que dice estar activa.
 * - **Recuperación sola**: [repairIfNeeded] vuelve a poner el componente en la
 *   lista cuando el sistema la vació, sin que el usuario tenga que volver a
 *   Ajustes. Solo si ya lo había activado antes ([Prefs.serviceEverEnabled]):
 *   este permiso no es para encenderle a nadie un servicio que nunca quiso.
 *
 * La única señal fiable de "vivo" es [VideoBoostService.instance], que el propio
 * servicio pone en `onServiceConnected` y limpia al morir. Leer el setting dice
 * lo que el sistema tiene apuntado, no lo que está corriendo, y esa diferencia
 * es justo el síntoma.
 */
object AccessibilityGuard {

    private const val TAG = "VideoBoostAO"

    /** Margen para que el sistema enlace el servicio tras escribir el setting.
     *  Medido en un Pixel: el bind llega en bastante menos de un segundo. */
    const val BIND_GRACE_MS = 2_500L

    /** Cada cuánto se re-escribe el setting mientras dura la supresión. Es un
     *  empujón barato por si el sistema dejó el componente en la lista pero sin
     *  enlazar: cuando la herramienta ajena se cierra, el siguiente empujón lo
     *  revive sin esperar a que el usuario haga nada. */
    private const val NUDGE_INTERVAL_MS = 5_000L

    @Volatile
    private var reported = false

    @Volatile
    private var lastNudgeAt = 0L

    /** ¿El servicio está REALMENTE enlazado, no solo listado en el setting? */
    fun isRunning(): Boolean = VideoBoostService.instance != null

    /** ¿El sistema tiene apuntado nuestro componente como activo? */
    fun isListed(context: Context): Boolean =
        Capabilities.accessibilityServiceEnabled(context, SecureSettings.ownComponent(context))

    /**
     * El síntoma: apuntado como activo pero sin enlazar. Para la UI, que no
     * tiene forma de esperar el margen de bind.
     */
    fun looksSuppressed(context: Context): Boolean = isListed(context) && !isRunning()

    /**
     * Resultado de un intento de encendido, ya pasado [BIND_GRACE_MS].
     *
     * Avisa una sola vez por episodio (`reported`) porque en modo bajo demanda
     * esto se evalúa en cada apertura de la cámara, y una notificación por
     * apertura sería ruido. Al recuperarse se cancela el aviso y se vuelve a
     * armar para el próximo episodio.
     */
    fun reportBindOutcome(context: Context, bound: Boolean) {
        if (bound) {
            if (reported) {
                Log.i(TAG, "Guard: la accesibilidad volvió; el bloqueo externo terminó")
                Notifications.cancelAccessibilityBlocked(context)
            }
            reported = false
            return
        }
        if (reported) return
        reported = true
        Log.w(
            TAG,
            "Guard: el servicio está en la lista pero no se enlaza; " +
                "otra app tiene tomado el UiAutomation del sistema",
        )
        Notifications.showAccessibilityBlocked(context)
    }

    /**
     * Re-escribe el setting mientras dura la supresión, como mucho cada
     * [NUDGE_INTERVAL_MS]. Devuelve true si empujó.
     *
     * Hace falta porque el sistema a veces deja el componente listado y sin
     * enlazar: ahí nadie reintentaría, y la app se quedaría muerta hasta el
     * siguiente cambio de estado.
     */
    fun nudge(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNudgeAt < NUDGE_INTERVAL_MS) return false
        lastNudgeAt = now
        SecureSettings.enableOwnAccessibilityService(context)
        return true
    }

    /**
     * ¿La lista de servicios la vació el sistema, o la vació el usuario?
     *
     * Distinguirlo es lo que separa "reparar" de "pelearse con el usuario", y
     * hay una firma clara:
     * - Un `UiAutomation` ajeno **borra la lista entera** y deja
     *   `accessibility_enabled` en 1. Nadie apagó nada a mano: el sistema la
     *   vació de paso.
     * - Cuando el usuario apaga el último servicio desde Ajustes, Android pone
     *   además `accessibility_enabled` en 0 (es lo mismo que hace esta app en
     *   [SecureSettings.disableAccessibilityService]).
     * - Y si el usuario apaga SOLO este servicio teniendo otros, la lista queda
     *   con los otros dentro: no está vacía.
     *
     * Así que solo el primer caso se repara. En los otros dos la decisión es
     * del usuario y la app no tiene nada que opinar, aunque técnicamente pueda
     * escribir el setting.
     */
    fun wipedByHijack(context: Context): Boolean {
        val list = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (list.split(':').any { it.isNotBlank() }) return false
        return android.provider.Settings.Secure.getInt(
            context.contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
    }

    /**
     * Repara el modo siempre activo: si el sistema vació la lista y la app
     * puede escribirla, vuelve a meterse. Lo llaman los momentos en que algo de
     * la app corre con el servicio caído: abrir la pantalla principal, el
     * arranque del teléfono y [AccessibilityRepairJob].
     *
     * En modo bajo demanda no hace nada: ahí el servicio está apagado casi
     * siempre a propósito y de reactivarlo se encarga [CameraWatchService].
     */
    fun repairIfNeeded(context: Context): Boolean {
        if (!Prefs.featureEnabled(context)) return false
        if (Capabilities.onDemandMode(context)) return false
        if (!Prefs.serviceEverEnabled(context)) return false
        if (!wipedByHijack(context)) return false
        if (!Capabilities.canWriteSecureSettings(context)) return false
        val repaired = SecureSettings.enableOwnAccessibilityService(context)
        if (repaired) {
            Log.i(TAG, "Guard: la lista de accesibilidad estaba vacía; me volví a activar")
        }
        return repaired
    }
}
