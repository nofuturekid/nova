package io.github.nofuturekid.nova

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp

/**
 * Also WorkManager's [Configuration.Provider]: WorkManager is initialised on demand, the
 * first time alerts are switched on or a scheduled check runs, never at app start. The
 * automatic initializer is removed in the manifest (ADR-0048).
 */
@HiltAndroidApp
class NovaApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
