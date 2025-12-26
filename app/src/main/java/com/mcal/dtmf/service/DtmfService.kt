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
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        when (intent?.action) {
//            ACTION_START -> start()
//            ACTION_CALL_START -> startCall()
//            ACTION_CALL_ANSWER -> answerCall()
//            ACTION_CALL_END -> endCall()
//            ACTION_STOP -> stop()
//        }
//        return START_STICKY
//    }

    // 2. Добавляем метод onCreate (выполняется ОДИН раз при самом первом запуске сервиса)
    override fun onCreate() {
        super.onCreate()

        // Включаем "защиту от сна" для процессора
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "DTMF:WakeLock"
        ).apply {
            acquire()
        }
    }

    // 3. Добавляем метод onDestroy (выполняется, когда сервис полностью выключается)
    override fun onDestroy() {
        // 1. Сначала останавливаем микрофон и тяжелую логику.
        // Пока процессор еще гарантированно работает.
        try {
            mainRepository.stopDtmf()
        } catch (e: Exception) {
            // Логируем, если что-то пошло не так
        }

        // 2. Теперь, когда всё "железо" выключено, отпускаем процессор.
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null // Хорошим тоном будет обнулить ссылку

        // 3. Завершаем работу сервиса
        super.onDestroy()
    }

    private fun getStatsPrefs() = getSharedPreferences("dtmf_stats", Context.MODE_PRIVATE)

    private fun resetStats() {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val date = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date())

        getStatsPrefs().edit()
            .putString("start_time", "$time $date")
            .putInt("restart_count", 0)
            .apply()
    }

    private fun getStatsString(): String {
        val prefs = getStatsPrefs()
        val startInfo = prefs.getString("start_time", "--:-- --.**.****")
        val restarts = prefs.getInt("restart_count", 0)

        return "Старт в $startInfo\nПерезапусков: $restarts"
    }

    private fun incrementRestartCount() {
        val current = getStatsPrefs().getInt("restart_count", 0)
        getStatsPrefs().edit().putInt("restart_count", current + 1).apply()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_START) {
            resetStats() // Сбрасываем стат при ручном запуске
            start()
        } else if (action == null) {
            incrementRestartCount() // Увеличиваем счетчик, если это авто-воскрешение
            start()
        } else {
            when (action) {
                ACTION_CALL_START -> startCall()
                ACTION_CALL_ANSWER -> answerCall()
                ACTION_CALL_END -> endCall()
                ACTION_STOP -> stop()
            }
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
            mainRepository.speakText("В устройстве нет ни одной действующей сим карты. Установите сим карты")
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
            mainRepository.speakText("Отсутствует сигнал сотовой сети. Вызов не возможен")
            mainRepository.setInput("")
        } else {
            if (phoneAccountCount > 1 && (slot1 != "Нет сигнала" && slot2 != "Нет сигнала")) {
                if (mainRepository.getSim() == 5) { mainRepository.speakText("Выберите с какой сим карты выполнить вызов") }
                if (mainRepository.getSim() == 0 || mainRepository.getSim() == 1) {
                    mainRepository.speakText("Звоню с  ${if (mainRepository.getSim() == 0) slot1 else slot2}")
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
                        } else mainRepository.speakText("Предотвращен вызов с пустым номером")
                    }
                    }, 5000) // Задержка в 5000 миллисекунд (5 секунд)
                }
            } else {

                if (slot1 == "Нет сигнала") {
                    mainRepository.speakText("Звоню с $slot2")
                }

                if (slot2 == "Нет сигнала") {
                    mainRepository.speakText("Звоню с $slot1")
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
                        } else mainRepository.speakText("Предотвращен вызов с пустым номером")
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
        mainRepository.startDtmfInternal()
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

        val contentIntent = Intent(this, com.mcal.dtmf.MainActivity::class.java)
        val contentPendingIntent = android.app.PendingIntent.getActivity(
            this, 0, contentIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Служба DTMF",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val stats = getStatsString()

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DTMF Телефон")
            .setContentText(stats) // Для свернутого вида
            .setStyle(NotificationCompat.BigTextStyle().bigText(stats)) // Для развернутого вида (в 2 строки)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent) // Обязательно для Android 8+
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

