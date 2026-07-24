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
}
