package com.agustin.videoboostao

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import kotlin.coroutines.resume

/**
 * Alternativa in-app a Shizuku: un cliente ADB embebido que se empareja con el
 * "Wireless debugging" del propio dispositivo (Android 11+) y corre los mismos
 * comandos shell que [UserService], sin necesitar la app externa Shizuku.
 *
 * El emparejamiento (pairing) guarda una clave RSA en el almacenamiento privado
 * de la app; a partir de ahí la reconexión no vuelve a pedir el código de 6
 * dígitos (solo redescubre el puerto por mDNS, que cambia en cada reinicio).
 *
 * Todas las operaciones de red (pair/connect/exec) son bloqueantes y corren en
 * [Dispatchers.IO]; nunca en el hilo principal.
 */
object AdbManager {

    private const val TAG = "VideoBoostAO"
    private const val PRIVATE_KEY_FILE = "adbkey_private.key"
    private const val CERT_FILE = "adbkey_cert.pem"

    @Volatile
    private var manager: ConnectionManager? = null

    /** Un solo connect a la vez (ver [connect]). */
    private val connectMutex = Mutex()

    /** Un solo comando a la vez sobre la conexión (ver [exec]). */
    private val execLock = Any()

    data class Endpoint(val host: String, val port: Int)

    /** Emparejado antes (clave presente + flag). Check síncrono barato. */
    fun isPaired(context: Context): Boolean =
        Prefs.adbPaired(context) &&
            File(context.filesDir, PRIVATE_KEY_FILE).exists() &&
            File(context.filesDir, CERT_FILE).exists()

    /** Descubre el endpoint de pairing (`_adb-tls-pairing._tcp`) por mDNS. */
    suspend fun discoverPairingEndpoint(context: Context, timeoutMs: Long = 15_000): Endpoint? =
        discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    /** Handle para parar un descubrimiento continuo de pairing. */
    class PairingDiscovery internal constructor(private val mdns: AdbMdns) {
        fun stop() {
            runCatching { mdns.stop() }
        }
    }

    /**
     * Descubrimiento CONTINUO del endpoint de pairing, para el flujo por
     * notificación (estilo Shizuku): la app escucha en segundo plano y, cuando
     * el usuario abre "Vincular con código", el servicio mDNS aparece y se
     * dispara [onFound] con host+puerto. Corre hasta que se llame a
     * [PairingDiscovery.stop]. [onFound] llega en un hilo de NsdManager.
     */
    fun startPairingDiscovery(context: Context, onFound: (Endpoint) -> Unit): PairingDiscovery {
        ensureHiddenApiBypass()
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            val h = host?.hostAddress
            if (port > 0 && h != null) onFound(Endpoint(h, port))
        }
        mdns.start()
        return PairingDiscovery(mdns)
    }

    /**
     * Empareja con el daemon usando el código de 6 dígitos. Al éxito persiste la
     * clave y marca `adb_paired`. [host]/[port] vienen del descubrimiento mDNS o
     * los teclea el usuario (lo que muestra el diálogo de Wireless debugging).
     */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ok = manager(context).pair(host, port, code)
                if (!ok) error("pair() devolvió false")
                Prefs.setAdbPaired(context, true)
            }.onFailure { Log.w(TAG, "ADB pair falló", it) }
        }

    /**
     * Conecta al daemon (`_adb-tls-connect._tcp`) usando la clave ya emparejada.
     * [autoConnect] descubre el puerto por mDNS internamente. Requiere que el
     * usuario haya (re)activado "Wireless debugging" tras el último reinicio.
     *
     * No se valida con una sonda: en el Pixel 10 Pro XL se comprobó que un
     * comando se ejecuta aunque leer su salida falle, así que una sonda que
     * "falla" no significa conexión inútil y descartarla por eso solo hacía
     * perder tiempo. Quien necesite certeza verifica el efecto, no la salida
     * (ver [Capabilities.accessibilityServiceEnabled]).
     */
    suspend fun connect(context: Context, timeoutMs: Long = 12_000): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Serializado: dos conexiones concurrentes (el connect inicial y un
            // reintento, p. ej.) se cerraban mutuamente el manager compartido y
            // todo comando posterior moría con "Stream closed.".
            connectMutex.withLock {
                runCatching {
                    repeat(2) { attempt ->
                        val m = manager(context)
                        val connected = try {
                            m.isConnected || m.autoConnect(context, timeoutMs)
                        } catch (e: Exception) {
                            Log.w(TAG, "ADB autoConnect lanzó (intento ${attempt + 1}): ${e.message}")
                            false
                        }
                        if (connected) return@runCatching
                        invalidateIf(m)
                    }
                    error("autoConnect() falló")
                }.onFailure { Log.w(TAG, "ADB connect falló: ${it.message}") }
            }
        }

    /** Un [PrivilegedShell] sobre la conexión ADB viva. El caller debe haber
     *  llamado a [connect] con éxito antes. */
    fun shell(context: Context): PrivilegedShell = AdbPrivilegedShell()

    /** Hay un manager con conexión abierta (no garantiza que responda). */
    fun isConnected(): Boolean = manager?.isConnected == true

    /** Timeout duro por comando: un stream colgado no debe congelar al caller
     *  (ver la carrera descrita en [execRetrying]). */
    private const val EXEC_TIMEOUT_MS = 4_000L

    /** Timeout corto para comandos cuyo efecto se verifica aparte: ahí no se
     *  espera la salida, así que no tiene sentido aguantar el timeout largo. */
    const val FIRE_TIMEOUT_MS = 1_200L

    /** Intentos de abrir stream sobre una misma conexión antes de descartarla. */
    private const val STREAM_ATTEMPTS = 4
    private const val STREAM_RETRY_MS = 200L

    /**
     * Corre `shell:cmd` y devuelve stdout, o null si no se pudo.
     *
     * El fallo en libadb 3.1.1 es POR STREAM, no por conexión: abrir un stream
     * es una carrera (ver [execRetrying]) y falla o se cuelga de forma
     * intermitente, mientras que la conexión de fondo sigue sana (se comprobó
     * en el Pixel 10 Pro XL: varios comandos seguidos funcionan tras un fallo).
     * Por eso primero se reintenta el stream sobre la MISMA conexión, que es
     * barato, y solo si todos fallan se da la conexión por perdida.
     */
    fun exec(
        cmd: String,
        timeoutMs: Long = EXEC_TIMEOUT_MS,
        streamAttempts: Int = STREAM_ATTEMPTS,
    ): String? {
        val m = manager ?: return null
        // Un comando a la vez: los streams comparten la conexión y entrelazarlos
        // la corrompe.
        return synchronized(execLock) {
            repeat(streamAttempts) { i ->
                val task = java.util.concurrent.FutureTask { rawExec(m, cmd) }
                Thread(task, "vb-adb-exec").apply { isDaemon = true }.start()
                try {
                    return@synchronized task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    val cause = (e.cause ?: e).let { "${it.javaClass.simpleName}: ${it.message}" }
                    Log.w(TAG, "ADB stream falló ($cause), intento ${i + 1}/$streamAttempts")
                    // El hilo del intento colgado queda como daemon; el
                    // siguiente abre su propio stream.
                    if (i < streamAttempts - 1) Thread.sleep(STREAM_RETRY_MS)
                }
            }
            // Solo si sigue siendo la conexión vigente: si otro hilo ya la
            // reemplazó, cerrarla mataría una conexión sana.
            invalidateIf(m)
            null
        }
    }

    private fun rawExec(m: ConnectionManager, cmd: String): String {
        val stream = m.openStream("shell:$cmd")
        try {
            return stream.openInputStream().bufferedReader().use { it.readText() }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Corre [cmd] reintentando con conexión nueva. `openStream` de libadb 3.1.1
     * tiene una carrera: si la respuesta del daemon llega antes de que se entre
     * al `wait()`, el notify se pierde y la llamada se cuelga para siempre. El
     * timeout de [exec] la corta, pero la única salida es rehacer la conexión;
     * de ahí los reintentos. Esta carrera era la causa de que el full-auto
     * fallara "a veces" y de que otras veces solo reaccionara al cabo de los 15
     * minutos del tope de sesión.
     */
    suspend fun execRetrying(
        context: Context,
        cmd: String,
        attempts: Int = 3,
        valid: (String) -> Boolean = { it.isNotBlank() },
    ): String? {
        repeat(attempts) { i ->
            // `valid` y no "salida no vacía": el servicio `shell:` de adbd
            // mezcla stderr en stdout, así que un error del shell también
            // llega como salida y se tomaría por éxito.
            exec(cmd)?.let { if (valid(it)) return it }
            if (i < attempts - 1) {
                Log.w(TAG, "ADB: comando sin resultado válido (intento ${i + 1}); reconecto")
                disconnect()
                if (connect(context).isFailure) return null
            }
        }
        Log.w(TAG, "ADB: comando falló tras $attempts intentos")
        return null
    }

    /** Cierra la conexión y descarta el manager (no borra la clave; sigue
     *  emparejado). La próxima operación parte con un manager nuevo. */
    fun disconnect() {
        synchronized(this) { close(manager) }
    }

    /** Descarta [m] solo si sigue siendo la conexión vigente. */
    private fun invalidateIf(m: ConnectionManager) {
        synchronized(this) { if (manager === m) close(m) }
    }

    private fun close(m: ConnectionManager?) {
        try {
            m?.close()
        } catch (_: Exception) {
        }
        manager = null
    }

    // --- interno ---

    private fun manager(context: Context): ConnectionManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: run {
                ensureHiddenApiBypass()
                ConnectionManager(context.applicationContext).also { manager = it }
            }
        }
    }

    /** libadb/Conscrypt tocan APIs ocultas en el handshake TLS; en Android 9+
     *  hay que eximirlas una vez antes del primer uso. */
    private fun ensureHiddenApiBypass() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun discover(context: Context, serviceType: String, timeoutMs: Long): Endpoint? {
        var ref: AdbMdns? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val mdns = AdbMdns(context, serviceType) { host, port ->
                        val h = host?.hostAddress
                        if (port > 0 && h != null && cont.isActive) {
                            cont.resume(Endpoint(h, port))
                        }
                    }
                    ref = mdns
                    cont.invokeOnCancellation { runCatching { mdns.stop() } }
                    mdns.start()
                }
            }
        } finally {
            runCatching { ref?.stop() }
        }
    }

    /**
     * Subclase concreta de la librería: solo aporta la identidad (clave RSA +
     * certificado X.509 autofirmado) y el nombre del dispositivo. La conexión y
     * el pairing los maneja la clase base. Traducción del ejemplo de referencia
     * de libadb-android, usando el namespace `android.sun.*` de sun-security.
     */
    private class ConnectionManager(context: Context) : AbsAdbConnectionManager() {

        private val privateKey: PrivateKey
        private val certificate: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val existingKey = readPrivateKey(context)
            val existingCert = readCertificate(context)
            if (existingKey != null && existingCert != null) {
                privateKey = existingKey
                certificate = existingCert
            } else {
                val keyGen = KeyPairGenerator.getInstance("RSA").apply {
                    initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
                }
                val pair = keyGen.generateKeyPair()
                privateKey = pair.private
                certificate = generateCertificate(pair.public, privateKey)
                writePrivateKey(context, privateKey)
                writeCertificate(context, certificate)
            }
        }

        override fun getPrivateKey(): PrivateKey = privateKey
        override fun getCertificate(): Certificate = certificate
        override fun getDeviceName(): String = "Video Boost"
    }

    private fun generateCertificate(publicKey: java.security.PublicKey, privateKey: PrivateKey): Certificate {
        val subject = "CN=Video Boost"
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 86_400_000L)
        val x500Name = X500Name(subject)

        val extensions = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }

        val certInfo = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(x500Name))
            set("key", CertificateX509Key(publicKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(x500Name))
            set("extensions", extensions)
        }
        return X509CertImpl(certInfo).apply { sign(privateKey, algorithm) }
    }

    private fun readPrivateKey(context: Context): PrivateKey? {
        val f = File(context.filesDir, PRIVATE_KEY_FILE)
        if (!f.exists()) return null
        return try {
            val spec = PKCS8EncodedKeySpec(f.readBytes())
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la clave ADB", e)
            null
        }
    }

    private fun writePrivateKey(context: Context, key: PrivateKey) {
        File(context.filesDir, PRIVATE_KEY_FILE).writeBytes(key.encoded)
    }

    private fun readCertificate(context: Context): Certificate? {
        val f = File(context.filesDir, CERT_FILE)
        if (!f.exists()) return null
        return try {
            f.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer el certificado ADB", e)
            null
        }
    }

    private fun writeCertificate(context: Context, cert: Certificate) {
        File(context.filesDir, CERT_FILE).outputStream().use { os ->
            os.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.UTF_8))
            os.write('\n'.code)
            BASE64Encoder().encode(cert.encoded, os)
            os.write('\n'.code)
            os.write(X509Factory.END_CERT.toByteArray(StandardCharsets.UTF_8))
        }
    }
}

/**
 * [PrivilegedShell] sobre la conexión ADB de [AdbManager]. Delegar cada comando
 * en [AdbManager.exec] (en vez de capturar el manager) hace que el shell
 * sobreviva a una invalidación/reconexión: siempre usa la conexión vigente y
 * hereda el timeout duro. Reusa [AdbShellCommands] para no divergir de la vía
 * Shizuku. Las llamadas son bloqueantes: correr en [Dispatchers.IO].
 */
class AdbPrivilegedShell internal constructor() : PrivilegedShell {

    override fun getForegroundPackage(): String =
        AdbShellCommands.foregroundPackage { AdbManager.exec(it).orEmpty() }

    override fun enableAccessibilityService(component: String): Boolean =
        AdbShellCommands.enableAccessibilityService(component) { AdbManager.exec(it).orEmpty() }

    override fun grantUsageAccess(pkg: String): Boolean =
        AdbShellCommands.grantUsageAccess(pkg) { AdbManager.exec(it).orEmpty() }

    override fun close() {
        AdbManager.disconnect()
    }
}
