package com.photobooth.print.dnp

/**
 * DNP DS-series wire format (DS-RX1HS): every command is a 32-byte header (ESC 'P', 6-char group, 16-char name,
 * 8-digit payload length, all space padded) followed by an optional payload. Queries answer with an 8-digit
 * ASCII length and then that many bytes.
 */
internal object DnpProtocol {
    const val VENDOR_ID = 0x1343
    const val NATIVE_WIDTH = 1920
    /** Raster rows of a 6x4 in print at 300 dpi, including the printer's overscan. */
    const val ROWS_6X4 = 1240
    const val MULTICUT_6X4 = 2
    const val PIXELS_PER_METER_300_DPI = 11808
    const val BMP_BLOCK_BYTES = 1088
    const val HEADER_BYTES = 32

    fun header(group: String, name: String, payloadLength: Int = 0): ByteArray {
        val b = ByteArray(HEADER_BYTES) { 0x20 }
        b[0] = 0x1B
        b[1] = 'P'.code.toByte()
        group.take(6).forEachIndexed { i, c -> b[2 + i] = c.code.toByte() }
        name.take(16).forEachIndexed { i, c -> b[8 + i] = c.code.toByte() }
        if (payloadLength > 0) "%08d".format(payloadLength).forEachIndexed { i, c -> b[24 + i] = c.code.toByte() }
        return b
    }

    /** First integer found at or after [offset] in a reply, or null. */
    fun numberAt(reply: String, offset: Int): Int? =
        Regex("\\d+").find(reply.drop(offset))?.value?.toIntOrNull()

    fun mediaName(code: Int): String = when (code) {
        200 -> "5x3.5 (L)"
        210 -> "5x7 (2L)"
        300 -> "10x15 (4x6)"
        310 -> "15x20 (6x8)"
        400 -> "15x23 (6x9)"
        else -> "code $code"
    }

    /** Media codes the 10x15 print can be made on. */
    fun supportsSheet(mediaCode: Int) = mediaCode == 300 || mediaCode == 310 || mediaCode == 400

    fun statusText(code: Int): String = when (code) {
        0 -> "Prête"
        1 -> "Impression en cours"
        500 -> "Refroidissement de la tête"
        510 -> "Refroidissement du moteur"
        900 -> "En veille"
        1000 -> "Capot ouvert"
        1010 -> "Bac de récupération absent"
        1100 -> "Plus de papier"
        1200 -> "Plus de ruban"
        1300 -> "Bourrage papier"
        1400 -> "Erreur de ruban"
        1500 -> "Erreur de définition du papier"
        1600 -> "Erreur de données"
        2000 -> "Erreur de tension de la tête"
        2010 -> "Erreur d'alimentation USB"
        2100 -> "Erreur de position de la tête"
        2200 -> "Erreur de ventilateur"
        2300 -> "Erreur de coupe"
        2400 -> "Erreur de rouleau presseur"
        2500 -> "Température de tête anormale"
        2600 -> "Température du papier anormale"
        2610 -> "Température du moteur anormale"
        2700 -> "Erreur de tension du ruban"
        2800 -> "Erreur du module RF-ID"
        3000 -> "Erreur système"
        else -> "État inconnu ($code)"
    }

    /** Codes that stop printing until someone intervenes. */
    fun isFault(code: Int) = code >= 1000
    fun isBusyButFine(code: Int) = code == 500 || code == 510
}
