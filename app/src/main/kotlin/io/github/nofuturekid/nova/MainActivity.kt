package io.github.nofuturekid.nova

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.nofuturekid.nova.data.alerts.NavigationRequest
import io.github.nofuturekid.nova.data.alerts.PendingNavigation
import io.github.nofuturekid.nova.ui.UnraidApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var pendingNavigation: PendingNavigation

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT),
        )
        // A recreated activity gets the intent it was first started with again; that tap is done.
        if (savedInstanceState == null) openFrom(intent)
        setContent { UnraidApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A tap on an alert notification names a server and a place (ADR-0048). */
    private fun openFrom(intent: Intent?) {
        // Relaunched from the recents list: the old extras would navigate a second time.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        NavigationRequest.from(intent)?.let(pendingNavigation::post)
    }
}
