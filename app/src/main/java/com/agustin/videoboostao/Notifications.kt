package com.agustin.videoboostao

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notificación de recordatorio: cuando el servicio se apaga por una app
 * sensible, avisa al usuario con un toque para reactivar Video Boost cuando
 * termine. Se cancela sola al reactivarse el servicio ([VideoBoostService.onServiceConnected]).
 */
object Notifications {

    private const val CHANNEL_ID = "reenable_reminder"
    private const val NOTIF_ID = 1001

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_desc) }
            nm.createNotificationChannel(ch)
        }
    }

    /** appLabel: nombre de la app sensible que disparó el apagado (o null). */
    fun showReenableReminder(context: Context, appLabel: String?) {
        ensureChannel(context)
        val tapIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (!appLabel.isNullOrBlank()) {
            context.getString(R.string.notif_text_named, appLabel)
        } else {
            context.getString(R.string.notif_text)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // Sin permiso POST_NOTIFICATIONS (Android 13+): el auto-apagado
            // igual funcionó, solo no se muestra el aviso.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}
