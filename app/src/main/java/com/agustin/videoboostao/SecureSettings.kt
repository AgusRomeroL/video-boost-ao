package com.agustin.videoboostao

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Escritura LOCAL de secure settings, sin shell privilegiado.
 *
 * Requiere `WRITE_SECURE_SETTINGS`, que la app se auto-concede una única vez con
 * `pm grant` (ver el manifest y [InitialEnable]). Con el permiso puesto, volver
 * a activar el servicio de accesibilidad es una escritura instantánea en el
 * provider: no hay conexión ADB que abrir, ni streams que se cuelguen (ver
 * [AdbManager.exec]), ni la notificación del sistema de "Depuración inalámbrica"
 * re-sonando en cada connect. Es la vía preferida; ADB/Shizuku quedan de
 * fallback cuando el permiso no está.
 *
 * Contraparte de solo-lectura: [Capabilities.accessibilityServiceEnabled].
 */
object SecureSettings {

    private const val TAG = "VideoBoostAO"

    /**
     * Añade [component] a `enabled_accessibility_services` y enciende la
     * accesibilidad global. Mismo efecto que [AdbShellCommands.enableScript]
     * pero en local.
     *
     * Devuelve true solo si se verifica POR EFECTO (releyendo el setting), igual
     * criterio que la vía ADB: es la única confirmación que no miente.
     */
    fun enableAccessibilityService(context: Context, component: String): Boolean = try {
        val cr = context.contentResolver
        val current = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val parts = current.split(':').filter { it.isNotBlank() }
        if (parts.none { it.equals(component, ignoreCase = true) }) {
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (parts + component).joinToString(":"),
            )
        }
        // Siempre: la lista puede estar bien y la accesibilidad global apagada.
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Capabilities.accessibilityServiceEnabled(context, component)
    } catch (e: Exception) {
        // SecurityException si el permiso no está concedido: es el caso normal
        // antes del `pm grant`, no un error. El caller cae al fallback.
        Log.d(TAG, "Escritura local no disponible: ${e.javaClass.simpleName}")
        false
    }

    /** Igual que [enableAccessibilityService] pero resolviendo el componente de
     *  este propio servicio, que es el único que la app puede reactivar. */
    fun enableOwnAccessibilityService(context: Context): Boolean =
        enableAccessibilityService(context, ownComponent(context))

    fun ownComponent(context: Context): String =
        ComponentName(context, VideoBoostService::class.java).flattenToString()

    /**
     * ¿La escritura funciona AHORA MISMO?
     *
     * Reescribe `accessibility_enabled` con su valor actual: idempotente, sin
     * efecto observable, y ejercita exactamente el permiso que interesa. Se
     * prefiere a [Capabilities.canWriteSecureSettings] justo después de un
     * `pm grant`, porque no depende de la caché de permisos del propio proceso,
     * que puede seguir devolviendo el valor viejo un rato.
     */
    fun canWriteNow(context: Context): Boolean = try {
        val cr = context.contentResolver
        // Default 0 y no 1: si el setting no estuviera fijado, la sonda no debe
        // encender la accesibilidad por accidente.
        val value = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, value)
        true
    } catch (_: Exception) {
        false
    }
}
