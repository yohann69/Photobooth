package com.photobooth.share

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.render.PhotoDecoder
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Event art direction applied to the guest download page. */
class GalleryTheme(val eventName: String, primaryHex: String, secondaryHex: String, backgroundHex: String, textHex: String) {
    val primary = safeHex(primaryHex, "#FF2E7E")
    val secondary = safeHex(secondaryHex, "#00E5FF")
    val background = safeHex(backgroundHex, "#07070D")
    val text = safeHex(textHex, "#FFFFFF")

    companion object {
        val DEFAULT = GalleryTheme("Photobooth", "#FF2E7E", "#00E5FF", "#07070D", "#FFFFFF")

        fun from(event: EventEntity) =
            GalleryTheme(event.name, event.primaryColorHex, event.secondaryColorHex, event.backgroundColorHex, event.textColorHex)

        // Values end up inside a <style> block, so anything but #RRGGBB is rejected.
        private fun safeHex(value: String, fallback: String) = if (Regex("#[0-9a-fA-F]{6}").matches(value)) value else fallback
    }
}

/**
 * Offline gallery on port 8080. `/session/<id>` is the mobile page (montage, every original, ZIP button),
 * `/download/<id>/{montage, raw/<n>, all.zip}` are attachments, `/health` a liveness probe.
 */
@Singleton
class LocalGalleryHttpServer(private val port: Int) {
    @Inject constructor() : this(PORT)

    class Handle(val sessionId: String, val url: String)

    private class Entry(val montage: File, val originals: List<File>, val theme: GalleryTheme)

    private val entries = object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }
    private val thumbnails = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > MAX_THUMBNAILS
    }
    private var server: Server? = null

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            server = Server().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            Log.i(TAG, "LocalGalleryHttpServer: listening on :$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "LocalGalleryHttpServer: cannot bind :$port: ${e.message}")
            server = null
            false
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    val isRunning: Boolean get() = server?.isAlive == true

    /** Publishes a validated session; null when the tablet has no LAN address to hand out. */
    fun register(sessionId: String, montage: File, originals: List<File> = emptyList(), theme: GalleryTheme = GalleryTheme.DEFAULT): Handle? {
        val ip = localIpAddress() ?: return null
        if (!start()) return null
        synchronized(entries) { entries[sessionId] = Entry(montage, originals, theme) }
        return Handle(sessionId, "http://$ip:$port/session/$sessionId")
    }

    fun unregister(sessionId: String) {
        synchronized(entries) { entries.remove(sessionId) }
    }

    private fun entry(id: String): Entry? = synchronized(entries) { entries[id] }

    private fun localIpAddress(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("eth") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress

    private inner class Server : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val parts = session.uri.trim('/').split('/')
            return when {
                parts == listOf("health") -> newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8", """{"status":"ok","port":$port}""",
                )
                parts.size >= 2 && parts[0] == "session" -> entry(parts[1])?.let { view(parts[1], it, parts.drop(2)) } ?: notFound()
                parts.size >= 3 && parts[0] == "download" -> entry(parts[1])?.let { download(it, parts.drop(2)) } ?: notFound()
                else -> notFound()
            }
        }

        private fun view(id: String, entry: Entry, rest: List<String>): Response = when {
            rest.isEmpty() -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page(id, entry))
            rest == listOf("montage.jpg") -> file(entry.montage, "image/jpeg", null)
            rest.size == 2 && rest[0] == "thumb" -> photoIndex(rest[1].removeSuffix(".jpg"), entry)?.let { thumbnail(entry.originals[it]) } ?: notFound()
            else -> notFound()
        }

        private fun download(entry: Entry, rest: List<String>): Response = when {
            rest == listOf("montage") -> file(entry.montage, "image/jpeg", "Montage_Final.jpg")
            rest == listOf("all.zip") -> zip(entry)
            rest.size == 2 && rest[0] == "raw" ->
                photoIndex(rest[1], entry)?.let { file(entry.originals[it], "image/jpeg", "Photo_${it + 1}.jpg") } ?: notFound()
            else -> notFound()
        }

        /** 1-based photo number from the URL to a list index, or null when it is not a valid number. */
        private fun photoIndex(number: String, entry: Entry): Int? =
            number.toIntOrNull()?.minus(1)?.takeIf { it in entry.originals.indices }

        private fun file(file: File, mime: String, downloadName: String?): Response {
            if (!file.isFile) return notFound()
            return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length()).apply {
                addHeader("Content-Disposition", if (downloadName != null) "attachment; filename=\"$downloadName\"" else "inline")
                addHeader("Cache-Control", "no-store")
            }
        }

        /** Montage plus every original in one archive; built into a temp file so the browser gets a Content-Length. */
        private fun zip(entry: Entry): Response {
            val temp = File.createTempFile("session-", ".zip")
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
                    out.setLevel(Deflater.NO_COMPRESSION) // JPEGs are already compressed
                    fun add(name: String, source: File) {
                        if (!source.isFile) return
                        out.putNextEntry(ZipEntry(name))
                        source.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    add("Montage_Final.jpg", entry.montage)
                    entry.originals.forEachIndexed { i, f -> add("Photo_${i + 1}.jpg", f) }
                }
            } catch (e: Exception) {
                temp.delete()
                Log.e(TAG, "LocalGalleryHttpServer: zip failed: ${e.message}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Erreur")
            }
            val body = object : FileInputStream(temp) {
                override fun close() {
                    try { super.close() } finally { temp.delete() }
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/zip", body, temp.length()).apply {
                addHeader("Content-Disposition", "attachment; filename=\"Photobooth_Session.zip\"")
                addHeader("Cache-Control", "no-store")
            }
        }

        private fun thumbnail(file: File): Response {
            if (!file.isFile) return notFound()
            val key = "${file.absolutePath}:${file.lastModified()}"
            val bytes = synchronized(thumbnails) { thumbnails[key] } ?: run {
                val source = PhotoDecoder.decodeSampled(file, THUMB_PX, THUMB_PX) ?: return notFound()
                val scale = THUMB_PX.toFloat() / maxOf(source.width, source.height)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
                val encoded = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
                if (small !== source) small.recycle()
                source.recycle()
                synchronized(thumbnails) { thumbnails[key] = encoded }
                encoded
            }
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
        }

        private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Introuvable")

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        private fun onColor(hex: String): String {
            val rgb = hex.drop(1).chunked(2).map { it.toInt(16) / 255.0 }
            return if (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2] > 0.55) "#101018" else "#ffffff"
        }

        private fun page(id: String, entry: Entry): String {
            val t = entry.theme
            val photos = entry.originals.mapIndexed { i, _ ->
                val n = i + 1
                """<div class="card"><img src="/session/$id/thumb/$n.jpg" alt="Photo $n" loading="lazy">
                    |<a class="btn small" href="/download/$id/raw/$n">Télécharger Photo #$n</a></div>""".trimMargin()
            }.joinToString("\n")
            val originals = if (photos.isEmpty()) "" else """<h2>Vos photos originales</h2><div class="grid">$photos</div>"""
            val zip = if (entry.originals.isEmpty()) "" else """<a class="btn big" href="/download/$id/all.zip">Tout télécharger (.ZIP)</a>"""
            val ready = entry.montage.isFile
            val montageBlock = if (ready) {
                """<img class="hero" src="/session/$id/montage.jpg" alt="Montage final">
                    |<a class="btn" href="/download/$id/montage">Télécharger le Montage</a>""".trimMargin()
            } else """<p class="sub">Votre montage est en cours de préparation, cette page se met à jour toute seule…</p>"""
            val refresh = if (ready) "" else """<meta http-equiv="refresh" content="4">"""
            return """<!doctype html><html lang="fr"><head><meta charset="utf-8">$refresh
                |<meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(t.eventName)}</title>
                |<style>
                |:root{--p:${t.primary};--s:${t.secondary};--bg:${t.background};--tx:${t.text};--on:${onColor(t.primary)}}
                |*{box-sizing:border-box}
                |body{margin:0;background:var(--bg);color:var(--tx);font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;text-align:center}
                |main{max-width:980px;margin:0 auto;padding:24px 16px 48px}
                |h1{font-size:2rem;margin:8px 0 2px}h2{font-size:1.2rem;margin:36px 0 14px;color:var(--s)}
                |.sub{opacity:.75;margin:0 0 18px}
                |img{display:block;width:100%;border-radius:16px;background:rgba(128,128,128,.15)}
                |img.hero{max-width:720px;margin:0 auto}
                |.btn{display:block;margin:14px auto;padding:16px 22px;max-width:440px;border-radius:999px;background:var(--p);color:var(--on);text-decoration:none;font-weight:700;font-size:1.05rem}
                |.btn.big{padding:20px 22px;font-size:1.2rem;box-shadow:0 6px 24px rgba(0,0,0,.28)}
                |.btn.small{padding:12px 16px;font-size:.95rem;margin:10px 0 0;max-width:none}
                |.btn.alt{background:transparent;color:var(--tx);border:2px solid var(--p)}
                |.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:16px}
                |.card{background:rgba(128,128,128,.12);border-radius:20px;padding:12px}
                |</style></head><body><main>
                |<h1>Merci !</h1><p class="sub">${escape(t.eventName)}</p>
                |$montageBlock
                |$zip
                |$originals
                |</main></body></html>""".trimMargin()
        }
    }

    companion object {
        const val PORT = 8080
        private const val MAX_ENTRIES = 60
        private const val MAX_THUMBNAILS = 48
        private const val THUMB_PX = 640
    }
}

object QrCodes {
    fun bitmap(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx) { i -> if (matrix.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
