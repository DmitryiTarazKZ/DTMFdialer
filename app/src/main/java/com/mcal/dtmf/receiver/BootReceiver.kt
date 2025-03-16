package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcal.dtmf.MainActivity
import org.koin.core.component.KoinComponent

open class BootReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> {
                    val mainActivityIntent = Intent(context, MainActivity::class.java)
                    mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(mainActivityIntent)
                    return
                }
            }
        }
    }
}
