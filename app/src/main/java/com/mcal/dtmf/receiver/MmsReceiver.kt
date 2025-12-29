package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Обязательно для статуса SMS-приложения, даже если MMS не нужны
    }
}