package com.photobooth.domain.model

/** Shape of every photo slot (except the square mosaic); pick the native ratio of the camera so nothing is cropped. */
enum class PhotoAspect(val ratio: Float, val label: String) {
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_3_2(3f / 2f, "3:2"),
    RATIO_16_9(16f / 9f, "16:9"),
}
