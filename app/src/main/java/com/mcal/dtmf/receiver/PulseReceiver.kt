package com.mcal.dtmf.receiver

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class PulseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {

        // 1. Просто поднимаем экран
        val activityIntent = Intent(context, com.mcal.dtmf.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(activityIntent)

        // 2. Ставим следующий через минуту
        scheduleNextPulse(context)
    }

    companion object {
        private const val REQUEST_CODE = 12345
        private const val INTERVAL = 30 * 60 * 1000L // 60 * 1000L1 минута

        @SuppressLint("ScheduleExactAlarm")
        fun scheduleNextPulse(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PulseReceiver::class.java)

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
            val triggerAt = System.currentTimeMillis() + INTERVAL

            // Прямая установка без лишних условий
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, pendingIntent)
                alarmManager.setAlarmClock(alarmInfo, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }
}