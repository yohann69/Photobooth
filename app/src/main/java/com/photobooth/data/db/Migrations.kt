package com.photobooth.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE print_jobs ADD COLUMN layoutType TEXT")
        db.execSQL("ALTER TABLE print_jobs ADD COLUMN finish TEXT NOT NULL DEFAULT 'GLOSSY'")
        db.execSQL("ALTER TABLE print_jobs ADD COLUMN copiesPrinted INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("slotPaddingPx", "slotOffsetXPx", "slotOffsetYPx").forEach { column ->
            db.execSQL("ALTER TABLE event_layout_configs ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/**
 * 10x15-only layouts, event art direction and per-event filters. Layout configs are rebuilt from the new
 * presets (their enum names and slot geometry no longer exist), so frames attached to old layouts are dropped.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS event_layout_configs")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_layout_configs` (`eventId` TEXT NOT NULL, `layoutType` TEXT NOT NULL, " +
                "`isEnabled` INTEGER NOT NULL, `orientation` TEXT NOT NULL, `customFramePngPath` TEXT, " +
                "`backgroundImagePath` TEXT, `backgroundColorHex` TEXT NOT NULL, `canvasWidthPx` INTEGER NOT NULL, " +
                "`canvasHeightPx` INTEGER NOT NULL, `photoSlots` TEXT NOT NULL, " +
                "`slotPaddingPx` INTEGER NOT NULL DEFAULT 0, `slotOffsetXPx` INTEGER NOT NULL DEFAULT 0, " +
                "`slotOffsetYPx` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`eventId`, `layoutType`), " +
                "FOREIGN KEY(`eventId`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("ALTER TABLE events ADD COLUMN primaryColorHex TEXT NOT NULL DEFAULT '#FF2E7E'")
        db.execSQL("ALTER TABLE events ADD COLUMN secondaryColorHex TEXT NOT NULL DEFAULT '#00E5FF'")
        db.execSQL("ALTER TABLE events ADD COLUMN backgroundColorHex TEXT NOT NULL DEFAULT '#07070D'")
        db.execSQL("ALTER TABLE events ADD COLUMN textColorHex TEXT NOT NULL DEFAULT '#FFFFFF'")
        db.execSQL("ALTER TABLE events ADD COLUMN customBackgroundImagePath TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN enabledFilters TEXT NOT NULL DEFAULT 'ORIGINAL,BLACK_AND_WHITE,SEPIA,VINTAGE,GLAM'")
        val current = "'LANDSCAPE_SINGLE','PORTRAIT_SINGLE','LANDSCAPE_DOUBLE','PORTRAIT_DOUBLE','LANDSCAPE_TRIPLE'," +
            "'PORTRAIT_TRIPLE','GRID_2X2','GRID_3X3_SQUARE'"
        db.execSQL("UPDATE print_jobs SET layoutType = NULL WHERE layoutType NOT IN ($current)")
        db.execSQL("UPDATE session_logs SET layoutType = NULL WHERE layoutType NOT IN ($current)")
    }
}

/** Layouts now follow the event's photo ratio and the layout set changed, so layout configs are regenerated. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM event_layout_configs")
        db.execSQL("ALTER TABLE events ADD COLUMN photoAspect TEXT NOT NULL DEFAULT 'RATIO_4_3'")
        val current = "'LANDSCAPE_SINGLE','PORTRAIT_SINGLE','LANDSCAPE_DOUBLE','PORTRAIT_TRIPLE','GRID_2X2','MOSAIC_6_SQUARE'"
        db.execSQL("UPDATE print_jobs SET layoutType = NULL WHERE layoutType NOT IN ($current)")
        db.execSQL("UPDATE session_logs SET layoutType = NULL WHERE layoutType NOT IN ($current)")
    }
}
