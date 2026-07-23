package com.agustin.videoboostao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

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
    private const val CHANNEL_ADB_PAIRING = "adb_pairing"

    private const val NOTIF_ID_ALERT = 1001
    const val NOTIF_ID_PAUSED = 1002
    private const val NOTIF_ID_REENABLED = 1003
    const val NOTIF_ID_ADB_PAIRING = 1004
    private const val NOTIF_ID_ADB_PAIRING_DONE = 1005

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
        if (nm.getNotificationChannel(CHANNEL_ADB_PAIRING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ADB_PAIRING,
                    context.getString(R.string.notif_channel_adb_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.notif_channel_adb_desc) },
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

    // --- Emparejamiento ADB (flujo por notificación, estilo Shizuku) ---

    private fun adbCancelAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, AdbPairingService::class.java)
            .setAction(AdbPairingService.ACTION_CANCEL)
        val pending = PendingIntent.getService(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stat_videoboost,
            context.getString(R.string.adb_notif_cancel),
            pending,
        ).build()
    }

    /** Notificación persistente del FGS mientras busca el diálogo de pairing. */
    fun buildAdbPairingSearching(context: Context): Notification {
        ensureChannels(context)
        val text = context.getString(R.string.adb_notif_searching_text)
        return NotificationCompat.Builder(context, CHANNEL_ADB_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(R.string.adb_notif_searching_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(adbCancelAction(context))
            .build()
    }

    fun showAdbPairingSearchingUpdate(context: Context) {
        notify(context, NOTIF_ID_ADB_PAIRING, buildAdbPairingSearching(context))
    }

    /**
     * Notificación con campo de respuesta inline (RemoteInput) para teclear el
     * código de 6 dígitos sin salir de la pantalla de Depuración inalámbrica.
     * El host/puerto descubiertos viajan como extras del PendingIntent (mutable).
     */
    fun showAdbPairingCodeInput(context: Context, endpoint: AdbManager.Endpoint, error: Boolean) {
        ensureChannels(context)
        val remoteInput = RemoteInput.Builder(AdbPairingService.KEY_CODE)
            .setLabel(context.getString(R.string.adb_notif_code_hint))
            .build()
        val submitIntent = Intent(context, AdbPairingService::class.java)
            .setAction(AdbPairingService.ACTION_SUBMIT)
            .putExtra(AdbPairingService.EXTRA_HOST, endpoint.host)
            .putExtra(AdbPairingService.EXTRA_PORT, endpoint.port)
        val submitPending = PendingIntent.getForegroundService(
            context, endpoint.port, submitIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_videoboost,
            context.getString(R.string.adb_notif_action),
            submitPending,
        ).addRemoteInput(remoteInput).build()

        val title = context.getString(
            if (error) R.string.adb_notif_title_retry else R.string.adb_notif_title,
        )
        val text = context.getString(R.string.adb_notif_text)
        val notif = NotificationCompat.Builder(context, CHANNEL_ADB_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(replyAction)
            .addAction(adbCancelAction(context))
            .build()
        notify(context, NOTIF_ID_ADB_PAIRING, notif)
    }

    fun buildAdbPairingProgress(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_ADB_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(R.string.adb_notif_pairing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    fun showAdbPairingSuccess(context: Context) {
        ensureChannels(context)
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_ADB_PAIRING)
        val text = context.getString(R.string.adb_notif_done_text)
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(R.string.adb_notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, NOTIF_ID_ADB_PAIRING_DONE, notif)
    }

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
