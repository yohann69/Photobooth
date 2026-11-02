package com.photobooth.feature.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.max
import kotlin.math.min

/** Draws live view frames straight onto the hardware canvas; rotation/mirroring is applied in the draw matrix. */
@Composable
fun LiveViewSurface(
    frames: SharedFlow<Bitmap>,
    rotationDegrees: Int,
    mirror: Boolean,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    var frame by remember { mutableStateOf<Bitmap?>(null, neverEqualPolicy()) }
    LaunchedEffect(frames) { frames.collect { frame = it } }

    val matrix = remember { Matrix() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }

    Canvas(modifier.background(Color.Black)) {
        val bitmap = frame ?: return@Canvas
        if (bitmap.isRecycled) return@Canvas
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val swapped = rotationDegrees % 180 != 0
        val contentW = if (swapped) bh else bw
        val contentH = if (swapped) bw else bh
        val scale = if (fill) max(size.width / contentW, size.height / contentH) else min(size.width / contentW, size.height / contentH)

        matrix.reset()
        matrix.postTranslate(-bw / 2f, -bh / 2f)
        matrix.postRotate(rotationDegrees.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(size.width / 2f, size.height / 2f)
        drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, matrix, paint) }
    }
}
