package io.github.nofuturekid.nova

import android.content.ContentProvider
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * WHY: "with alerts off NOVA does nothing in the background" has to hold for the real app, not
 * just for the code of this change. Robolectric starts [NovaApp] with the merged manifest and runs
 * the App Startup provider as Android does at process start. If anything (the automatic
 * initializer coming back through a library update, someone calling WorkManager.getInstance at
 * app start) initialises WorkManager, the test fails. The permissions
 * checked are those of the merged manifest of the variant under test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorkManagerStartupTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        // Another test class may have installed a test WorkManager earlier in this JVM.
        WorkManagerImpl.setDelegate(null)
        ctx = ApplicationProvider.getApplicationContext()
    }

    @Test fun starting_the_app_does_not_initialise_workmanager() {
        assertTrue("the application provides WorkManager's configuration on demand", ctx is NovaApp)
        // Robolectric does not create the App Startup provider by itself. Create it from the merged
        // manifest, as Android does at process start: it runs every initializer the manifest lists.
        @Suppress("UNCHECKED_CAST")
        Robolectric.setupContentProvider(Class.forName("androidx.startup.InitializationProvider") as Class<ContentProvider>)
        assertFalse(WorkManager.isInitialized())
    }

    @Test fun the_merged_manifest_has_no_initializer_entry_and_no_foreground_service_permission() {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(
            ctx.packageName,
            PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA).toLong()),
        )
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.POST_NOTIFICATIONS" in requested)
        assertFalse("android.permission.FOREGROUND_SERVICE" in requested)

        val startup = info.providers.orEmpty().single { it.name == "androidx.startup.InitializationProvider" }
        assertNotNull(startup)
        assertNull(
            "WorkManagerInitializer must not be registered with App Startup",
            startup.metaData?.keySet()?.firstOrNull { it == "androidx.work.WorkManagerInitializer" },
        )
    }
}
