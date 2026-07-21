package com.agustin.videoboostao

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reactiva Video Boost cada vez que Pixel Camera pasa a primer plano en
 * modo video. Pixel Camera lo modela como un control segmentado de dos
 * ImageButton ("Video Boost off" / "Video Boost on"), no como un Switch:
 * no hay isChecked, el estado se lee con isSelected. El botón "on" es
 * siempre el de más a la derecha dentro de la fila (mismo patrón que
 * Flash, Resolución, FPS y HDR en el mismo panel), así que la detección
 * no depende del idioma del sistema.
 *
 * Secuencia: detectar cámara → confirmar modo video → si el panel de
 * ajustes está colapsado, abrirlo con el botón `options_entry_button` →
 * localizar la fila "Video Boost" → si el botón "on" no está ya
 * seleccionado, tocarlo → verificar → cerrar el panel.
 */
class VideoBoostService : AccessibilityService() {

    companion object {
        private const val TAG = "VideoBoostAO"

        private const val ATTEMPT_DELAY_MS = 150L
        private const val PANEL_SETTLE_MS = 150L
        private const val MAX_ATTEMPTS = 30

        /** Tiempo mínimo entre clicks al botón de entrada del panel. Debe ser
         *  mayor que el peor tiempo de inflado del panel (~1 s medido en el
         *  Pixel 10 Pro XL): re-clickear antes lo cierra en plena animación. */
        private const val ENTRY_RECLICK_COOLDOWN_MS = 1500L

        /** Máximo de aperturas de panel por sesión antes de desistir. */
        private const val MAX_ENTRY_CLICKS = 3

        /** Espera antes de confirmar que la cámara dejó el primer plano. Los
         *  gestos del sistema (p. ej. nuestro BACK) emiten eventos de ventana
         *  de SystemUI con la cámara aún visible; resetear ahí re-dispara la
         *  activación en bucle. */
        private const val RESET_CONFIRM_MS = 600L

        /** Instancia viva del servicio, para que la UI pueda deshabilitarlo
         *  (una app solo puede apagar su propio servicio con `disableSelf`). */
        @Volatile
        var instance: VideoBoostService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Si volvió a habilitarse, ya no está "apagado por el banco".
        Prefs.setDisabledByBank(this, false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val attemptRunnable = Runnable { attemptActivation() }
    private val resetCheckRunnable = Runnable { confirmCameraGone() }

    /** Ya se activó (o verificó activo) en esta sesión de cámara. */
    private var handledThisSession = false

    /** El panel de Video Settings lo abrimos nosotros (para cerrarlo después). */
    private var panelOpenedByUs = false

    /** uptime del último click al botón de entrada (guarda anti-doble-click). */
    private var lastEntryClickMs = 0L

    private var entryClicks = 0
    private var attempts = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // Auto-apagado para apps bancarias, antes que todo (incluso en pausa):
        // los bancos bloquean cualquier servicio de accesibilidad activo, así
        // que nos sacamos de la lista con disableSelf() al detectar una.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            Prefs.autoDisableForBanks(this) && BankApps.isBank(pkg)
        ) {
            Log.i(TAG, "App bancaria en primer plano ($pkg); deshabilitando el servicio")
            Prefs.setDisabledByBank(this, true)
            disableSelf()
            return
        }

        // Interruptor maestro de la app: pausado = no hacer nada, sin
        // necesidad de deshabilitar el servicio de accesibilidad.
        if (!Prefs.featureEnabled(this)) return

        // Sin filtro de package en el manifest: el filtrado es acá. Los
        // eventos de otras apps solo sirven para detectar que la cámara
        // dejó el primer plano y resetear la sesión; nunca se inspecciona
        // ni toca nada fuera de Pixel Camera.
        if (pkg != Selectors.CAMERA_PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                (handledThisSession || attempts > 0) &&
                !handler.hasCallbacks(resetCheckRunnable)
            ) {
                handler.postDelayed(resetCheckRunnable, RESET_CONFIRM_MS)
            }
            return
        }

        // Actividad de la cámara: cancelar cualquier reset pendiente.
        handler.removeCallbacks(resetCheckRunnable)

        if (handledThisSession) return

        // Throttle (no debounce): la ráfaga de eventos durante la carga del
        // visor no pospone el intento: el primero corre a los 150 ms del
        // primer evento y de ahí se sondea a ritmo fijo.
        if (!handler.hasCallbacks(attemptRunnable)) {
            handler.postDelayed(attemptRunnable, ATTEMPT_DELAY_MS)
        }
    }

    /** Solo resetea si la cámara realmente dejó el primer plano. */
    private fun confirmCameraGone() {
        val fg = rootInActiveWindow?.packageName?.toString()
        if (fg == Selectors.CAMERA_PACKAGE) return
        Log.d(TAG, "Cámara en segundo plano; reseteo sesión")
        resetSession()
    }

    private fun resetSession() {
        handledThisSession = false
        panelOpenedByUs = false
        lastEntryClickMs = 0L
        entryClicks = 0
        attempts = 0
        handler.removeCallbacks(attemptRunnable)
    }

    private fun attemptActivation() {
        if (handledThisSession) return
        if (attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "Agotados $MAX_ATTEMPTS intentos sin encontrar los nodos; desisto esta sesión")
            handledThisSession = true
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != Selectors.CAMERA_PACKAGE) {
            return
        }

        if (!isInVideoMode(root)) {
            // Modo foto/retrato: no actuar ni reintentar. El próximo evento
            // de la cámara (p. ej. cambio a modo video) re-dispara el chequeo.
            return
        }
        attempts++

        // Caso 1: la fila "Video Boost" ya está a la vista (panel abierto).
        val onButton = findVideoBoostOnButton(root)
        if (onButton != null) {
            ensureSelected(onButton)
            return
        }

        // Caso 2: panel colapsado, abrirlo con el botón de entrada.
        val entry = root.findAccessibilityNodeInfosByViewId(Selectors.VIDEO_SETTINGS_ENTRY_ID)
            ?.firstOrNull()
        if (entry != null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEntryClickMs < ENTRY_RECLICK_COOLDOWN_MS) {
                // Panel inflándose tras nuestro click reciente: solo esperar.
                retry()
                return
            }
            if (entryClicks >= MAX_ENTRY_CLICKS) {
                Log.w(TAG, "El panel no expone Video Boost tras $entryClicks aperturas; desisto esta sesión")
                handledThisSession = true
                return
            }
            Log.d(TAG, "Abriendo panel Video Settings (intento $attempts)")
            if (entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastEntryClickMs = now
                entryClicks++
                panelOpenedByUs = true
                handler.postDelayed(attemptRunnable, PANEL_SETTLE_MS)
            } else {
                retry()
            }
            return
        }

        retry()
    }

    private fun retry() {
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, ATTEMPT_DELAY_MS)
    }

    private fun ensureSelected(onButton: AccessibilityNodeInfo) {
        if (onButton.isSelected) {
            Log.i(TAG, "Video Boost ya estaba activo")
            finishSession()
            return
        }

        Log.i(TAG, "Activando Video Boost")
        if (onButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed({ verifyAndClose() }, PANEL_SETTLE_MS)
        } else {
            retry()
        }
    }

    private fun verifyAndClose() {
        val root = rootInActiveWindow ?: run { finishSession(); return }
        val onButton = findVideoBoostOnButton(root)
        if (onButton != null && !onButton.isSelected) {
            Log.w(TAG, "El botón 'on' sigue sin seleccionar tras el click; reintento")
            retry()
            return
        }
        Log.i(TAG, "Video Boost activado y verificado")
        finishSession()
    }

    private fun finishSession() {
        handledThisSession = true
        handler.removeCallbacks(attemptRunnable)
        if (panelOpenedByUs) {
            // Dejar el visor limpio como lo encontró el usuario.
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 100L)
            panelOpenedByUs = false
        }
    }

    // ---------- Búsqueda de nodos ----------

    /** Idioma actual del sistema (el mismo con el que Pixel Camera se renderiza). */
    private fun systemLanguage(): String =
        resources.configuration.locales[0]?.language ?: "en"

    private fun isInVideoMode(root: AccessibilityNodeInfo): Boolean {
        val chips = root.findAccessibilityNodeInfosByViewId(Selectors.MODE_CHIP_TEXT_ID) ?: return false
        val labels = CameraLabels.videoModeLabels(systemLanguage())
        return chips.any { chip ->
            chip.isSelected && labels.any { label ->
                chip.text?.toString()?.equals(label, ignoreCase = true) == true ||
                    chip.contentDescription?.toString()?.equals(label, ignoreCase = true) == true
            }
        }
    }

    /**
     * Encuentra el ImageButton "on" de la fila Video Boost.
     *
     * Estrategia primaria: identificarlo por su `contentDescription`
     * localizada (sapphire_on_desc, p. ej. "Video Boost on"). Es inmune a la
     * posición, así que funciona igual en layouts RTL (árabe, hebreo, farsi,
     * urdu) donde el orden visual de los botones se invierte.
     *
     * Respaldo: si la descripción cambiara en una versión futura, ubicar la
     * fila por su etiqueta y elegir el botón del extremo según la dirección
     * del layout (LTR → derecha, RTL → izquierda).
     */
    private fun findVideoBoostOnButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val lang = systemLanguage()

        // Primaria: match exacto de contentDescription contra los candidatos "on".
        for (desc in CameraLabels.videoBoostOnDescs(lang)) {
            val matches = root.findAccessibilityNodeInfosByText(desc) ?: continue
            val button = matches.firstOrNull {
                it.className == "android.widget.ImageButton" &&
                    it.isClickable &&
                    it.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true
            }
            if (button != null) return button
        }

        // Respaldo: fila por etiqueta + extremo según dirección del layout.
        var label: AccessibilityNodeInfo? = null
        for (candidate in CameraLabels.videoBoostLabels(lang)) {
            val matches = root.findAccessibilityNodeInfosByText(candidate)
            label = matches?.firstOrNull { it.isVisibleToUser } ?: matches?.firstOrNull()
            if (label != null) break
        }
        if (label == null) return null

        val row = label.parent ?: label
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectClickableImageButtons(row, buttons, depth = 0)
        if (buttons.isEmpty()) return null

        val rtl = resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        return if (rtl) buttons.minByOrNull { boundsOf(it).left }
        else buttons.maxByOrNull { boundsOf(it).left }
    }

    private fun collectClickableImageButtons(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 4) return
        if (node.className == "android.widget.ImageButton" && node.isClickable) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableImageButtons(child, out, depth + 1)
        }
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    override fun onInterrupt() {
        handler.removeCallbacks(attemptRunnable)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(attemptRunnable)
        super.onDestroy()
    }
}
