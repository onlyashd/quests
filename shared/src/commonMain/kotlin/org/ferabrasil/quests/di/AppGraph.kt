package org.ferabrasil.quests.di

import com.russhwolf.settings.Settings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.ferabrasil.quests.domain.identity.IdentityRepository
import org.ferabrasil.quests.domain.identity.WipeIdentity
import org.ferabrasil.quests.domain.preferences.PreferencesRepository
import org.ferabrasil.quests.domain.sync.SyncTiming
import org.ferabrasil.quests.platform.SecretStore

@Qualifier
annotation class IoDispatcher

/**
 * Business-logic object graph. Each app creates exactly one, passing its own platform
 * implementations, and builds its stack-specific presentation layer on top.
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
    val identity: IdentityRepository
    val wipeIdentity: WipeIdentity
    val preferences: PreferencesRepository
    val syncTiming: SyncTiming

    @Provides
    @SingleIn(AppScope::class)
    fun provideSettings(): Settings = Settings()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @SingleIn(AppScope::class)
    fun provideSyncTiming(): SyncTiming = SyncTiming()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides secretStore: SecretStore): AppGraph
    }
}

/** Entry point for both apps (Swift: `AppGraphKt.createAppGraph(secretStore:)`). */
fun createAppGraph(secretStore: SecretStore): AppGraph =
    createGraphFactory<AppGraph.Factory>().create(secretStore)
