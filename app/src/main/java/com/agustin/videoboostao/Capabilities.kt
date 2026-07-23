package com.agustin.videoboostao

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * ¿Podemos monitorear el primer plano mientras el servicio de accesibilidad
 * está apagado por una app sensible? Hace falta para detectar el cierre y
 * mandar la notificación normal / re-activar (full-auto). Dos vías:
 * - Shizuku (además re-activa solo).
 * - "Acceso de uso" (UsageStats): solo detecta el cierre.
 */
object Capabilities {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Full-auto: Shizuku activado por el usuario y con permiso. Re-activa solo. */
    fun fullAutoReady(context: Context): Boolean =
        Prefs.fullAutoShizuku(context) && ShizukuManager.hasPermission()

    /** Cualquier vía de monitoreo del cierre de la app sensible. */
    fun canMonitorClose(context: Context): Boolean =
        fullAutoReady(context) || hasUsageAccess(context)
}
