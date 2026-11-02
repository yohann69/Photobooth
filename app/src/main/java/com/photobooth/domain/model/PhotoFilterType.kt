package com.photobooth.domain.model

enum class PhotoFilterType(val label: String) {
    ORIGINAL("Original"),
    BLACK_AND_WHITE("Noir & Blanc"),
    SEPIA("Sépia"),
    VINTAGE("Vintage"),
    GLAM("Glam"),
    ;

    companion object {
        val ALL: List<PhotoFilterType> = entries.toList()
    }
}
