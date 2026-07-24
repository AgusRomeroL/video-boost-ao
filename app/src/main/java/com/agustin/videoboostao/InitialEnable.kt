package com.agustin.videoboostao

import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    /**
     * Se auto-concede "Acceso de uso" por ADB. Con eso, el monitoreo de apps
     * sensibles lee el primer plano en local y deja de sondear por ADB, que es
     * la parte frágil del full-auto (ver [AdbManager.execRetrying]). Silencioso:
     * si falla, [BankWatchService] lo reintenta al arrancar.
     */
    suspend fun grantUsageAccessViaAdb(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (Capabilities.hasUsageAccess(context)) return@withContext true
                AdbManager.connect(context).getOrThrow()
                val out = AdbManager.execRetrying(
                    context,
                    "appops set ${context.packageName} GET_USAGE_STATS allow;" +
                        " appops get ${context.packageName} GET_USAGE_STATS",
                ) { it.contains("allow") }
                (out != null).also { Log.i(TAG, "Acceso de uso via ADB: $it") }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo conceder 'Acceso de uso' via ADB: ${e.message}")
                false
            } finally {
                AdbManager.disconnect()
            }
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
