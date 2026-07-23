package com.agustin.videoboostao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notificaciones del flujo de apps sensibles. Dos canales:
 * - [CHANNEL_ALERT] (importancia default): avisos "normales" con sonido —
 *   recordatorio al cerrar la app / servicio reactivado / (fallback) al abrir.
 * - [CHANNEL_PAUSED] (importancia mínima, silenciosa): "en pausa" mientras la
 *   app sensible está abierta; es a la vez la notificación del foreground
 *   service [BankWatchService].
 */
object Notifications {

    private const val CHANNEL_ALERT = "reenable_reminder"
    private const val CHANNEL_PAUSED = "bank_paused"

    private const val NOTIF_ID_ALERT = 1001
    const val NOTIF_ID_PAUSED = 1002
    private const val NOTIF_ID_REENABLED = 1003

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.notif_channel_desc) },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_PAUSED) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PAUSED,
                    context.getString(R.string.notif_channel_paused_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.notif_channel_paused_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val tapIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Notificación silenciosa y persistente para el [BankWatchService]. No la
     * postea directo: la devuelve para `startForeground`.
     */
    fun buildPausedOngoing(context: Context, appLabel: String?): Notification {
        ensureChannels(context)
        val text = if (!appLabel.isNullOrBlank()) {
            context.getString(R.string.notif_paused_text_named, appLabel)
        } else {
            context.getString(R.string.notif_paused_text)
        }
        return NotificationCompat.Builder(context, CHANNEL_PAUSED)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(R.string.notif_paused_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    /** Aviso normal al detectar que la app sensible se cerró (re-activar a mano). */
    fun showClosedReminder(context: Context, appLabel: String?) {
        ensureChannels(context)
        val text = if (!appLabel.isNullOrBlank()) {
            context.getString(R.string.notif_closed_text_named, appLabel)
        } else {
            context.getString(R.string.notif_closed_text)
        }
        notify(context, NOTIF_ID_ALERT, alert(context, R.string.notif_closed_title, text))
    }

    /** Aviso normal cuando el full-auto (Shizuku) reactivó el servicio solo. */
    fun showReenabled(context: Context) {
        ensureChannels(context)
        val text = context.getString(R.string.notif_reenabled_text)
        notify(context, NOTIF_ID_REENABLED, alert(context, R.string.notif_reenabled_title, text))
    }

    /**
     * Fallback (sin capacidad de monitoreo): aviso normal al abrir la app
     * sensible, para no dejar al usuario sin recordatorio de re-activar.
     */
    fun showReenableReminder(context: Context, appLabel: String?) {
        ensureChannels(context)
        val text = if (!appLabel.isNullOrBlank()) {
            context.getString(R.string.notif_text_named, appLabel)
        } else {
            context.getString(R.string.notif_text)
        }
        notify(context, NOTIF_ID_ALERT, alert(context, R.string.notif_title, text))
    }

    private fun alert(context: Context, titleRes: Int, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()

    private fun notify(context: Context, id: Int, notif: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // Sin permiso POST_NOTIFICATIONS (Android 13+): la lógica igual corrió.
        }
    }

    /** Cancela el recordatorio (al re-activarse el servicio). */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_ALERT)
    }

    fun cancelPaused(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_PAUSED)
    }
}
