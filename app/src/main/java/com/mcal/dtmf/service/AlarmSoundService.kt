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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        playAlarmWithRepetitions()
        return START_NOT_STICKY
    }

    private fun playAlarmWithRepetitions() {
        serviceScope.launch {
            for (pass in 1..5) {
                playSound()
                if (pass < 5) {
                    delay(60000)
                }
            }
            stopSelf()
        }
    }

    private suspend fun playSound() {
        stopAndReleasePlayer()

        mediaPlayer = MediaPlayer.create(applicationContext, R.raw.frog)
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