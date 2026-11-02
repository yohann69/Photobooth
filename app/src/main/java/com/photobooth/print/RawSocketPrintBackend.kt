package com.photobooth.print

import android.content.Context
import com.photobooth.camera.ccapi.CcapiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** JetDirect-style raw socket (port 9100): streams the JPEG once per sheet, persisting progress after each. */
class RawSocketPrintBackend(
    private val context: Context,
    private val host: String,
    private val port: Int,
) : PrintBackend {
    override val label = "RAW $host:$port"

    override suspend fun check(): PrinterReadiness = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext PrinterReadiness.NotReady("Adresse IP de l'imprimante non configurée")
        runCatching { connect().use { } }.fold(
            onSuccess = { PrinterReadiness.Ready },
            onFailure = { PrinterReadiness.NotReady("Imprimante injoignable ($host:$port): ${it.message}") },
        )
    }

    override suspend fun print(request: PrintRequest, onSheetPrinted: suspend (Int) -> Unit): PrintResult = withContext(Dispatchers.IO) {
        var printed = 0
        repeat(request.copies) {
            try {
                connect().use { socket ->
                    FileInputStream(request.file).use { it.copyTo(socket.getOutputStream()) }
                    socket.getOutputStream().flush()
                }
            } catch (e: IOException) {
                val reason = "Connexion perdue: ${e.message}"
                return@withContext if (printed > 0) PrintResult.Partial(printed, reason) else PrintResult.Blocked(reason)
            }
            printed++
            onSheetPrinted(1)
        }
        PrintResult.Success
    }

    private fun connect(): Socket {
        val socket = Socket()
        CcapiClient.findLocalNetwork(context)?.bindSocket(socket)
        socket.soTimeout = 10_000
        socket.connect(InetSocketAddress(host, port), 3_000)
        return socket
    }
}
