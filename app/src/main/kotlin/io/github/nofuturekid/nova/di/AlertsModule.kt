package io.github.nofuturekid.nova.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nofuturekid.nova.data.alerts.AlertStateStore
import io.github.nofuturekid.nova.data.alerts.ApolloServerProbe
import io.github.nofuturekid.nova.data.alerts.ServerProbe
import io.github.nofuturekid.nova.data.alerts.SettingsAlertStateStore

/** The production implementations behind the interfaces of the alert check. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlertsModule {
    @Binds abstract fun serverProbe(impl: ApolloServerProbe): ServerProbe
    @Binds abstract fun alertStateStore(impl: SettingsAlertStateStore): AlertStateStore
}
