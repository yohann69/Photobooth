package com.photobooth.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.photobooth.data.db.dao.EventDao
import com.photobooth.data.db.dao.EventLayoutConfigDao
import com.photobooth.data.db.dao.PrintJobDao
import com.photobooth.data.db.dao.SessionLogDao
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.db.entity.EventLayoutConfigEntity
import com.photobooth.data.db.entity.PrintJobEntity
import com.photobooth.data.db.entity.SessionLogEntity

@Database(
    entities = [
        EventEntity::class,
        EventLayoutConfigEntity::class,
        PrintJobEntity::class,
        SessionLogEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PhotoboothDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun layoutConfigDao(): EventLayoutConfigDao
    abstract fun printJobDao(): PrintJobDao
    abstract fun sessionLogDao(): SessionLogDao

    companion object {
        const val NAME = "photobooth.db"
    }
}
