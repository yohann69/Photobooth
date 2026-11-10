package com.photobooth.feature.booth

import android.graphics.Bitmap
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.domain.model.LayoutType
import java.io.File

enum class BoothStep { ATTRACT, SELECT_LAYOUT, LIVE, COUNTDOWN, CAPTURING, REVIEW, FINAL, PRINTING }

class ShareUi(val url: String, val qr: Bitmap)

data class BoothUiState(
    val step: BoothStep = BoothStep.ATTRACT,
    val event: EventEntity? = null,
    val cameraReady: Boolean = false,
    val rotationDegrees: Int = 0,
    val slideshowEnabled: Boolean = true,
    val slideshow: List<File> = emptyList(),

    val layouts: List<EventLayoutConfigEntity> = emptyList(),
    val layoutPreviews: Map<LayoutType, Bitmap> = emptyMap(),
    val layout: EventLayoutConfigEntity? = null,

    val photos: List<File?> = emptyList(),
    val slotIndex: Int = 0,
    val countdown: Int = 0,
    val smile: Boolean = false,
    val reviewFile: File? = null,
    /** Final composition with the new photo already in its slot; shown instead of the raw photo while reviewing. */
    val reviewPreview: Bitmap? = null,

    val copies: Int = 1,
    val maxCopies: Int = 5,
    val finalPreview: Bitmap? = null,
    val isRendering: Boolean = false,

    val share: ShareUi? = null,
    val shareLoading: Boolean = false,
    val shareFailed: Boolean = false,
    val thanksSecondsLeft: Int = 15,
    val printWaiting: Boolean = false,
    val message: String? = null,
) {
    val totalSlots: Int get() = layout?.photoCount ?: 0
}

sealed interface BoothIntent {
    data object StartSession : BoothIntent
    data class SelectLayout(val type: LayoutType) : BoothIntent
    data object TakePhoto : BoothIntent
    data object KeepPhoto : BoothIntent
    data object RetakePhoto : BoothIntent
    data class SetCopies(val copies: Int) : BoothIntent
    data object ConfirmPrint : BoothIntent
    data object BackToStart : BoothIntent
    data object Abort : BoothIntent
}

fun LayoutType.frenchTitle(): String = when (this) {
    LayoutType.LANDSCAPE_SINGLE -> "Paysage Seul"
    LayoutType.PORTRAIT_SINGLE -> "Portrait Seul"
    LayoutType.LANDSCAPE_DOUBLE -> "Paysage Duo"
    LayoutType.PORTRAIT_TRIPLE -> "Portrait Trio"
    LayoutType.GRID_2X2 -> "Grille 4 Photos"
    LayoutType.MOSAIC_6_SQUARE -> "Mosaïque 6 Carrés"
}
