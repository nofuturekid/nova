package io.github.nofuturekid.nova.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Repositories + stores are constructor-injected with @Singleton — Hilt
    // discovers them automatically. This module holds the bindings that
    // constructor injection cannot express (qualifiers, factories).

    /** Lives as long as the process; a failure in one job does not cancel the others. */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
