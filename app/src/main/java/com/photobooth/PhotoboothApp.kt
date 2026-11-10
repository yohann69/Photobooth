package com.photobooth

import android.app.Application
import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.seed.DatabaseSeeder
import com.photobooth.di.ApplicationScope
import com.photobooth.print.PrintController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PhotoboothApp : Application() {
    @Inject lateinit var seeder: DatabaseSeeder
    @Inject lateinit var printController: PrintController
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { seeder.seedIfNeeded() }
                .onFailure { Log.e(TAG, "DbSeeder: FAILED", it) }
            printController.start()
        }
    }
}
