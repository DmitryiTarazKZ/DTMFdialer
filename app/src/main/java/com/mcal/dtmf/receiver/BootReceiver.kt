package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcal.dtmf.service.DtmfService
import org.koin.core.component.KoinComponent


open class BootReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> {
                    DtmfService.start(context)
                    return
                }
            }
        }
    }
}
