package com.photobooth.share

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class UploadKind(val wire: String) { PHOTO("photo"), MONTAGE("montage") }

/** Everything the server is told about one image. */
class UploadRequest(
    val file: File,
    val kind: UploadKind,
    val sessionId: String,
    val layoutType: String,
    val eventName: String,
    /** ISO date (yyyy-MM-dd) of the event. */
    val eventDate: String,
    /** 1-based shot number; 0 for the final montage. */
    val photoNumber: Int,
)

/** What the server returns for an upload: the id to delete it with and the download page for the session. */
class UploadResult(val remoteId: String, val downloadUrl: String?)

/**
 * The only class that knows the server's wire format. The paths, field names and response fields below are
 * placeholders to be aligned with the real API; nothing else in the app depends on them.
 */
@Singleton
class RemoteGalleryApi @Inject constructor() {

    fun upload(baseUrl: String, token: String, request: UploadRequest): Result<UploadResult> = runCatching {
        val boundary = "photobooth-${UUID.randomUUID()}"
        val conn = open(baseUrl + UPLOAD_PATH, token, "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(multipartLength(boundary, request))
            conn.outputStream.buffered().use { out ->
                fields(request).forEach { (name, value) -> out.write(textPart(boundary, name, value)) }
                out.write(filePartHeader(boundary, request.file))
                request.file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val body = readBody(conn)
            val json = JSONObject(body)
            UploadResult(
                remoteId = json.optString(FIELD_ID).ifEmpty { throw IOException("Réponse sans identifiant: $body") },
                downloadUrl = json.optString(FIELD_URL).ifEmpty { null },
            )
        } finally {
            conn.disconnect()
        }
    }

    fun delete(baseUrl: String, token: String, remoteId: String): Result<Unit> = runCatching {
        val conn = open("$baseUrl$DELETE_PATH/${java.net.URLEncoder.encode(remoteId, "UTF-8").replace("+", "%20")}", token, "DELETE")
        try {
            readBody(conn)
            Unit
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    /** A 404 on delete means the image is already gone, which is what the caller wanted. */
    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_NOT_FOUND && conn.requestMethod == "DELETE") return ""
        if (code !in 200..299) {
            val detail = conn.errorStream?.use { String(it.readBytes()).take(200) }.orEmpty()
            throw IOException("HTTP $code $detail".trim())
        }
        return conn.inputStream.use { String(it.readBytes()) }
    }

    private fun textPart(boundary: String, name: String, value: String): ByteArray =
        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray()

    private fun filePartHeader(boundary: String, file: File): ByteArray =
        ("--$boundary\r\nContent-Disposition: form-data; name=\"$FIELD_FILE\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n").toByteArray()

    private fun fields(r: UploadRequest): Map<String, String> = linkedMapOf(
        "kind" to r.kind.wire,
        "session_id" to r.sessionId,
        "layout_type" to r.layoutType,
        "event_name" to r.eventName,
        "event_date" to r.eventDate,
        "photo_number" to r.photoNumber.toString(),
    )

    private fun multipartLength(boundary: String, r: UploadRequest): Long =
        fields(r).entries.sumOf { textPart(boundary, it.key, it.value).size.toLong() } +
            filePartHeader(boundary, r.file).size + r.file.length() + "\r\n--$boundary--\r\n".toByteArray().size

    companion object {
        const val UPLOAD_PATH = "/api/photos"
        const val DELETE_PATH = "/api/photos"
        const val FIELD_FILE = "file"
        const val FIELD_ID = "id"
        const val FIELD_URL = "url"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
