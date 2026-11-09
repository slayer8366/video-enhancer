package io.github.slayer8366.enhancer

import android.app.Application
import io.github.slayer8366.enhancer.jobs.BenchStore
import io.github.slayer8366.enhancer.jobs.History
import io.github.slayer8366.enhancer.jobs.SettingsStore

class EnhancerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.load(this)
        History.load(this)
        BenchStore.load(this)
    }
}
