package com.photobooth.share

import com.photobooth.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

enum class UploadKind(val wire: String) { PHOTO("photo"), COLLAGE("collage") }

/** The booth's key, typed in the admin panel as "id.secret" (secret in base64url, at least 32 bytes). */
class BoothCredential private constructor(val keyId: String, internal val secret: ByteArray) {
    /** First bytes of the secret's SHA-256, to compare keys in logs without revealing them. */
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(secret).take(4).joinToString("") { "%02x".format(it) } + "/${secret.size}"

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,32}$")

        fun parse(raw: String): BoothCredential? {
            // Keyboards like to add a space after the dot: a key never contains whitespace.
            val (id, secret) = raw.filterNot { it.isWhitespace() }.split('.', limit = 2).takeIf { it.size == 2 } ?: return null
            if (!ID.matches(id)) return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(secret.trimEnd('=')) }.getOrNull() ?: secret.toByteArray()
            return if (bytes.size >= 32) BoothCredential(id, bytes) else null
        }
    }
}

class TransatServer(baseUrl: String, val credential: BoothCredential) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')
}

/** Everything the server is told about one image before it is sent. */
class UploadRequest(
    val file: File,
    val kind: UploadKind,
    val number: Int,
    val eventName: String,
    /** ISO date (yyyy-MM-dd) of the event. */
    val eventDate: String,
    val layoutType: String,
)

class StartedUpload(val fileId: Long, val uploadUrl: String, val fileName: String)

/** A non-2xx answer. 4xx other than 408/429 will not get better by retrying. */
class ApiException(val code: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = code >= 500 || code == 408 || code == 429
}

/**
 * Client of the Transat backend's photobooth API. Every request is signed with the booth key (HMAC-SHA256 over
 * method, path, time, a one-time nonce and the body hash), so only a booth holding the key can call it, and a
 * captured request can be neither changed nor replayed. The pictures themselves go straight to Google Drive
 * through the one-file upload sessions the backend opens; the backend then checks size and MD5 before keeping them.
 */
@Singleton
class TransatShareApi @Inject constructor() {

    /** Server clock minus ours, learnt when the server refuses our time. */
    @Volatile private var clockOffsetSec = 0L
    private val random = SecureRandom()

    fun ping(server: TransatServer): Result<Unit> = runCatching { call(server, "GET", "/photobooth/booth/ping", null) }

    fun start(server: TransatServer, sessionId: String, r: UploadRequest, md5Hex: String): Result<StartedUpload> = runCatching {
        val body = JSONObject()
            .put("kind", r.kind.wire)
            .put("number", r.number)
            .put("event_name", r.eventName)
            .put("event_date", r.eventDate)
            .put("layout_type", r.layoutType)
            .put("size", r.file.length())
            .put("md5", md5Hex)
            .put("mime_type", "image/jpeg")
        val json = JSONObject(call(server, "POST", "${session(sessionId)}/files", body.toString()))
        StartedUpload(json.getLong("file_id"), json.getString("upload_url"), json.optString("file_name"))
    }

    /** Sends the bytes to the Drive upload session; returns the new Drive file id. */
    fun sendToDrive(uploadUrl: String, file: File): Result<String> = runCatching {
        val url = URL(uploadUrl)
        val drive = url.protocol == "https" && url.host.endsWith(".googleapis.com")
        require(drive || (BuildConfig.DEBUG && url.host in LOCAL_HOSTS)) { "unexpected upload host ${url.host}" }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "image/jpeg")
            setFixedLengthStreamingMode(file.length())
        }
        try {
            conn.outputStream.buffered().use { out -> file.inputStream().use { it.copyTo(out) } }
            val text = readBody(conn)
            JSONObject(text).optString("id").ifEmpty { throw IOException("Drive returned no file id") }
        } finally {
            conn.disconnect()
        }
    }

    /** Confirms an upload. Throws ApiException(410) when the server discarded the picture meanwhile. */
    fun complete(server: TransatServer, sessionId: String, fileId: Long, driveFileId: String): Result<Unit> = runCatching {
        call(server, "POST", "${session(sessionId)}/files/$fileId/complete", JSONObject().put("drive_file_id", driveFileId).toString())
    }

    fun discardSlot(server: TransatServer, sessionId: String, kind: UploadKind, number: Int): Result<Unit> = runCatching {
        call(server, "DELETE", "${session(sessionId)}/slots/${kind.wire}/$number", null)
    }

    fun cancel(server: TransatServer, sessionId: String): Result<Unit> = runCatching { call(server, "DELETE", session(sessionId), null) }

    /** Returns the permanent share link once the collage and photos 1..photoCount are on Drive. */
    fun finalize(server: TransatServer, sessionId: String, photoCount: Int): Result<String> = runCatching {
        val json = JSONObject(call(server, "POST", "${session(sessionId)}/finalize", JSONObject().put("photo_count", photoCount).toString()))
        json.getString("url").also { require(it.startsWith("https://")) { "unexpected share link" } }
    }

    private fun session(id: String): String {
        require(SESSION_ID.matches(id)) { "invalid session id" }
        return "/photobooth/booth/sessions/$id"
    }

    private fun call(server: TransatServer, method: String, path: String, body: String?, retriedClock: Boolean = false): String {
        val url = URL(server.baseUrl + path)
        require(url.protocol == "https" || (BuildConfig.DEBUG && url.host in LOCAL_HOSTS)) { "the server must use https" }
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = API_READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            sign(this, server.credential, method, URI(url.toString()).rawPath, bytes)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(bytes.size)
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(bytes) }
            return readBody(conn)
        } catch (e: ApiException) {
            val serverTime = conn.getHeaderField("X-Photobooth-Server-Time")?.toLongOrNull()
            if (e.code == 401 && serverTime != null && !retriedClock && e.message.orEmpty().contains("clock_skew")) {
                clockOffsetSec = serverTime - System.currentTimeMillis() / 1000
                return call(server, method, path, body, retriedClock = true)
            }
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun sign(conn: HttpURLConnection, credential: BoothCredential, method: String, rawPath: String, body: ByteArray) {
        val timestamp = (System.currentTimeMillis() / 1000 + clockOffsetSec).toString()
        val nonce = ByteArray(18).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        conn.setRequestProperty("X-Photobooth-Key", credential.keyId)
        conn.setRequestProperty("X-Photobooth-Timestamp", timestamp)
        conn.setRequestProperty("X-Photobooth-Nonce", nonce)
        conn.setRequestProperty("X-Photobooth-Signature", signature(credential.secret, canonical(method, rawPath, timestamp, nonce, body)))
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) {
            val detail = conn.errorStream?.use { String(it.readBytes()).take(300) }.orEmpty()
            throw ApiException(code, "HTTP $code $detail".trim())
        }
        return conn.inputStream.use { String(it.readBytes()) }
    }

    companion object {
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val API_READ_TIMEOUT_MS = 45_000
        private const val UPLOAD_READ_TIMEOUT_MS = 120_000

        fun canonical(method: String, rawPath: String, timestamp: String, nonce: String, body: ByteArray): String =
            "PB1\n${method.uppercase()}\n$rawPath\n$timestamp\n$nonce\n${hex(MessageDigest.getInstance("SHA-256").digest(body))}"

        fun signature(secret: ByteArray, canonical: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
        }

        fun md5Hex(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
