package com.mcal.dtmf

import android.app.Application
import com.mcal.dtmf.di.AppModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AndroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AndroidApp)
            modules(AppModules.modules)
        }
    }
}
