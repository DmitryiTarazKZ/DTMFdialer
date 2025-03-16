package com.mcal.dtmf.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mcal.dtmf.R
import com.mcal.dtmf.data.repositories.main.MainRepository
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

class DtmfService : Service(), KoinComponent {
    private val mainRepository: MainRepository by inject()

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_CALL_START -> startCall()
            ACTION_CALL_ANSWER -> answerCall()
            ACTION_CALL_END -> endCall()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    private fun startCall() {
        var slot1 = ""
        var slot2 = ""

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subscriptions = subscriptionManager.activeSubscriptionInfoList
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandles = telecomManager.callCapablePhoneAccounts
        val phoneAccountCount = phoneAccountHandles.size

        if (subscriptions == null || subscriptions.isEmpty()) {
            mainRepository.speakText("В устройстве нет ни одной действующей сим карты. Установите сим карты", false)
            mainRepository.setInput("")
            return
        }

        // Функция конвертации имени оператора
        fun getOperatorName(slot: String?): String {
            return when (slot) {
                "ACTIV" -> "Актив"
                "ALTEL" -> "Алтэл"
                "MTS" -> "МТС"
                "Beeline KZ" -> "Билайн"
                "Megafon" -> "Мегафон"
                "Tele2" -> "Теле2"
                else -> slot ?: "Нет сигнала"
            }
        }

        subscriptions.forEach { subscription ->
            val simSlotIndex = subscription.simSlotIndex + 1
            val simCarrierName = getOperatorName(subscription.carrierName?.toString())

            when (simSlotIndex) {
                1 -> slot1 = simCarrierName
                2 -> slot2 = simCarrierName
            }
        }

        if (slot1 == "Нет сигнала" && slot2 == "Нет сигнала") {
            mainRepository.speakText("Отсутствует сигнал сотовой сети. Вызов не возможен", false)
            mainRepository.setInput("")
        } else {
            if (phoneAccountCount > 1 && (slot1 != "Нет сигнала" && slot2 != "Нет сигнала")) {
                mainRepository.speakText("Выберите с какой сим карты выполнить вызов", false)
                if (mainRepository.getSim() == 0 || mainRepository.getSim() == 1) {
                    mainRepository.speakText("Звоню с  ${if (mainRepository.getSim() == 0) slot1 else slot2}", false)
                    Handler(Looper.getMainLooper()).postDelayed({
                    mainRepository.getInput()?.let { phone ->
                        if (phone.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_CALL).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                data = Uri.parse("tel:$phone")
                                putExtra(
                                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                                    phoneAccountHandles[mainRepository.getSim()]
                                )
                            }
                            startActivity(intent)
                        } else mainRepository.speakText("Предотвращен вызов с пустым номером", false)
                    }
                    }, 5000) // Задержка в 5000 миллисекунд (5 секунд)
                }
            } else {

                if (slot1 == "Нет сигнала") {
                    mainRepository.speakText("Звоню с $slot2}", false)
                }

                if (slot2 == "Нет сигнала") {
                    mainRepository.speakText("Звоню с $slot1}", false)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    mainRepository.getInput()?.let { phone ->
                        if (phone.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_CALL).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                data = Uri.parse("tel:$phone")
                                putExtra(
                                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                                    phoneAccountHandles[0]
                                )
                            }
                            startActivity(intent)
                        } else mainRepository.speakText("Предотвращен вызов с пустым номером", false)
                    }
                }, 5000) // Задержка в 5000 миллисекунд (5 секунд)
            }
        }
    }

    private fun endCall() {
       mainRepository.getCall()!!.disconnect()
    }

    private fun answerCall() {
        mainRepository.getCall()!!.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    private fun start() {
        updateForegroundNotification()
    }

    private fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateForegroundNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_LOW // Устанавливаем низкий приоритет
            )
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DTMF Телефон")
            .setContentText("Распознавание DTMF работает")
            .setPriority(NotificationCompat.PRIORITY_LOW) // Устанавливаем низкий приоритет
            .build()
        startForeground(1, notification)
    }

    companion object {
        internal const val ACTION_CALL_START = "call_start"
        internal const val ACTION_CALL_END = "call_end"
        internal const val ACTION_CALL_ANSWER = "call_answer"
        internal const val ACTION_START = "start"
        internal const val ACTION_STOP = "stop"
        const val NOTIFICATION_CHANNEL_ID = "DTMF"

        fun callStart(context: Context) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_CALL_START)
            context.startService(intent)
        }

        fun callEnd(context: Context) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_CALL_END)
            context.startService(intent)
        }

        fun callAnswer(context: Context) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_CALL_ANSWER)
            context.startService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_START)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

