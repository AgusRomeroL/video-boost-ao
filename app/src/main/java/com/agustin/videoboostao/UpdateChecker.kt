package com.agustin.videoboostao

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chequeo de actualizaciones contra GitHub Releases (sin dependencias:
 * HttpURLConnection + org.json). Falla en silencio (null) sin red o si la
 * API no responde — la app nunca se bloquea por esto.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/AgusRomeroL/video-boost-ao/releases/latest"

    data class Update(val versionName: String, val pageUrl: String)

    /** Devuelve la actualización disponible, o null si ya estamos al día (o sin red). */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        try {
            val current = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: return@withContext null

            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.getString("tag_name")          // p. ej. "v2.1"
            val remote = tag.removePrefix("v")
            val pageUrl = json.getString("html_url")

            if (isNewer(remote, current)) Update(remote, pageUrl) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Comparación numérica por segmentos: "2.10" > "2.9" > "2.1". */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
