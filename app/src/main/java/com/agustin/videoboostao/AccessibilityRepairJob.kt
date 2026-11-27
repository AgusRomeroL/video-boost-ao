package com.agustin.videoboostao

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Rescate del modo **siempre activo** cuando el servicio muere y no queda nadie
 * en el proceso para notarlo.
 *
 * En bajo demanda el vigilante ([CameraWatchService]) sigue vivo con el servicio
 * apagado y se encarga él. En siempre activo no: el servicio de accesibilidad
 * ES lo único que corre, así que cuando un `UiAutomation` ajeno lo tumba (ver
 * [AccessibilityGuard]) el proceso se queda sin nada y la app no vuelve hasta
 * que el usuario la abra. Este job cubre ese hueco sin sondear nada en el caso
 * normal: se programa **solo** cuando el servicio se destruye estando el modo
 * siempre activo, que es exactamente la señal de que algo pasó.
 *
 * El job no reactiva a ciegas: [AccessibilityGuard.repairIfNeeded] solo actúa
 * ante la firma de un borrado ajeno, así que un apagado hecho por el usuario
 * desde Ajustes programa este job, lo encuentra todo en orden y no hace nada.
 */
class AccessibilityRepairJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val repaired = AccessibilityGuard.repairIfNeeded(this)
        if (!repaired) {
            // O no hacía falta, o el permiso ya no está: en cualquier caso no
            // hay nada más que intentar y no se reprograma.
            jobFinished(params, false)
            return false
        }
        // Escribir el setting no garantiza el bind: si la herramienta ajena
        // sigue viva, el sistema acepta la escritura y no enlaza nada. Por eso
        // el resultado se mira pasado el mismo margen que usa el vigilante.
        Handler(Looper.getMainLooper()).postDelayed({
            val bound = AccessibilityGuard.isRunning()
            AccessibilityGuard.reportBindOutcome(this, bound)
            // Mientras el bloqueo dure, volver a intentarlo más tarde: es lo
            // que hace que la app reviva sola aunque el usuario no la abra.
            if (!bound) schedule(this)
            jobFinished(params, false)
        }, AccessibilityGuard.BIND_GRACE_MS)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "VideoBoostAO"
        private const val JOB_ID = 2001

        /** Margen antes del intento. No corre pegado a la muerte del servicio:
         *  una actualización de la app también lo destruye y ahí el sistema lo
         *  revive solo en cuanto el paquete vuelve. */
        private const val DELAY_MS = 60_000L

        /**
         * Programa el rescate si el modo siempre activo está en vigor. Es
         * idempotente: vuelve a programar el mismo [JOB_ID], así que varios
         * disparos seguidos no acumulan trabajo.
         */
        fun schedule(context: Context) {
            if (Capabilities.onDemandMode(context)) return
            if (!Prefs.featureEnabled(context)) return
            if (!Prefs.serviceEverEnabled(context)) return
            if (!Capabilities.canWriteSecureSettings(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, AccessibilityRepairJob::class.java),
            )
                .setMinimumLatency(DELAY_MS)
                .setOverrideDeadline(DELAY_MS * 15)
                .build()
            try {
                scheduler.schedule(job)
                Log.d(TAG, "Guard: rescate programado por si el servicio no vuelve")
            } catch (t: Throwable) {
                // Sin el job la app sigue reparándose al abrirla o al arrancar
                // el teléfono: es un refuerzo, no un requisito.
                Log.w(TAG, "Guard: no se pudo programar el rescate", t)
            }
        }
    }
}
