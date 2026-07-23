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

    override fun getForegroundPackage(): String {
        val out = exec("dumpsys activity activities")
        // Busca "...ResumedActivity=ActivityRecord{hash u0 <pkg>/<act> ...}"
        // (cubre topResumedActivity / mResumedActivity según versión).
        val m = Regex("""ResumedActivity=[^\n]*?\bu\d+\s+([a-zA-Z0-9._]+)/""").find(out)
        return m?.groupValues?.getOrNull(1) ?: ""
    }

    override fun enableAccessibilityService(component: String): Boolean {
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

    private fun exec(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out
    } catch (_: Exception) {
        ""
    }
}
