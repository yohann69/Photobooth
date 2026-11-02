package com.photobooth.camera.ccapi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.photobooth.camera.partialFileFor
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResult(val code: Int, val body: ByteArray) {
    val isSuccess get() = code in 200..299
    fun text() = String(body, Charsets.UTF_8)
}

/** Minimal CCAPI HTTP client pinned to the local (Wi-Fi/Ethernet) network so camera traffic never leaks to cellular. */
internal class CcapiClient(context: Context, val host: String, val port: Int) {
    private val network: Network? = findLocalNetwork(context)

    /** API version -> endpoint paths, as advertised by GET /ccapi. */
    private var endpoints: Map<String, List<String>> = emptyMap()

    fun request(
        method: String,
        path: String,
        body: String? = null,
        connectTimeoutMs: Int = 1500,
        readTimeoutMs: Int = 4000,
    ): HttpResult {
        val conn = open(path, method, connectTimeoutMs, readTimeoutMs)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return HttpResult(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    fun download(path: String, target: File, readTimeoutMs: Int = 15_000): File {
        target.absoluteFile.parentFile?.mkdirs()
        val partial = partialFileFor(target)
        val conn = open(path, "GET", 2000, readTimeoutMs)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Download failed: HTTP $code")
            conn.inputStream.use { input ->
                FileOutputStream(partial).use { out ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Could not move ${partial.name}")
            return target
        } catch (e: Throwable) {
            partial.delete()
            conn.disconnect()
            throw e
        }
    }

    /** Fetches GET /ccapi; throws if the host is not a CCAPI camera. */
    fun loadApiMap(): Map<String, List<String>> {
        val r = request("GET", "/ccapi", connectTimeoutMs = 700, readTimeoutMs = 900)
        if (!r.isSuccess) throw IOException("GET /ccapi returned HTTP ${r.code}")
        val root = JSONObject(r.text())
        val map = mutableMapOf<String, List<String>>()
        for (version in root.keys()) {
            val array = root.optJSONArray(version) ?: continue
            map[version] = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("path")?.takeIf(String::isNotEmpty) }
        }
        if (map.isEmpty()) throw IOException("/ccapi advertised no API versions")
        endpoints = map
        return map
    }

    /** Highest advertised API version that exposes [suffix], falling back to ver100. */
    fun resolve(suffix: String): String {
        val versions = endpoints.keys.sortedByDescending { it.removePrefix("ver").toIntOrNull() ?: 0 }
        for (v in versions) {
            val match = endpoints.getValue(v).firstOrNull { it.endsWith("/$suffix") || it == suffix }
            if (match != null) return if (match.startsWith("/")) match else "/ccapi/$v/$match"
        }
        return "/ccapi/ver100/$suffix"
    }

    fun supportedVersions(): List<String> = endpoints.keys.sorted()

    private fun open(path: String, method: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        val url = URL("http://$host:$port$path")
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        return conn
    }

    companion object {
        fun findLocalNetwork(context: Context): Network? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        }
    }
}
