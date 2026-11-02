package com.photobooth.data.db

import androidx.room.TypeConverter
import com.photobooth.domain.model.PhotoFilterType
import com.photobooth.domain.model.PhotoSlot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun slotsToJson(slots: List<PhotoSlot>): String = SlotJson.encode(slots)

    @TypeConverter
    fun jsonToSlots(value: String): List<PhotoSlot> = SlotJson.decode(value)

    @TypeConverter
    fun filtersToString(filters: List<PhotoFilterType>): String = filters.joinToString(",") { it.name }

    @TypeConverter
    fun stringToFilters(value: String): List<PhotoFilterType> =
        value.split(',').mapNotNull { name -> PhotoFilterType.entries.firstOrNull { it.name == name.trim() } }
}

// Kept out of Converters: KSP cannot resolve the plugin-generated serializer() in member signatures.
private object SlotJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(slots: List<PhotoSlot>): String =
        json.encodeToString(ListSerializer(PhotoSlot.serializer()), slots)

    fun decode(value: String): List<PhotoSlot> =
        json.decodeFromString(ListSerializer(PhotoSlot.serializer()), value)
}
