package com.photobooth.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.photobooth.data.db.PhotoboothDatabase
import com.photobooth.data.db.MIGRATION_1_2
import com.photobooth.data.db.MIGRATION_2_3
import com.photobooth.data.db.MIGRATION_3_4
import com.photobooth.data.db.MIGRATION_4_5
import com.photobooth.data.db.dao.EventDao
import com.photobooth.data.db.dao.EventLayoutConfigDao
import com.photobooth.data.db.dao.PrintJobDao
import com.photobooth.data.db.dao.SessionLogDao
import com.photobooth.data.settings.settingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @Singleton @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PhotoboothDatabase =
        Room.databaseBuilder(context, PhotoboothDatabase::class.java, PhotoboothDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides fun eventDao(db: PhotoboothDatabase): EventDao = db.eventDao()
    @Provides fun layoutConfigDao(db: PhotoboothDatabase): EventLayoutConfigDao = db.layoutConfigDao()
    @Provides fun printJobDao(db: PhotoboothDatabase): PrintJobDao = db.printJobDao()
    @Provides fun sessionLogDao(db: PhotoboothDatabase): SessionLogDao = db.sessionLogDao()

    @Provides @Singleton
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore
}
