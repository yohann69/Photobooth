package com.photobooth.print.dnp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Asymmetric 10x15 sheet (marked top-left, labelled colour bars) to check how the printer lays out the raster. */
object DnpTestSheet {
    fun render(portrait: Boolean): Bitmap {
        val w = if (portrait) 1200 else 1800
        val h = if (portrait) 1800 else 1200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            isFakeBoldText = true
            textSize = 64f
        }
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            color = Color.BLACK
        }
        c.drawRect(6f, 6f, w - 6f, h - 6f, frame)
        // Solid disc in the top-left corner only: the one asymmetric mark.
        c.drawCircle(120f, 120f, 70f, ink)
        c.drawText("HAUT / GAUCHE", 220f, 150f, text)
        // Arrow pointing up on the left edge.
        val arrow = Path().apply {
            moveTo(60f, 330f); lineTo(140f, 460f); lineTo(100f, 460f); lineTo(100f, 600f)
            lineTo(20f, 600f); lineTo(20f, 460f); lineTo(-20f, 460f); close()
        }
        c.save(); c.translate(80f, 0f); c.drawPath(arrow, ink); c.restore()

        val bars = listOf("ROUGE" to Color.RED, "VERT" to Color.rgb(0, 170, 0), "BLEU" to Color.BLUE, "NOIR" to Color.BLACK)
        val barH = h * 0.12f
        bars.forEachIndexed { i, (name, color) ->
            val top = h * 0.36f + i * (barH + 24f)
            c.drawRect(260f, top, w - 60f, top + barH, Paint().apply { this.color = color })
            c.drawText(name, 280f, top + barH * 0.7f, text.apply { this.color = if (color == Color.BLACK || color == Color.BLUE) Color.WHITE else Color.BLACK })
        }
        text.color = Color.BLACK
        text.textSize = 44f
        c.drawText("TEST DNP DS-RX1HS  ${w}x$h", 40f, h - 50f, text)
        return bitmap
    }
}
