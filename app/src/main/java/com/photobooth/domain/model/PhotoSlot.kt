package com.photobooth.domain.model

import kotlinx.serialization.Serializable

/**
 * Photo placement normalized (0..1) against the full canvas.
 * [rotation] is in degrees clockwise around the slot center;
 * [aspectRatio] is width/height in pixels. [sourceIndex] names the shot shown in this slot
 * (null = the slot's own index), so one shot can fill several slots.
 */
@Serializable
data class PhotoSlot(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val aspectRatio: Float,
    val sourceIndex: Int? = null,
)
