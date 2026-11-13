package io.github.nofuturekid.nova.di

import javax.inject.Qualifier

/**
 * The application-wide CoroutineScope, provided by [AppModule]. Work that must
 * outlive the screen that asked for it (a ViewModel's scope is cancelled when
 * its screen is left) runs here.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
