package com.agustin.videoboostao

/**
 * Implementación del [IUserService] que Shizuku levanta en un proceso con
 * identidad shell (UID 2000). Ahí sí hay `WRITE_SECURE_SETTINGS`, así que
 * podemos re-habilitar el servicio de accesibilidad y leer el primer plano
 * vía comandos shell. Este proceso NO es un contexto de app válido: solo se
 * usa `Runtime.exec`, nada de APIs que requieran Context.
 */
class UserService : IUserService.Stub() {

    // Shizuku llama a esto para terminar el proceso del UserService.
    override fun destroy() {
        System.exit(0)
    }

    // Los comandos viven en AdbShellCommands para no divergir de la vía ADB
    // ([AdbPrivilegedShell]); aquí solo se provee el ejecutor Runtime.exec.
    override fun getForegroundPackage(): String =
        AdbShellCommands.foregroundPackage(::exec)

    override fun enableAccessibilityService(component: String): Boolean =
        AdbShellCommands.enableAccessibilityService(component, ::exec)

    override fun grantUsageAccess(pkg: String): Boolean =
        AdbShellCommands.grantUsageAccess(pkg, ::exec)

    override fun revokeUsageAccess(pkg: String): Boolean =
        AdbShellCommands.revokeUsageAccess(pkg, ::exec)

    private fun exec(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out
    } catch (_: Exception) {
        ""
    }
}
