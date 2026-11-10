package com.photobooth.print

import com.photobooth.data.db.entity.PrintFinish
import com.photobooth.print.dnp.DnpOrientation
import com.photobooth.print.dnp.DnpPrinterLink
import com.photobooth.print.dnp.DnpProtocol

/** USB printing on the DNP DS-RX1HS. */
class DnpUsbBackend(
    private val link: DnpPrinterLink,
    private val orientation: DnpOrientation,
) : PrintBackend {
    override val label = "USB DNP DS-RX1HS"

    override suspend fun check(): PrinterReadiness {
        val t = link.refresh(askPermission = true).getOrElse { return PrinterReadiness.NotReady(it.message ?: "Imprimante USB indisponible") }
        return when {
            DnpProtocol.isFault(t.statusCode) -> PrinterReadiness.NotReady(t.statusText)
            t.remaining == 0 -> PrinterReadiness.NotReady("Plus de papier")
            t.mediaCode != null && !DnpProtocol.supportsSheet(t.mediaCode) ->
                PrinterReadiness.NotReady("Papier chargé (${t.mediaName}) incompatible avec le 10x15")
            else -> PrinterReadiness.Ready
        }
    }

    override suspend fun print(request: PrintRequest, onSheetPrinted: suspend (Int) -> Unit): PrintResult =
        link.print(request.file, request.copies, request.finish == PrintFinish.MATTE, orientation, onSheetPrinted)
}
