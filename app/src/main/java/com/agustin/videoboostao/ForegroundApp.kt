package com.agustin.videoboostao

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Lectura del paquete en primer plano vía UsageStats ("Acceso de uso").
 *
 * Local, instantánea y sin estado: es la vía preferida frente a cualquier shell
 * privilegiado, que abre streams y se cuelga (ver [AdbManager.exec]). La usan
 * los dos vigilantes: [BankWatchService] (detectar que la app sensible se cerró)
 * y [CameraWatchService] (detectar que Pixel Camera se abrió).
 */
object ForegroundApp {

    /** Ventana de eventos que se mira hacia atrás. Diez segundos porque el
     *  sistema no reporta "quién está en primer plano" sino transiciones: una
     *  app quieta un rato no genera eventos nuevos y la última transición puede
     *  quedar lejos. */
    private const val WINDOW_MS = 10_000L

    /**
     * Último paquete que pasó a primer plano dentro de la ventana, o null si no
     * hubo eventos (o si falta el permiso, que devuelve una consulta vacía).
     */
    fun current(context: Context): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - WINDOW_MS, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                last = e.packageName
            }
        }
        return last
    }
}
