package com.agustin.videoboostao

/**
 * Selectores de UI de Pixel Camera. Aislados acá porque cada Feature Drop
 * puede cambiarlos: si el servicio deja de encontrar los nodos, volcar la
 * jerarquía con `adb exec-out uiautomator dump /dev/tty` (cámara en modo
 * video, panel de ajustes abierto) y actualizar estas listas.
 */
object Selectors {

    const val CAMERA_PACKAGE = "com.google.android.GoogleCamera"

    /**
     * Botón que abre/despliega el panel de ajustes de video (abajo a la
     * izquierda del visor). Resource-id estable confirmado por
     * `uiautomator dump` en un Pixel 10 Pro XL (Pixel Camera, jul-2026):
     * no depende del idioma del sistema.
     */
    const val VIDEO_SETTINGS_ENTRY_ID = "$CAMERA_PACKAGE:id/options_entry_button"

    /**
     * Chip del carrusel de modos ("Pan", "Blur", "Video", "Night Sight",
     * "Slow Motion"). Se usa para confirmar que el modo activo es Video
     * antes de tocar nada. Los textos localizados del chip y de la fila
     * "Video Boost" viven en [CameraLabels] (extraídos de la APK real de
     * Pixel Camera, 74 idiomas).
     */
    const val MODE_CHIP_TEXT_ID = "$CAMERA_PACKAGE:id/mode_chip_text"
}
