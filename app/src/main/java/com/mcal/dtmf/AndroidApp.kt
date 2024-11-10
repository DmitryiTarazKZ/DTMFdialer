package com.mcal.dtmf

import android.app.Application
import cafe.adriel.voyager.core.registry.ScreenRegistry
import com.mcal.dtmf.di.AppModules
import com.mcal.dtmf.navigator.helpScreenModule
import com.mcal.dtmf.navigator.mainScreenModule
import com.mcal.dtmf.navigator.preferencesScreenModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AndroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AndroidApp)
            val featureModules = listOf(
                AppModules.modules,
            ).flatten()
            modules(featureModules)
        }
        ScreenRegistry {
            mainScreenModule()
            preferencesScreenModule()
            helpScreenModule()
        }
    }
}
