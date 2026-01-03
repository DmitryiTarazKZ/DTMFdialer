package com.mcal.dtmf.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mcal.dtmf.R
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.receiver.PulseReceiver
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import java.text.SimpleDateFormat
import java.util.*

class DtmfService : Service(), KoinComponent {
    private val mainRepository: MainRepository by inject()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DtmfService:WakeLockTag")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Если intent == null, значит сервис был убит системой и теперь восстановлен
        if (intent == null) {
            mainRepository.incrementSystemCount() // Фиксируем системный сбой
            mainRepository.startDtmf()           // Запускаем логику
            PulseReceiver.scheduleNextPulse(this)
            updateForegroundNotification() // ДОБАВИТЬ СЮДА
        } else {
            // Обычная обработка экшенов
            when (intent.action) {
                ACTION_START -> start()
                ACTION_CALL_START -> startCall()
                ACTION_CALL_ANSWER -> answerCall()
                ACTION_CALL_END -> endCall()
                ACTION_STOP -> stop()
            }
        }
        return START_STICKY
    }

   // Выводим уведомление
   private fun updateForegroundNotification() {
       val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

       // 1. Создаем канал уведомлений (для Android 8.0 и выше)
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
           val channel = NotificationChannel(
               NOTIFICATION_CHANNEL_ID,
               "DTMF Service Channel",
               NotificationManager.IMPORTANCE_LOW // Низкий приоритет, чтобы не пиликало при каждом обновлении
           )
           channel.description = "Канал для работы службы распознавания DTMF"
           notificationManager.createNotificationChannel(channel)
       }

       // 2. Создаем Intent для открытия MainActivity при нажатии на уведомление
       val notificationIntent = Intent(this, com.mcal.dtmf.MainActivity::class.java).apply {
           flags = Intent.FLAG_ACTIVITY_SINGLE_TOP // Чтобы не плодить копии экрана
       }

       // Флаг FLAG_IMMUTABLE обязателен для Android 12+
       val pendingIntent = PendingIntent.getActivity(
           this,
           0,
           notificationIntent,
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
               PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
           } else {
               PendingIntent.FLAG_UPDATE_CURRENT
           }
       )

       // 3. Собираем само уведомление
       val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
           .setSmallIcon(R.mipmap.ic_launcher)
           .setContentTitle("DTMF Телефон")
           .setContentText("Распознавание DTMF работает")
           .setPriority(NotificationCompat.PRIORITY_LOW)
           .setCategory(NotificationCompat.CATEGORY_SERVICE)
           .setContentIntent(pendingIntent) // Привязываем клик к открытию Activity
           .setOngoing(true) // Делаем уведомление несмахиваемым
           .build()

       // 4. Запускаем сервис в режиме Foreground (обязательно для доступа к микрофону)
       startForeground(1, notification)
   }

    private fun startCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subscriptions = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandles = telecomManager.callCapablePhoneAccounts

        if (subscriptions.isEmpty()) {
            mainRepository.speakText("В устройстве нет ни одной действующей сим карты. Установите сим карты")
            mainRepository.setInput("")
            return
        }

        fun getOperatorName(name: String?) = when (name) {
            "ACTIV" -> "Актив"
            "ALTEL" -> "Алтэл"
            "MTS" -> "МТС"
            "Beeline KZ" -> "Билайн"
            "Megafon" -> "Мегафон"
            "Tele2" -> "Теле2"
            else -> name ?: "Нет сигнала"
        }

        var slot1 = "Нет сигнала"
        var slot2 = "Нет сигнала"
        subscriptions.forEach {
            if (it.simSlotIndex == 0) slot1 = getOperatorName(it.carrierName?.toString())
            if (it.simSlotIndex == 1) slot2 = getOperatorName(it.carrierName?.toString())
        }

        if (slot1 == "Нет сигнала" && slot2 == "Нет сигнала") {
            mainRepository.speakText("Отсутствует сигнал сотовой сети. Вызов не возможен")
            mainRepository.setInput("")
            return
        }

        val simIndex = mainRepository.getSim()
        if (phoneAccountHandles.size > 1 && slot1 != "Нет сигнала" && slot2 != "Нет сигнала") {
            if (simIndex == 5) {
                mainRepository.speakText("Выберите с какой сим карты выполнить вызов")
                return
            }
            mainRepository.speakText("Звоню с ${if (simIndex == 0) slot1 else slot2}")
        } else {
            mainRepository.speakText("Звоню с ${if (slot1 != "Нет сигнала") slot1 else slot2}")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            mainRepository.getInput()?.let { phone ->
                if (phone.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_CALL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        data = Uri.parse("tel:$phone")
                        val handle = if (simIndex in 0..1 && simIndex < phoneAccountHandles.size)
                            phoneAccountHandles[simIndex] else phoneAccountHandles.getOrNull(0)
                        putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    }
                    startActivity(intent)
                } else mainRepository.speakText("Предотвращен вызов с пустым номером")
            }
        }, 5000)
    }

    private fun endCall() { mainRepository.getCall()?.disconnect() }
    private fun answerCall() { mainRepository.getCall()?.answer(VideoProfile.STATE_AUDIO_ONLY) }

    private fun start() {
        // 2. Сразу ставим первый пульс, чтобы система начала тикать
        PulseReceiver.scheduleNextPulse(this)

        // 3. Запускаем саму логику
        mainRepository.startDtmf()

        // 4. Показываем уведомление
        updateForegroundNotification()
    }

    private fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
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