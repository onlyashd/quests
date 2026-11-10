package org.ferabrasil.quests.android

import android.app.Application
import org.ferabrasil.quests.android.platform.KeystoreSecretStore
import org.ferabrasil.quests.di.AppGraph
import org.ferabrasil.quests.di.createAppGraph

class QuestsApplication : Application() {
    /** The single shared business-logic graph; Android presentation is built on top of it. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createAppGraph(secretStore = KeystoreSecretStore(this))
    }
}
