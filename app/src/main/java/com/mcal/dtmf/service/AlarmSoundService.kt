package com.mcal.dtmf.service

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.IBinder
import com.mcal.dtmf.R
import kotlinx.coroutines.*

class AlarmSoundService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var alarmPeriod: Long = 60000L
    private var alarmPart: Long = 5L


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alarmPeriod = intent?.getLongExtra("ALARM_PERIOD", 60000L) ?: 60000L
        alarmPart = intent?.getLongExtra("ALARM_PART", 5L) ?: 5L
        playAlarmWithRepetitions()
        return START_NOT_STICKY
    }

    private fun playAlarmWithRepetitions() {
        serviceScope.launch {
            for (pass in 1..alarmPart) {
                if (pass < alarmPart) delay(alarmPeriod)
                playSound()
            }
            stopSelf()
        }
    }

    private suspend fun playSound() {
        stopAndReleasePlayer()

        // Выбираем звуковой файл в зависимости от значения alarmPeriod
        val soundResId = if (alarmPeriod == 60000L) {
            R.raw.frog
        } else {
            R.raw.alarm
        }

        mediaPlayer = MediaPlayer.create(applicationContext, soundResId)
        mediaPlayer?.isLooping = false
        mediaPlayer?.start()

        val duration = mediaPlayer?.duration?.toLong() ?: 3000L
        delay(duration)
    }

    private fun stopAndReleasePlayer() {
        synchronized(this) {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            mediaPlayer = null
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopAndReleasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}