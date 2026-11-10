package com.photobooth.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class RemoteGalleryApiTest {
    private class Seen(val requestLine: String, val authorization: String?, val body: String)

    private lateinit var socket: ServerSocket
    private val seen = mutableListOf<Seen>()
    private var status = 200
    private val base get() = "http://127.0.0.1:${socket.localPort}"

    @Before
    fun start() {
        socket = ServerSocket(0)
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    val input = it.getInputStream()
                    val head = readHead(input)
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = String(input.readNBytes(length), Charsets.ISO_8859_1)
                    seen += Seen(head.lineSequence().first(), Regex("Authorization: (.*)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.trim(), body)
                    val reply = if (status == 200) """{"id":"abc 1","url":"https://example.test/s/xyz"}""" else ""
                    it.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply".toByteArray())
                }
            }
        }
    }

    @After
    fun stop() = socket.close()

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (!out.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) out.write(input.read())
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun jpeg() = File.createTempFile("shot", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)); deleteOnExit() }

    @Test
    fun uploadSendsBearerFieldsAndFileAndReturnsLink() {
        val file = jpeg()
        val result = RemoteGalleryApi().upload(
            base, "tok123", UploadRequest(file, UploadKind.PHOTO, "sess-1", "GRID_2X2", "Gala", "2026-11-06", 3),
        ).getOrThrow()

        assertEquals("abc 1", result.remoteId)
        assertEquals("https://example.test/s/xyz", result.downloadUrl)
        val request = seen.single()
        assertTrue(request.requestLine.startsWith("POST /api/photos"))
        assertEquals("Bearer tok123", request.authorization)
        listOf("session_id" to "sess-1", "layout_type" to "GRID_2X2", "event_name" to "Gala", "event_date" to "2026-11-06", "photo_number" to "3", "kind" to "photo")
            .forEach { (k, v) -> assertTrue("$k=$v in ${request.body}", request.body.contains("name=\"$k\"\r\n\r\n$v\r\n")) }
        assertTrue(request.body.contains("name=\"file\"; filename=\"${file.name}\""))
    }

    @Test
    fun deleteTargetsTheEncodedId() {
        assertTrue(RemoteGalleryApi().delete(base, "tok", "abc 1").isSuccess)
        assertTrue(seen.single().requestLine.startsWith("DELETE /api/photos/abc%201"))
    }

    @Test
    fun serverErrorIsAFailure() {
        status = 401
        assertTrue(RemoteGalleryApi().upload(base, "bad", UploadRequest(jpeg(), UploadKind.MONTAGE, "s", "GRID_2X2", "e", "2026-01-01", 0)).isFailure)
    }
}
