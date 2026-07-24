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
     *  accesibilidad global. Devuelve true si se verificó que quedó activo. */
    fun enableAccessibilityService(component: String): Boolean

    /** Concede "Acceso de uso" a [pkg] para que la app pueda leer el primer
     *  plano en local, sin tocar el shell privilegiado en cada sondeo. */
    fun grantUsageAccess(pkg: String): Boolean

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
    private val RESUMED = Regex("""ResumedActivity[=:][^\n]*?\bu\d+\s+([a-zA-Z0-9._]+)/""")

    /**
     * El `grep` corre EN EL DISPOSITIVO a propósito. `dumpsys activity
     * activities` completo son ~136 KB (medidos en el Pixel 10 Pro XL) y este
     * comando se ejecuta cada 1.2 s: transferir eso por el túnel TLS de ADB
     * saturaba la conexión, que quedaba muerta a los pocos sondeos y ya no
     * detectaba el cierre de la app sensible. Filtrado son ~100 bytes.
     */
    fun foregroundPackage(exec: (String) -> String): String {
        val out = exec("dumpsys activity activities | grep ResumedActivity")
        return RESUMED.find(out)?.groupValues?.getOrNull(1) ?: ""
    }

    /**
     * Todo en UN solo comando (leer, componer, escribir y releer para
     * verificar) a propósito: por la vía ADB cada comando abre un stream nuevo,
     * y abrir streams es la operación frágil (ver [AdbManager.exec]). Cuatro
     * comandos eran cuatro oportunidades de fallar; ahora es una.
     *
     * El `case` evita duplicar el componente si ya estaba, y trata "null"
     * (valor sin fijar) como lista vacía. La salida es el valor final, que se
     * verifica acá: sin esa comprobación, un shell muerto (stdout vacío)
     * reportaba éxito y el usuario veía "reactivado" con el servicio apagado.
     */
    fun enableAccessibilityService(component: String, exec: (String) -> String): Boolean =
        exec(enableScript(component)).contains(component)

    /**
     * El script de [enableAccessibilityService], expuesto para poder correrlo
     * con la vía reintentante de ADB ([AdbManager.execRetrying]).
     *
     * Va en UNA línea a propósito. Escribirlo en varias y unirlas con `;`
     * produce `case ... in;` y `;;;`, que son errores de sintaxis de sh; como
     * el servicio `shell:` de adbd mezcla stderr en stdout, el mensaje de error
     * volvía como si fuera salida válida y la reactivación se daba por hecha
     * sin haber ocurrido.
     */
    fun enableScript(component: String): String {
        val d = '$'
        return "c=$d(settings get secure enabled_accessibility_services); " +
            "[ \"${d}c\" = \"null\" ] && c=\"\"; " +
            "case \":${d}c:\" in *\":$component:\"*) n=\"${d}c\" ;; " +
            "*) if [ -z \"${d}c\" ]; then n=\"$component\"; else n=\"${d}c:$component\"; fi ;; esac; " +
            "settings put secure enabled_accessibility_services \"${d}n\"; " +
            "settings put secure accessibility_enabled 1; " +
            "settings get secure enabled_accessibility_services"
    }

    /** Un solo comando, por el mismo motivo que [enableAccessibilityService]. */
    fun grantUsageAccess(pkg: String, exec: (String) -> String): Boolean {
        val out = exec("appops set $pkg GET_USAGE_STATS allow; appops get $pkg GET_USAGE_STATS")
        return out.contains("allow")
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

    override fun grantUsageAccess(pkg: String): Boolean = try {
        service.grantUsageAccess(pkg)
    } catch (_: Exception) {
        false
    }

    // El unbind lo maneja BankWatchService sobre el ServiceConnection de Shizuku.
    override fun close() {}
}
