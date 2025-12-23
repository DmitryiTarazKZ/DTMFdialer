package com.mcal.dtmf.scheduler

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.mcal.dtmf.receiver.AlarmReceiver
import com.mcal.dtmf.service.AlarmSoundService
import java.util.Calendar

class AlarmScheduler(
    private val context: Application
) {
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    private lateinit var alarmPendingIntent: PendingIntent

    private val ALARM_REQUEST_CODE = 1001

    fun setAlarm(hours: Int, minutes: Int, period: Long, part: Long) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, get(Calendar.SECOND))
            set(Calendar.MILLISECOND, 0)

            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            // Добавляем period в Intent
            putExtra("ALARM_PERIOD", period)
            putExtra("ALARM_PART", part)
        }

        alarmPendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                scheduleExactAlarm(calendar.timeInMillis)
            } else {
                val settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            }
        } else {
            scheduleExactAlarm(calendar.timeInMillis)
        }
    }

    private fun scheduleExactAlarm(timeInMillis: Long) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            timeInMillis,
            alarmPendingIntent
        )
    }

    fun stopAlarm() {
        if (::alarmPendingIntent.isInitialized) {
            // Отменяем будущие запуски
            alarmManager.cancel(alarmPendingIntent)
            // Явно останавливаем уже запущенный сервис
            val serviceIntent = Intent(context, AlarmSoundService::class.java)
            context.stopService(serviceIntent)
        }
    }
}