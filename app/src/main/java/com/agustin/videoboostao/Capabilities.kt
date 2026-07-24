package com.agustin.videoboostao

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * ¿Podemos monitorear el primer plano mientras el servicio de accesibilidad
 * está apagado por una app sensible? Hace falta para detectar el cierre y
 * mandar la notificación normal / re-activar (full-auto). Vías:
 * - Shizuku o ADB inalámbrico embebido (además re-activan solo).
 * - "Acceso de uso" (UsageStats): solo detecta el cierre.
 */
object Capabilities {

    /** Qué mecanismo privilegiado provee el full-auto. */
    enum class Mechanism { SHIZUKU, ADB, NONE }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Shizuku activado por el usuario y con permiso. */
    fun shizukuReady(context: Context): Boolean =
        Prefs.fullAutoShizuku(context) && ShizukuManager.hasPermission()

    /** ADB inalámbrico activado por el usuario y ya emparejado (check síncrono;
     *  la conexión real se prueba al bindear en BankWatchService). */
    fun adbReady(context: Context): Boolean =
        Prefs.fullAutoAdb(context) && AdbManager.isPaired(context)

    /** Full-auto: cualquiera de las dos vías privilegiadas. Re-activa solo. */
    fun fullAutoReady(context: Context): Boolean =
        shizukuReady(context) || adbReady(context)

    /** Mecanismo elegido. Prefiere Shizuku si ambos están configurados, para
     *  que los usuarios actuales no vean ningún cambio de comportamiento. */
    fun fullAutoMechanism(context: Context): Mechanism = when {
        shizukuReady(context) -> Mechanism.SHIZUKU
        adbReady(context) -> Mechanism.ADB
        else -> Mechanism.NONE
    }

    /** Cualquier vía de monitoreo del cierre de la app sensible. */
    fun canMonitorClose(context: Context): Boolean =
        fullAutoReady(context) || hasUsageAccess(context)

    /**
     * ¿[component] está en la lista de servicios de accesibilidad activos?
     *
     * Lectura LOCAL: escribir secure settings necesita privilegios, pero leerlos
     * no. Es la forma fiable de confirmar una reactivación: el comando ADB que
     * la ejecuta puede escribir bien y aun así fallar al devolver su salida
     * (ver [AdbManager.exec]), así que verificar por ADB daba falsos negativos
     * y provocaba reintentos que reactivaban el servicio una y otra vez.
     */
    fun accessibilityServiceEnabled(context: Context, component: String): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }
}
