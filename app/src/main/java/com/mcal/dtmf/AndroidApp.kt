package com.mcal.dtmf

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import com.mcal.dtmf.di.AppModules
import com.mcal.dtmf.receiver.HeadsetReceiver
import com.mcal.dtmf.receiver.PowerReceiver
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AndroidApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Инициализируем Koin
        startKoin {
            androidContext(this@AndroidApp)
            modules(AppModules.modules)
        }

        // 2. Регистрируем ресиверы.
        // Теперь они будут работать всегда, пока живо приложение.
        registerSystemReceivers()
    }

    private fun registerSystemReceivers() {
        // Регистрация наушников
        registerReceiver(
            HeadsetReceiver(),
            IntentFilter(Intent.ACTION_HEADSET_PLUG)
        )

        // Регистрация питания
        registerReceiver(
            PowerReceiver(),
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }
}