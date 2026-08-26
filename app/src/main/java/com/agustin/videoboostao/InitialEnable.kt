package com.agustin.videoboostao

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Operaciones puntuales por ADB inalámbrico: la activación inicial del servicio
 * de accesibilidad (para saltarse el muro de "Ajustes restringidos" del sideload
 * en el onboarding) y los dos permisos que el full-auto se auto-concede.
 *
 * Todas abren la conexión, hacen lo suyo y la cierran ([withConnection]). UNA
 * conexión por operación, nunca una por intento: cada connect/disconnect hace
 * re-sonar la notificación del sistema de "Depuración inalámbrica", que es de
 * Ajustes y no se puede silenciar; lo único que está en nuestra mano es conectar
 * menos. Reintentar el comando sobre la MISMA conexión es barato y silencioso:
 * el fallo de libadb es por stream, no por conexión (ver [AdbManager.exec]).
 *
 * Requiere que el dispositivo ya esté emparejado ([AdbManager.isPaired]) y con
 * "Wireless debugging" activo.
 */
object InitialEnable {

    private const val TAG = "VideoBoostAO"

    /** Intentos de poner el appop de "Acceso de uso" (ver [setUsageAccess]). */
    private const val USAGE_ATTEMPTS = 4
    private const val SECURE_ATTEMPTS = 4
    private const val RETRY_MS = 500L

    /**
     * Concede en UNA sola conexión los dos permisos que quiere el full-auto:
     * "Acceso de uso" (sondeo del primer plano en local) y
     * `WRITE_SECURE_SETTINGS` (reactivación en local, ver [SecureSettings]).
     * Con los dos puestos, una sesión de app sensible no abre ADB ni una vez.
     */
    suspend fun grantFullAutoPermissionsViaAdb(context: Context): Boolean =
        withConnection(context) {
            // `and` y no `&&`: que fallar uno no se salte el otro.
            grantUsageAccess(context) and grantSecureSettings(context)
        } ?: false

    /**
     * Se auto-concede "Acceso de uso". Con eso, el monitoreo de apps sensibles
     * lee el primer plano en local y deja de sondear por ADB, que es la parte
     * frágil del full-auto (ver [AdbManager.execRetrying]). Silencioso: si
     * falla, [BankWatchService] lo reintenta al arrancar.
     */
    suspend fun grantUsageAccessViaAdb(context: Context): Boolean =
        withConnection(context) { grantUsageAccess(context) } ?: false

    /**
     * Deshace [grantUsageAccessViaAdb] al apagar el full-auto. Solo revoca lo
     * que concedimos nosotros: si el usuario dio el permiso a mano en Ajustes,
     * se queda como está.
     *
     * `WRITE_SECURE_SETTINGS` NO se revoca acá a propósito (ver
     * [PrivilegedShell.revokeSecureSettings]).
     */
    suspend fun revokeUsageAccessViaAdb(context: Context): Boolean {
        if (!Prefs.usageAccessSelfGranted(context)) {
            Log.i(TAG, "Acceso de uso: no lo concedimos nosotros; no se toca")
            return false
        }
        val ok = withConnection(context) { setUsageAccess(context, allow = false) } ?: false
        if (ok) Prefs.setUsageAccessSelfGranted(context, false)
        Log.i(TAG, "Acceso de uso revocado via ADB: $ok")
        return ok
    }

    suspend fun enableAccessibilityViaAdb(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Con el permiso local puesto no hace falta ADB ni, por tanto,
            // "Wireless debugging" encendido: tras un reinicio esto sigue
            // funcionando sin volver a Opciones de desarrollador.
            if (SecureSettings.enableOwnAccessibilityService(context)) {
                Log.i(TAG, "Servicio de accesibilidad activado en local")
                return@withContext Result.success(Unit)
            }
            try {
                AdbManager.connect(context).getOrThrow()
                val ok = AdbManager.shell(context)
                    .enableAccessibilityService(SecureSettings.ownComponent(context))
                if (!ok) error("No se pudo habilitar el servicio de accesibilidad")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Activación inicial vía ADB falló", e)
                Result.failure(e)
            } finally {
                AdbManager.disconnect()
            }
        }

    // --- interno ---

    /** Abre la conexión una vez, corre [block] y cierra una vez. Devuelve null
     *  si no se pudo conectar. */
    private suspend fun <T> withConnection(context: Context, block: suspend () -> T): T? =
        withContext(Dispatchers.IO) {
            try {
                if (AdbManager.connect(context).isFailure) return@withContext null
                block()
            } finally {
                AdbManager.disconnect()
            }
        }

    private suspend fun grantUsageAccess(context: Context): Boolean {
        if (Capabilities.hasUsageAccess(context)) {
            Log.i(TAG, "Acceso de uso: ya concedido; nada que hacer")
            return true
        }
        return setUsageAccess(context, allow = true).also {
            if (it) Prefs.setUsageAccessSelfGranted(context, true)
            Log.i(TAG, "Acceso de uso concedido via ADB: $it")
        }
    }

    private suspend fun grantSecureSettings(context: Context): Boolean {
        if (SecureSettings.canWriteNow(context)) {
            Log.i(TAG, "Escritura local: ya concedida; nada que hacer")
            return true
        }
        val cmd = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        return fireAndVerify(context, SECURE_ATTEMPTS, cmd) { SecureSettings.canWriteNow(context) }.also {
            if (it) Prefs.setSecureSettingsSelfGranted(context, true)
            Log.i(TAG, "WRITE_SECURE_SETTINGS concedido via ADB: $it")
        }
    }

    private suspend fun setUsageAccess(context: Context, allow: Boolean): Boolean {
        val value = if (allow) "allow" else "default"
        val cmd = "appops set ${context.packageName} GET_USAGE_STATS $value"
        return fireAndVerify(context, USAGE_ATTEMPTS, cmd) {
            Capabilities.hasUsageAccess(context) == allow
        }
    }

    /**
     * Corre [cmd] y confirma con [done], que lee el estado REAL en local.
     * Verificar por efecto y no por la salida es lo que hace esto fiable: abrir
     * un stream ADB falla a menudo justo al leer, aunque el comando ya se haya
     * ejecutado (ver [AdbManager.exec]), y fiarse de la salida daba falsos
     * negativos.
     *
     * No se fuerza un `disconnect()` entre intentos (la conexión suele seguir
     * sana y reconectar solo suma ruido de notificación del sistema), pero sí se
     * reconecta si [AdbManager.exec] la dio por perdida al agotar los reintentos
     * de stream: sin esto, un solo stream fallido dejaba muertos todos los
     * comandos siguientes de la misma tanda, que es lo que hacía fallar el
     * auto-permiso de escritura local justo después de conceder el "Acceso de
     * uso".
     *
     * Con el timeout largo por defecto: `pm grant` tarda bastante más que un
     * `settings put`, y con el corto se abandonaba el stream antes de tiempo.
     */
    private suspend fun fireAndVerify(
        context: Context,
        attempts: Int,
        cmd: String,
        done: () -> Boolean,
    ): Boolean {
        repeat(attempts) { i ->
            if (done()) return true
            if (!AdbManager.isConnected() && AdbManager.connect(context).isFailure) return done()
            AdbManager.exec(cmd)
            // Dar tiempo a que el cambio se publique antes de leer.
            delay(300)
            if (done()) return true
            if (i < attempts - 1) delay(RETRY_MS)
        }
        return done()
    }
}
