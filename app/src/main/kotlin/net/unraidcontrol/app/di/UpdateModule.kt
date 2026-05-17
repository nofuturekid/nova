package net.unraidcontrol.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.unraidcontrol.app.data.update.ApkInstaller
import net.unraidcontrol.app.data.update.UpdateInstaller
import javax.inject.Singleton

/**
 * Binds the [ApkInstaller] seam to its sole production implementation
 * [UpdateInstaller] (ADR-0030 D2). The interface exists so
 * `UpdateController`'s install state machine is unit-testable without
 * Android's `PackageInstaller`. `UpdateController` itself is a
 * constructor-injected `@Singleton`, so Hilt discovers it directly — no
 * provider needed here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    @Singleton
    abstract fun bindApkInstaller(impl: UpdateInstaller): ApkInstaller
}
