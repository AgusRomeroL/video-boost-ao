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

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun featureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FEATURE_ENABLED, true)

    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FEATURE_ENABLED, enabled).apply()
    }
}
