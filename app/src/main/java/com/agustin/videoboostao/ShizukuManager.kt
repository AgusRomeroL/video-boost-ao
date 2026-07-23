package com.agustin.videoboostao

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Envoltorio de Shizuku para el modo full-auto. Todas las llamadas al SDK van
 * envueltas en try/catch: si Shizuku no está instalado/activo, el binder no
 * existe y los métodos lanzan; la app debe seguir funcionando sin Shizuku.
 */
object ShizukuManager {

    private const val PERMISSION_REQUEST_CODE = 4210

    /** Shizuku instalado y su servicio corriendo (binder vivo). */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** Disponible y con permiso concedido a esta app. */
    fun hasPermission(): Boolean = try {
        isAvailable() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Pide el permiso de Shizuku; devuelve el resultado por callback. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    /** Args para levantar el [UserService] como proceso shell. */
    fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, UserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("shizuku_uds")
            .debuggable(false)
            .version(1)
}
