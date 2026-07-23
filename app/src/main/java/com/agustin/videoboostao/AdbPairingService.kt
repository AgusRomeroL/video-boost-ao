package com.agustin.videoboostao

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Emparejamiento ADB "estilo Shizuku": el usuario nunca sale de la pantalla de
 * "Depuración inalámbrica". Este foreground service escucha por mDNS y, cuando
 * detecta que se abrió el diálogo "Vincular con código" (aparece el servicio
 * `_adb-tls-pairing._tcp`), publica una notificación con un campo de respuesta
 * inline (RemoteInput) para teclear el código de 6 dígitos ahí mismo. Al
 * responder, empareja con el host/puerto descubiertos + el código.
 *
 * Así se esquiva el problema de que salir de la pantalla de pairing tira el
 * servicio (el puerto cambia): el descubrimiento corre en segundo plano y el
 * código se introduce sin cambiar de pantalla.
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "VideoBoostAO"
        const val ACTION_START = "com.agustin.videoboostao.adb.START"
        const val ACTION_SUBMIT = "com.agustin.videoboostao.adb.SUBMIT"
        const val ACTION_CANCEL = "com.agustin.videoboostao.adb.CANCEL"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val KEY_CODE = "adb_pairing_code"

        /** Tope de vida por si el usuario nunca abre el diálogo de pairing. */
        private const val MAX_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdbPairingService::class.java).setAction(ACTION_START),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: AdbManager.PairingDiscovery? = null

    @Volatile
    private var endpoint: AdbManager.Endpoint? = null
    private var shownPort = -1
    private var pairing = false

    private val stopTimeout = Runnable {
        Log.w(TAG, "AdbPairing: tope de tiempo sin emparejar; termino")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SUBMIT -> handleSubmit(intent)
            ACTION_CANCEL -> stopSelf()
            else -> startSearching()
        }
        return START_NOT_STICKY
    }

    private fun startSearching() {
        startForeground(
            Notifications.NOTIF_ID_ADB_PAIRING,
            Notifications.buildAdbPairingSearching(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        handler.postDelayed(stopTimeout, MAX_MS)
        discovery?.stop()
        discovery = AdbManager.startPairingDiscovery(this) { ep ->
            endpoint = ep
            handler.post {
                // Mostrar el campo de código solo la primera vez por endpoint,
                // para no borrar lo que el usuario esté tecleando en respuestas
                // repetidas del descubrimiento.
                if (!pairing && ep.port != shownPort) {
                    shownPort = ep.port
                    Notifications.showAdbPairingCodeInput(this, ep, error = false)
                }
            }
        }
    }

    private fun handleSubmit(intent: Intent) {
        // La entrega llegó por startForegroundService (PendingIntent del
        // RemoteInput): hay que re-afirmar foreground dentro de 5 s.
        startForeground(
            Notifications.NOTIF_ID_ADB_PAIRING,
            Notifications.buildAdbPairingProgress(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
        val host = intent.getStringExtra(EXTRA_HOST) ?: endpoint?.host
        val port = intent.getIntExtra(EXTRA_PORT, -1).takeIf { it > 0 } ?: endpoint?.port ?: -1

        if (code.length < 6 || host == null || port <= 0) {
            val ep = endpoint
            if (ep != null) Notifications.showAdbPairingCodeInput(this, ep, error = true)
            return
        }

        pairing = true
        scope.launch {
            val result = AdbManager.pair(this@AdbPairingService, host, port, code)
            withContext(Dispatchers.Main) {
                pairing = false
                if (result.isSuccess) {
                    // Emparejado: dejar el full-auto ADB listo (el usuario abrió
                    // este flujo justamente para eso).
                    Prefs.setFullAutoAdb(this@AdbPairingService, true)
                    Notifications.showAdbPairingSuccess(this@AdbPairingService)
                    cleanup()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    // Código errado o el diálogo se cerró: permitir reintentar.
                    shownPort = -1
                    val ep = endpoint
                    if (ep != null) {
                        Notifications.showAdbPairingCodeInput(this@AdbPairingService, ep, error = true)
                    } else {
                        Notifications.showAdbPairingSearchingUpdate(this@AdbPairingService)
                    }
                }
            }
        }
    }

    private fun cleanup() {
        handler.removeCallbacks(stopTimeout)
        discovery?.stop()
        discovery = null
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }
}
