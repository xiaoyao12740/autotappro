package com.aitap.autotappro

import android.app.Application
import com.aitap.autotappro.core.ClickerCoordinator

class AppContext : Application() {
    companion object {
        @Volatile
        private var instance: AppContext? = null
        fun get(): AppContext? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ClickerCoordinator.initialize(this)
    }
}
