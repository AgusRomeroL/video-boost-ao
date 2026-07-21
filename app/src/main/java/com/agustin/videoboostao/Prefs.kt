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
}
