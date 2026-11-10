package com.photobooth.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
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
    version = 9,
    autoMigrations = [
        AutoMigration(from = 7, to = 8, spec = DropEventFilters::class),
        AutoMigration(from = 8, to = 9, spec = DropEventThemes::class),
    ],
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

/** Photo filters were removed from the app. */
@DeleteColumn(tableName = "events", columnName = "enabledFilters")
class DropEventFilters : AutoMigrationSpec

/** Per-event colours and background image were removed: every event shares the booth's dark look. */
@DeleteColumn.Entries(
    DeleteColumn(tableName = "events", columnName = "primaryColorHex"),
    DeleteColumn(tableName = "events", columnName = "secondaryColorHex"),
    DeleteColumn(tableName = "events", columnName = "backgroundColorHex"),
    DeleteColumn(tableName = "events", columnName = "textColorHex"),
    DeleteColumn(tableName = "events", columnName = "customBackgroundImagePath"),
)
class DropEventThemes : AutoMigrationSpec
