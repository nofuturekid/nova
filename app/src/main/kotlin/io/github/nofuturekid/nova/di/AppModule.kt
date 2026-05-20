package io.github.nofuturekid.nova.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Repositories + stores are constructor-injected with @Singleton — Hilt
    // discovers them automatically. This module exists so additional bindings
    // (qualifiers, factories) can land here later without re-wiring callers.
}
