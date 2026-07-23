package com.agustin.videoboostao

/**
 * Abstracción sobre "algo que puede correr comandos privilegiados (shell UID)
 * en el dispositivo". Dos implementaciones:
 * - [ShizukuPrivilegedShell]: sobre el binder de Shizuku ([IUserService]).
 * - [AdbPrivilegedShell]: sobre una conexión ADB inalámbrica al propio equipo.
 *
 * Gracias a esta interfaz, [BankWatchService] y el flujo de activación inicial
 * son agnósticos al mecanismo: solo necesitan re-activar la accesibilidad y
 * leer el primer plano, sin saber quién provee el privilegio.
 */
interface PrivilegedShell {
    /** Paquete de la app en primer plano, o "" si no se pudo leer. */
    fun getForegroundPackage(): String

    /** Añade [component] a los servicios de accesibilidad activos y enciende la
     *  accesibilidad global. Devuelve true si los comandos se ejecutaron. */
    fun enableAccessibilityService(component: String): Boolean

    /** Libera recursos. No-op para Shizuku (el servicio maneja el unbind);
     *  cierra la conexión TLS para ADB. */
    fun close()
}

/**
 * Comandos shell compartidos por las dos vías (Shizuku via [UserService] y ADB
 * via [AdbPrivilegedShell]), para que la lógica no se bifurque. Reciben un
 * ejecutor `exec(cmd) -> stdout` porque cada vía corre los comandos distinto
 * (Runtime.exec en el proceso shell de Shizuku; stream `shell:` en ADB).
 */
object AdbShellCommands {

    /** Regex del paquete resumido en `dumpsys activity activities`
     *  (cubre topResumedActivity / mResumedActivity segun version). */
    private val RESUMED = Regex("""ResumedActivity=[^\n]*?\bu\d+\s+([a-zA-Z0-9._]+)/""")

    fun foregroundPackage(exec: (String) -> String): String {
        val out = exec("dumpsys activity activities")
        return RESUMED.find(out)?.groupValues?.getOrNull(1) ?: ""
    }

    fun enableAccessibilityService(component: String, exec: (String) -> String): Boolean {
        val current = exec("settings get secure enabled_accessibility_services").trim()
        val services = current
            .split(':')
            .filter { it.isNotBlank() && it != "null" }
            .toMutableSet()
        services.add(component)
        val joined = services.joinToString(":")
        exec("settings put secure enabled_accessibility_services '$joined'")
        exec("settings put secure accessibility_enabled 1")
        return true
    }
}

/** Adapta el binder de Shizuku ([IUserService]) a [PrivilegedShell]. */
class ShizukuPrivilegedShell(private val service: IUserService) : PrivilegedShell {
    override fun getForegroundPackage(): String = try {
        service.foregroundPackage ?: ""
    } catch (_: Exception) {
        ""
    }

    override fun enableAccessibilityService(component: String): Boolean = try {
        service.enableAccessibilityService(component)
    } catch (_: Exception) {
        false
    }

    // El unbind lo maneja BankWatchService sobre el ServiceConnection de Shizuku.
    override fun close() {}
}
