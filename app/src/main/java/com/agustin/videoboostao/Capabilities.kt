package com.agustin.videoboostao

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
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

    /**
     * `WRITE_SECURE_SETTINGS` concedido a este proceso (auto-concedido con
     * `pm grant`, ver el manifest). Chequeo barato para la UI; para decidir si
     * hacer una escritura conviene [SecureSettings.canWriteNow], que no depende
     * de la caché de permisos local.
     *
     * OJO: esto NO entra en [fullAutoReady] ni en [canMonitorClose] a propósito.
     * El permiso permite REACTIVAR, no leer el primer plano; si contara como
     * full-auto, [VideoBoostService] arrancaría un [BankWatchService] que nunca
     * podría detectar el cierre de la app sensible.
     */
    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** ¿Puede la app volver a activarse sola al cerrarse la app sensible? Es lo
     *  que habilita la cuenta atrás de gracia: sin esto el usuario reactiva a
     *  mano y no hay nada que demorar. */
    fun canReenableItself(context: Context): Boolean =
        canWriteSecureSettings(context) || fullAutoReady(context)

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
     * ¿Se sostiene el modo bajo demanda? Hacen falta las DOS mitades y son
     * justo los permisos especiales que la app se auto-concede por ADB/Shizuku:
     * leer el primer plano para saber que la cámara se abrió ("Acceso de uso") y
     * encender/apagar el propio servicio de accesibilidad
     * (`WRITE_SECURE_SETTINGS`).
     *
     * A diferencia del full-auto, acá NO valen Shizuku ni ADB como sustitutos en
     * caliente: el modo enciende y apaga varias veces al día, y hacerlo abriendo
     * una conexión ADB cada vez reintroduciría la notificación de "Depuración
     * inalámbrica" del sistema en cada sesión de cámara. Shizuku y ADB sirven
     * para CONCEDER los permisos una vez (ver [InitialEnable]), no para operar.
     */
    fun canRunOnDemand(context: Context): Boolean =
        hasUsageAccess(context) && canWriteSecureSettings(context)

    /**
     * Modo REALMENTE en vigor. Si el usuario eligió bajo demanda y después perdió
     * un permiso (lo revocó a mano, reinstaló...), esto degrada a
     * [ActivationMode.ALWAYS] y la app vuelve al comportamiento de siempre en vez
     * de quedarse sin activar nada. La preferencia guardada no se toca: si el
     * permiso vuelve, el modo vuelve.
     */
    fun activationMode(context: Context): ActivationMode =
        if (Prefs.activationMode(context) == ActivationMode.ON_DEMAND &&
            canRunOnDemand(context)
        ) {
            ActivationMode.ON_DEMAND
        } else {
            ActivationMode.ALWAYS
        }

    fun onDemandMode(context: Context): Boolean =
        activationMode(context) == ActivationMode.ON_DEMAND

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
