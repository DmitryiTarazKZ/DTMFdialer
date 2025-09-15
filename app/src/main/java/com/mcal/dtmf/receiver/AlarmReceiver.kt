package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcal.dtmf.service.AlarmSoundService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            val serviceIntent = Intent(it, AlarmSoundService::class.java)
            serviceIntent.putExtra("ALARM_MESSAGE", intent?.getStringExtra("ALARM_MESSAGE") ?: "Будильник!")

            // Запускаем сервис как обычный фоновый сервис
            it.startService(serviceIntent)
        }
    }
}