package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcal.dtmf.data.repositories.main.MainRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class HeadsetReceiver : BroadcastReceiver(), KoinComponent {

    private val mainRepository: MainRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_HEADSET_PLUG) {
            val state = intent.getIntExtra("state", 0)
            mainRepository.setHeadset(state == 1)
        }
    }
}