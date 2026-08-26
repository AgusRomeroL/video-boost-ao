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
 * Notificaciones del flujo de apps sensibles. El criterio de sonido: **suena lo
 * que pide una decisión** (la app sensible se cerró y hay una cuenta atrás
 * corriendo), **no suena lo informativo** (se apagó el servicio, ya volvió).
 * Cuatro canales:
 * - [CHANNEL_ALERT] (importancia default): la cuenta atrás al cerrarse la app y
 *   el recordatorio de reactivar a mano.
 * - [CHANNEL_REMINDER_SILENT] (baja, sin sonido): el aviso de que el servicio se
 *   apagó al abrirse la app sensible. Baja y no mínima porque, sin monitoreo, es
 *   el único aviso que recibe el usuario y tiene que conservar icono en la barra.
 * - [CHANNEL_PAUSED] (mínima, silenciosa): "en pausa" mientras la app sensible
 *   está abierta; es a la vez la notificación del foreground service
 *   [BankWatchService].
 * - [CHANNEL_WATCH] (mínima, silenciosa): el vigilante del modo bajo demanda
 *   ([CameraWatchService]). Canal propio y no [CHANNEL_PAUSED] porque son cosas
 *   distintas y el usuario tiene que poder silenciar u ocultar una sin la otra.
 * - [CHANNEL_ACTIVATED] (baja, silenciosa): la confirmación opcional de que
 *   Video Boost quedó activado.
 * - [CHANNEL_ADB_PAIRING] (alta): el emparejamiento ADB.
 *
 * Los canales son inmutables una vez creados, así que cambiar la importancia de
 * un aviso se hace moviéndolo a otro canal, nunca editando el suyo. Eso es lo
 * que implementa el maestro de silencio ([Prefs.silenceAllNotifs]): no toca
 * ningún canal, elige otro al postear.
 *
 * Los avisos OPCIONALES consultan su preferencia y no se postean si está
 * apagada. Los de foreground service (pausa, cuenta atrás, vigilante) los exige
 * Android mientras el servicio corre: se pueden silenciar, no suprimir; para
 * eso la UI ofrece [channelSettingsIntent].
 */
object Notifications {

    const val CHANNEL_ALERT = "reenable_reminder"
    const val CHANNEL_REMINDER_SILENT = "reenable_silent"
    const val CHANNEL_PAUSED = "bank_paused"
    const val CHANNEL_ADB_PAIRING = "adb_pairing"
    const val CHANNEL_WATCH = "ondemand_watch"
    const val CHANNEL_ACTIVATED = "boost_activated"

    private const val NOTIF_ID_ALERT = 1001
    const val NOTIF_ID_PAUSED = 1002
    const val NOTIF_ID_ADB_PAIRING = 1004
    private const val NOTIF_ID_ADB_PAIRING_DONE = 1005
    const val NOTIF_ID_WATCH = 1007
    private const val NOTIF_ID_ACTIVATED = 1008

    /**
     * Ciclo "la app sensible se cerró": cuenta atrás -> "Video Boost volvió".
     * Un solo id para las dos cosas a propósito: así el aviso final es una
     * ACTUALIZACIÓN de la cuenta atrás y, con `onlyAlertOnce`, no vuelve a
     * sonar. Y es un id que [VideoBoostService.onServiceConnected] no cancela
     * ([cancel] / [cancelPaused]), para que el aviso sobreviva al rebind del
     * servicio de accesibilidad. (Reemplaza al viejo 1003.)
     */
    const val NOTIF_ID_REENABLE = 1006

    /**
     * Crea los canales que falten. Pública porque la UI necesita que el canal
     * exista ANTES de abrir sus ajustes: [channelSettingsIntent] contra un canal
     * inexistente aterriza en una pantalla vacía.
     */
    fun ensureChannels(context: Context) {
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
        if (nm.getNotificationChannel(CHANNEL_REMINDER_SILENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDER_SILENT,
                    context.getString(R.string.notif_channel_silent_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_silent_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
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
        if (nm.getNotificationChannel(CHANNEL_WATCH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCH,
                    context.getString(R.string.notif_channel_watch_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.notif_channel_watch_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ACTIVATED) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACTIVATED,
                    context.getString(R.string.notif_channel_activated_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_activated_desc)
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
        if (!Prefs.notifClosed(context)) return
        ensureChannels(context)
        val text = if (!appLabel.isNullOrBlank()) {
            context.getString(R.string.notif_closed_text_named, appLabel)
        } else {
            context.getString(R.string.notif_closed_text)
        }
        notify(context, NOTIF_ID_ALERT, alert(context, R.string.notif_closed_title, text))
    }

    /**
     * Cuenta atrás de gracia antes de reactivar, con acciones para estirarla o
     * cortarla. Es la notificación del foreground service durante esa fase (por
     * eso `ongoing`: no se puede descartar y sus acciones siguen ahí), y no la
     * postea directo cuando arranca: la devuelve para `startForeground`.
     *
     * El tiempo lo dibuja el sistema: [setWhen] con el instante límite en reloj
     * de pared + cronómetro en cuenta atrás. Así el número baja solo, sin
     * re-postear cada segundo — re-postear sería la forma segura de que el
     * usuario silencie la app. Los minutos van también en el texto porque el
     * cronómetro no se dibuja en todos los templates.
     */
    fun buildReenableCountdown(
        context: Context,
        appLabel: String?,
        deadlineAt: Long,
        delayMin: Int,
        canSnooze: Boolean,
    ): Notification {
        ensureChannels(context)
        val text = if (!appLabel.isNullOrBlank()) {
            context.getString(R.string.notif_countdown_text_named, appLabel, delayMin)
        } else {
            context.getString(R.string.notif_countdown_text, delayMin)
        }
        val builder = NotificationCompat.Builder(context, quiet(context, CHANNEL_ALERT))
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(R.string.notif_countdown_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(deadlineAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(openAppIntent(context))
        if (canSnooze) {
            builder.addAction(
                bankAction(
                    context,
                    BankWatchService.ACTION_SNOOZE,
                    REQ_SNOOZE,
                    context.getString(R.string.notif_countdown_action_wait, delayMin),
                ),
            )
        }
        builder.addAction(
            bankAction(
                context,
                BankWatchService.ACTION_NOW,
                REQ_NOW,
                context.getString(R.string.notif_countdown_action_now),
            ),
        )
        return builder.build()
    }

    fun showReenableCountdown(
        context: Context,
        appLabel: String?,
        deadlineAt: Long,
        delayMin: Int,
        canSnooze: Boolean,
    ) {
        notify(
            context,
            NOTIF_ID_REENABLE,
            buildReenableCountdown(context, appLabel, deadlineAt, delayMin, canSnooze),
        )
    }

    /**
     * Aviso de que el servicio volvió, en el mismo id que la cuenta atrás y con
     * `onlyAlertOnce`: si venía de una cuenta atrás es una actualización y por
     * tanto silenciosa (un solo sonido por ciclo); si no la hubo (retardo 0) es
     * el primer post y suena, que es lo correcto porque ahí este ES el aviso del
     * cierre.
     */
    fun showReenabled(context: Context) {
        if (!Prefs.notifReenabled(context)) {
            // Sin aviso final, la cuenta atrás (mismo id) se quedaría colgada
            // contando hacia atrás para siempre: hay que retirarla.
            cancelReenable(context)
            return
        }
        ensureChannels(context)
        val text = context.getString(R.string.notif_reenabled_text)
        notify(
            context,
            NOTIF_ID_REENABLE,
            alert(context, R.string.notif_reenabled_title, text, onlyAlertOnce = true),
        )
    }

    /**
     * Aviso al abrir la app sensible (fallback sin capacidad de monitoreo), para
     * no dejar al usuario sin recordatorio de re-activar. Silencioso: es
     * informativo y llega en el peor momento posible, justo cuando el usuario
     * está entrando al banco.
     */
    fun showReenableReminder(context: Context, appLabel: String?) {
        if (!Prefs.notifServiceOff(context)) return
        ensureChannels(context)
        val text = if (!appLabel.isNullOrBlank()) {
            context.getString(R.string.notif_text_named, appLabel)
        } else {
            context.getString(R.string.notif_text)
        }
        notify(
            context,
            NOTIF_ID_ALERT,
            alert(context, R.string.notif_title, text, channelId = CHANNEL_REMINDER_SILENT),
        )
    }

    private fun alert(
        context: Context,
        titleRes: Int,
        text: String,
        channelId: String = CHANNEL_ALERT,
        onlyAlertOnce: Boolean = false,
    ): Notification {
        val channel = quiet(context, channelId)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            // Paridad con el canal para quien tenga los canales ya creados.
            .setSilent(channel != CHANNEL_ALERT)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    /**
     * Canal efectivo con el maestro de silencio puesto: lo que sonaría pasa al
     * canal silencioso. Se elige canal en vez de editar el existente porque los
     * canales son inmutables; y no se toca lo que ya es silencioso.
     */
    private fun quiet(context: Context, channelId: String): String =
        if (channelId == CHANNEL_ALERT && Prefs.silenceAllNotifs(context)) {
            CHANNEL_REMINDER_SILENT
        } else {
            channelId
        }

    /** Acción de la cuenta atrás. Va al propio [BankWatchService], que ya está
     *  en primer plano: `getForegroundService` es el mismo patrón que usa el
     *  emparejamiento ADB ([showAdbPairingCodeInput]). No hace falta un
     *  BroadcastReceiver y el servicio sigue sin exportarse. */
    private fun bankAction(
        context: Context,
        action: String,
        requestCode: Int,
        label: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, BankWatchService::class.java).setAction(action)
        val pending = PendingIntent.getForegroundService(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stat_videoboost,
            label,
            pending,
        ).build()
    }

    // --- Emparejamiento ADB (flujo por notificación, estilo Shizuku) ---

    /** requestCodes de los PendingIntent (distintos o se sobreescriben). */
    private const val REQ_SNOOZE = 1
    private const val REQ_NOW = 2

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

    // --- Modo bajo demanda ---

    /**
     * Notificación del foreground service que vigila la apertura de la cámara
     * ([CameraWatchService]). Importancia mínima: sin sonido y sin icono en la
     * barra de estado, que es lo más discreto que Android permite para un
     * servicio en primer plano (ocultarla del todo no se puede).
     */
    fun buildWatchOngoing(context: Context): Notification {
        ensureChannels(context)
        val text = context.getString(R.string.notif_watch_text)
        return NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_videoboost)
            .setContentTitle(context.getString(R.string.notif_watch_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    /**
     * Confirmación opcional de que Video Boost quedó activado en esta sesión de
     * cámara ([Prefs.notifActivated], apagada por defecto). `autoCancel` + canal
     * silencioso: se descarta al tocarla y nunca suena, porque llega justo
     * cuando el usuario está por grabar.
     */
    fun showActivated(context: Context) {
        if (!Prefs.notifActivated(context)) return
        ensureChannels(context)
        val text = context.getString(R.string.notif_activated_text)
        notify(
            context,
            NOTIF_ID_ACTIVATED,
            alert(
                context,
                R.string.notif_activated_title,
                text,
                channelId = CHANNEL_ACTIVATED,
                onlyAlertOnce = true,
            ),
        )
    }

    fun cancelActivated(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_ACTIVATED)
    }

    /**
     * Ajustes del sistema para un canal concreto (sonido, vibración, burbuja,
     * importancia). Es el único sitio donde se pueden cambiar esas cosas: los
     * canales son inmutables desde código una vez creados, así que la app ofrece
     * el atajo en vez de fingir que las controla.
     */
    fun channelSettingsIntent(context: Context, channelId: String): Intent {
        ensureChannels(context)
        return Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Ajustes de notificaciones de la app entera (todos los canales). */
    fun appSettingsIntent(context: Context): Intent {
        ensureChannels(context)
        return Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    /** Cancela la cuenta atrás / el aviso de reactivado (id compartido). */
    fun cancelReenable(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_REENABLE)
    }
}
