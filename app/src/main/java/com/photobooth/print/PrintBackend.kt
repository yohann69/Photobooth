package com.photobooth.print

import com.photobooth.data.db.entity.PrintFinish
import com.photobooth.domain.model.LayoutType
import java.io.File

class PrintRequest(
    val file: File,
    /** Sheets still to print (never more than the job's remaining copies). */
    val copies: Int,
    val finish: PrintFinish,
    val layoutType: LayoutType?,
    val jobName: String,
)

sealed interface PrinterReadiness {
    data object Ready : PrinterReadiness
    /** Temporary: paper out, cover open, cable unplugged… jobs wait in the queue and resume on their own. */
    data class NotReady(val reason: String) : PrinterReadiness
}

sealed interface PrintResult {
    /** All requested sheets were accepted by the printer. */
    data object Success : PrintResult
    /** [printed] sheets went out before the printer stopped. */
    data class Partial(val printed: Int, val reason: String) : PrintResult
    data class Blocked(val reason: String) : PrintResult
    data class Failed(val message: String) : PrintResult
}

interface PrintBackend {
    val label: String
    suspend fun check(): PrinterReadiness

    /** Prints [request]; [onSheetPrinted] is called after each sheet the printer accepted so progress survives crashes. */
    suspend fun print(request: PrintRequest, onSheetPrinted: suspend (Int) -> Unit): PrintResult
}
