package com.agustin.videoboostao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Vuelve a levantar el vigilante del modo bajo demanda cuando el proceso muere
 * por causas ajenas: un reinicio del teléfono o una actualización de la propia
 * app (que mata el proceso y no lo reinicia).
 *
 * Solo hace falta en ese modo. En "siempre activo" es el sistema el que revive
 * el servicio de accesibilidad al arrancar, y no hay nada que reprogramar.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit
            else -> return
        }
        if (!Capabilities.onDemandMode(context) || !Prefs.featureEnabled(context)) return
        try {
            CameraWatchService.start(context)
            Log.i(TAG, "Boot: vigilante del modo bajo demanda relanzado")
        } catch (t: Throwable) {
            // Android 15+ restringe qué tipos de foreground service pueden
            // arrancar desde BOOT_COMPLETED. `specialUse` no está en la lista
            // vetada, pero si algún día lo estuviera, el modo se recupera solo
            // en cuanto el usuario abra la app (MainActivity llama a
            // CameraWatchService.sync): mejor eso que un crash en el arranque.
            Log.w(TAG, "Boot: no se pudo arrancar el vigilante", t)
        }
    }

    private companion object {
        const val TAG = "VideoBoostAO"
    }
}
