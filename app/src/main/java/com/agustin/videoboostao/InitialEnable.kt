package com.agustin.videoboostao

import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Activación inicial del servicio de accesibilidad vía ADB inalámbrico, para
 * saltarse el muro de "Ajustes restringidos" del sideload en el onboarding.
 * Conexión corta: conectar -> habilitar -> desconectar.
 *
 * Requiere que el dispositivo ya esté emparejado ([AdbManager.isPaired]) y con
 * "Wireless debugging" activo. Devuelve Result para que la UI muestre el error.
 */
object InitialEnable {

    private const val TAG = "VideoBoostAO"

    /** Intentos de poner el appop de "Acceso de uso" (ver [setUsageAccess]). */
    private const val USAGE_ATTEMPTS = 6
    private const val USAGE_RETRY_MS = 500L

    /**
     * Se auto-concede "Acceso de uso" por ADB. Con eso, el monitoreo de apps
     * sensibles lee el primer plano en local y deja de sondear por ADB, que es
     * la parte frágil del full-auto (ver [AdbManager.execRetrying]). Silencioso:
     * si falla, [BankWatchService] lo reintenta al arrancar.
     */
    suspend fun grantUsageAccessViaAdb(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (Capabilities.hasUsageAccess(context)) {
                Log.i(TAG, "Acceso de uso: ya concedido; nada que hacer")
                return@withContext true
            }
            setUsageAccess(context, allow = true).also {
                if (it) Prefs.setUsageAccessSelfGranted(context, true)
                Log.i(TAG, "Acceso de uso concedido via ADB: $it")
            }
        }

    /**
     * Deshace [grantUsageAccessViaAdb] al apagar el full-auto. Solo revoca lo
     * que concedimos nosotros: si el usuario dio el permiso a mano en Ajustes,
     * se queda como está.
     */
    suspend fun revokeUsageAccessViaAdb(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!Prefs.usageAccessSelfGranted(context)) {
                Log.i(TAG, "Acceso de uso: no lo concedimos nosotros; no se toca")
                return@withContext false
            }
            setUsageAccess(context, allow = false).also {
                if (it) Prefs.setUsageAccessSelfGranted(context, false)
                Log.i(TAG, "Acceso de uso revocado via ADB: $it")
            }
        }

    /**
     * Pone el appop y confirma leyendo el estado real con [AppOpsManager], no
     * la salida del comando: abrir un stream ADB falla a menudo justo al leer,
     * aunque el comando ya se haya ejecutado (ver [AdbManager.exec]). Fiarse de
     * la salida daba falsos negativos, y aquí eso dejaba la marca de
     * [Prefs.usageAccessSelfGranted] descuadrada respecto al permiso real.
     */
    private suspend fun setUsageAccess(context: Context, allow: Boolean): Boolean {
        val value = if (allow) "allow" else "default"
        val cmd = "appops set ${context.packageName} GET_USAGE_STATS $value"
        try {
            repeat(USAGE_ATTEMPTS) { i ->
                if (Capabilities.hasUsageAccess(context) == allow) return true
                if (!AdbManager.isConnected()) AdbManager.connect(context)
                AdbManager.exec(cmd, AdbManager.FIRE_TIMEOUT_MS, streamAttempts = 1)
                delay(300)
                if (Capabilities.hasUsageAccess(context) == allow) return true
                AdbManager.disconnect()
                if (i < USAGE_ATTEMPTS - 1) delay(USAGE_RETRY_MS)
            }
        } finally {
            AdbManager.disconnect()
        }
        return Capabilities.hasUsageAccess(context) == allow
    }

    suspend fun enableAccessibilityViaAdb(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                AdbManager.connect(context).getOrThrow()
                val component = ComponentName(context, VideoBoostService::class.java).flattenToString()
                val ok = AdbManager.shell(context).enableAccessibilityService(component)
                if (!ok) error("No se pudo habilitar el servicio de accesibilidad")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Activación inicial vía ADB falló", e)
                Result.failure(e)
            } finally {
                AdbManager.disconnect()
            }
        }
}
