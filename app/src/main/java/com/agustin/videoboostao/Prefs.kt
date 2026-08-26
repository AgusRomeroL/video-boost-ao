package com.agustin.videoboostao

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencias de la app. `featureEnabled` es el interruptor maestro: permite
 * pausar la automatización sin deshabilitar el servicio de accesibilidad
 * (evita re-pasar por el flujo de "ajustes restringidos" al reactivar).
 */
object Prefs {

    private const val FILE = "vb_prefs"
    private const val KEY_FEATURE_ENABLED = "feature_enabled"
    private const val KEY_AUTO_DISABLE_BANKS = "auto_disable_banks"
    private const val KEY_DISABLED_BY_BANK = "disabled_by_bank"
    private const val KEY_SENSITIVE_USER_APPS = "sensitive_user_apps"
    private const val KEY_SENSITIVE_EXCLUDED_APPS = "sensitive_excluded_apps"
    private const val KEY_FULL_AUTO_SHIZUKU = "full_auto_shizuku"
    private const val KEY_FULL_AUTO_ADB = "full_auto_adb"
    private const val KEY_ADB_PAIRED = "adb_paired"
    private const val KEY_USAGE_SELF_GRANTED = "usage_access_self_granted"
    private const val KEY_SECURE_SELF_GRANTED = "secure_settings_self_granted"
    private const val KEY_REENABLE_DELAY_MIN = "reenable_delay_min"
    private const val KEY_ACTIVATION_MODE = "activation_mode"
    private const val KEY_ONDEMAND_LINGER_SEC = "ondemand_linger_sec"
    private const val KEY_NOTIF_SERVICE_OFF = "notif_service_off"
    private const val KEY_NOTIF_CLOSED = "notif_closed"
    private const val KEY_NOTIF_REENABLED = "notif_reenabled"
    private const val KEY_NOTIF_ACTIVATED = "notif_activated"
    private const val KEY_NOTIF_SILENCE_ALL = "notif_silence_all"

    /** Tope del deslizador de la UI y del acumulado de esperas. */
    const val MAX_REENABLE_DELAY_MIN = 10

    /** Gracia por defecto y tope del deslizador del modo bajo demanda. */
    const val DEFAULT_LINGER_SEC = 15
    const val MAX_LINGER_SEC = 60

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun featureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FEATURE_ENABLED, true)

    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FEATURE_ENABLED, enabled).apply()
    }

    /** Apagar el servicio solo al detectar una app bancaria (default: sí). */
    fun autoDisableForBanks(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DISABLE_BANKS, true)

    fun setAutoDisableForBanks(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DISABLE_BANKS, enabled).apply()
    }

    /** Marca que el último apagado fue por una app bancaria (para el mensaje de la UI). */
    fun disabledByBank(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLED_BY_BANK, false)

    fun setDisabledByBank(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLED_BY_BANK, value).apply()
    }

    /** Packages que el usuario marcó como "app sensible" (además de la lista integrada). */
    fun sensitiveUserApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SENSITIVE_USER_APPS, emptySet()) ?: emptySet()

    fun setSensitiveUserApps(context: Context, apps: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SENSITIVE_USER_APPS, apps).apply()
    }

    /**
     * Packages pre-marcados (lista integrada o prefijo) que el usuario apagó
     * explícitamente: son la excepción que gana sobre la marca por defecto.
     */
    fun sensitiveExcludedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SENSITIVE_EXCLUDED_APPS, emptySet()) ?: emptySet()

    fun setSensitiveExcludedApps(context: Context, apps: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SENSITIVE_EXCLUDED_APPS, apps).apply()
    }

    /**
     * Modo full-auto: con Shizuku, re-activar el servicio solo al cerrarse la
     * app sensible (default: no; requiere que el usuario lo active y conceda
     * el permiso de Shizuku).
     */
    fun fullAutoShizuku(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULL_AUTO_SHIZUKU, false)

    fun setFullAutoShizuku(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL_AUTO_SHIZUKU, enabled).apply()
    }

    /**
     * Modo full-auto vía ADB inalámbrico embebido (alternativa a Shizuku, sin
     * app externa). Como Shizuku: default no; requiere emparejar el dispositivo.
     */
    fun fullAutoAdb(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULL_AUTO_ADB, false)

    fun setFullAutoAdb(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL_AUTO_ADB, enabled).apply()
    }

    /** El dispositivo ya se emparejó por ADB (la clave RSA es la fuente de
     *  verdad; esto es solo un flag barato para la UI). */
    fun adbPaired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADB_PAIRED, false)

    fun setAdbPaired(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADB_PAIRED, value).apply()
    }

    /**
     * El "Acceso de uso" lo concedió la propia app (vía shell privilegiado), no
     * el usuario a mano. Solo entonces se revoca al apagar el full-auto: un
     * permiso que el usuario dio por su cuenta no es nuestro para quitarlo.
     */
    fun usageAccessSelfGranted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USAGE_SELF_GRANTED, false)

    /**
     * `commit()` y no `apply()`: esto se escribe desde [BankWatchService] justo
     * antes de que el servicio se pare, y con el servicio de accesibilidad
     * apagado el proceso es candidato a morir enseguida. Un `apply()` puede
     * quedarse sin volcar a disco, y perder la marca significa dejar el permiso
     * concedido para siempre. Es una escritura rara y fuera del camino caliente.
     */
    fun setUsageAccessSelfGranted(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USAGE_SELF_GRANTED, value).commit()
    }

    /**
     * `WRITE_SECURE_SETTINGS` lo concedió la app y no el usuario por su cuenta.
     * Solo informativo: a diferencia del "Acceso de uso", este NO se revoca solo
     * (ver [PrivilegedShell.revokeSecureSettings]). `commit()` por el mismo
     * motivo que [setUsageAccessSelfGranted]: se escribe desde un servicio que
     * está por morir.
     */
    fun secureSettingsSelfGranted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SECURE_SELF_GRANTED, false)

    fun setSecureSettingsSelfGranted(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SECURE_SELF_GRANTED, value).commit()
    }

    /**
     * Minutos de gracia antes de reactivar el servicio cuando la app sensible se
     * cierra (0 = al instante, el comportamiento histórico).
     *
     * Existe porque salir del banco cinco segundos a mirar un dato y volver
     * hacía que la app encontrara un servicio de accesibilidad recién activado y
     * mostrara su error anti-fraude. Durante la espera se sigue vigilando el
     * primer plano: volver a la app sensible la cancela (ver [BankWatchService]).
     */
    fun reenableDelayMin(context: Context): Int =
        prefs(context).getInt(KEY_REENABLE_DELAY_MIN, 3)

    fun setReenableDelayMin(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_REENABLE_DELAY_MIN, minutes.coerceIn(0, MAX_REENABLE_DELAY_MIN))
            .apply()
    }

    // --- Modo de activación ---

    /**
     * Modo guardado, sin comprobar si el dispositivo puede sostenerlo. Para
     * decidir comportamiento usar [Capabilities.onDemandMode], que degrada a
     * [ActivationMode.ALWAYS] si faltan los permisos especiales.
     */
    fun activationMode(context: Context): ActivationMode =
        if (prefs(context).getString(KEY_ACTIVATION_MODE, null) == MODE_ON_DEMAND) {
            ActivationMode.ON_DEMAND
        } else {
            ActivationMode.ALWAYS
        }

    fun setActivationMode(context: Context, mode: ActivationMode) {
        val value = if (mode == ActivationMode.ON_DEMAND) MODE_ON_DEMAND else MODE_ALWAYS
        prefs(context).edit().putString(KEY_ACTIVATION_MODE, value).apply()
    }

    /**
     * Segundos que el servicio de accesibilidad sigue encendido tras salir de la
     * cámara (0 = apagar en cuanto se confirma la salida).
     *
     * Existe porque salir un momento a la galería a revisar la toma y volver es
     * lo normal: apagar y encender en cada ida y vuelta hace que la cámara se
     * encuentre un servicio recién activado. Una app sensible en primer plano
     * corta la gracia y apaga al instante (ver [CameraWatchService]).
     */
    fun onDemandLingerSec(context: Context): Int =
        prefs(context).getInt(KEY_ONDEMAND_LINGER_SEC, DEFAULT_LINGER_SEC)

    fun setOnDemandLingerSec(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(KEY_ONDEMAND_LINGER_SEC, seconds.coerceIn(0, MAX_LINGER_SEC))
            .apply()
    }

    // --- Notificaciones ---
    //
    // Solo gobiernan los avisos OPCIONALES. Las notificaciones de foreground
    // service (pausa, cuenta atrás, vigilante) las exige Android y no se pueden
    // suprimir: para esas, [silenceAllNotifs] baja el sonido y la UI ofrece el
    // atajo al canal del sistema.

    /** Aviso de que el servicio se apagó al abrirse una app sensible. */
    fun notifServiceOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_SERVICE_OFF, true)

    fun setNotifServiceOff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_SERVICE_OFF, value).apply()
    }

    /** Aviso de "la app sensible se cerró, reactiva a mano". */
    fun notifClosed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_CLOSED, true)

    fun setNotifClosed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_CLOSED, value).apply()
    }

    /** Aviso de "Video Boost volvió" tras una reactivación automática. */
    fun notifReenabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_REENABLED, true)

    fun setNotifReenabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_REENABLED, value).apply()
    }

    /** Confirmación de que Video Boost quedó activado en la cámara. Apagada por
     *  defecto: es una notificación por sesión de cámara y la mayoría no la
     *  quiere; sirve sobre todo para comprobar el modo bajo demanda. */
    fun notifActivated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_ACTIVATED, false)

    fun setNotifActivated(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_ACTIVATED, value).apply()
    }

    /** Maestro: manda todo por canal silencioso (ver [Notifications]). */
    fun silenceAllNotifs(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_SILENCE_ALL, false)

    fun setSilenceAllNotifs(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_SILENCE_ALL, value).apply()
    }
}

/**
 * Cómo se activa Video Boost.
 *
 * - [ALWAYS]: el servicio de accesibilidad queda encendido siempre y filtra en
 *   código (comportamiento histórico). No necesita permisos especiales.
 * - [ON_DEMAND]: el servicio está APAGADO salvo mientras Pixel Camera está en
 *   primer plano; [CameraWatchService] lo enciende al detectar la cámara y lo
 *   apaga al salir. Necesita "Acceso de uso" + `WRITE_SECURE_SETTINGS`, o sea
 *   los permisos especiales que la app se auto-concede por ADB/Shizuku.
 */
enum class ActivationMode { ALWAYS, ON_DEMAND }

private const val MODE_ALWAYS = "always"
private const val MODE_ON_DEMAND = "on_demand"
