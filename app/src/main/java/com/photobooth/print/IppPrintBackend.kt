package com.photobooth.print

import android.content.Context
import com.photobooth.camera.ccapi.CcapiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Silent IPP printing straight to a printer or CUPS queue: no Android print service, no dialog. */
class IppPrintBackend(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val path: String,
) : PrintBackend {
    override val label = "IPP $host:$port$path"

    private val requestIds = AtomicInteger(1)
    private val printerUri get() = "ipp://$host:$port$path"

    override suspend fun check(): PrinterReadiness = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext PrinterReadiness.NotReady("Adresse IP de l'imprimante non configurée")
        val response = try {
            post(IppCodec.getPrinterAttributesRequest(nextId(), printerUri, listOf("printer-state", "printer-state-reasons")), null, 4_000)
        } catch (e: IOException) {
            return@withContext PrinterReadiness.NotReady("Imprimante injoignable ($host:$port)")
        }
        if (!response.isSuccess) return@withContext PrinterReadiness.NotReady("Erreur IPP 0x${response.statusCode.toString(16)}")
        blockingReason(response)?.let { PrinterReadiness.NotReady(it) } ?: PrinterReadiness.Ready
    }

    override suspend fun print(request: PrintRequest, onSheetPrinted: suspend (Int) -> Unit): PrintResult = withContext(Dispatchers.IO) {
        val ipp = IppCodec.printJobRequest(
            requestId = nextId(),
            printerUri = printerUri,
            jobName = request.jobName,
            user = "photobooth",
            mime = "image/jpeg",
            copies = request.copies,
            media = "na_index-4x6_4x6in",
        )
        val response = try {
            post(ipp, request.file, 60_000)
        } catch (e: IOException) {
            return@withContext PrintResult.Blocked("Connexion perdue pendant l'envoi: ${e.message}")
        }
        when {
            response.isSuccess -> {
                onSheetPrinted(request.copies)
                PrintResult.Success
            }
            blockingReason(response) != null -> PrintResult.Blocked(blockingReason(response)!!)
            else -> PrintResult.Failed("IPP 0x${response.statusCode.toString(16)} ${response.texts("status-message").firstOrNull().orEmpty()}")
        }
    }

    private fun blockingReason(response: IppCodec.Response): String? {
        val reasons = response.texts("printer-state-reasons")
        val message = when {
            reasons.any { it.startsWith("media-empty") || it.startsWith("media-needed") } -> "Plus de papier"
            reasons.any { it.startsWith("media-jam") } -> "Bourrage papier"
            reasons.any { it.startsWith("door-open") || it.startsWith("cover-open") } -> "Capot ouvert"
            reasons.any { it.startsWith("marker-supply-empty") || it.startsWith("toner-empty") } -> "Consommable épuisé"
            response.int("printer-state") == IppCodec.STATE_STOPPED -> "Imprimante arrêtée"
            else -> null
        }
        return message
    }

    private fun nextId() = requestIds.getAndIncrement()

    private fun post(ipp: ByteArray, document: java.io.File?, readTimeoutMs: Int): IppCodec.Response {
        val url = URL("http://$host:$port$path")
        val network = CcapiClient.findLocalNetwork(context)
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.setFixedLengthStreamingMode(ipp.size + (document?.length() ?: 0L))
            conn.outputStream.use { out ->
                out.write(ipp)
                document?.let { f -> FileInputStream(f).use { it.copyTo(out) } }
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return IppCodec.parseResponse(conn.inputStream.use { it.readBytes() })
        } catch (e: SocketTimeoutException) {
            throw IOException("Délai dépassé", e)
        } finally {
            conn.disconnect()
        }
    }
}
