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
