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
        val action = intent?.action

        // --- УДАЛЕНО: Больше не планируем пульс здесь через PendingIntent.getService ---

        when (action) {
            ACTION_START -> {
                // При первом запуске из Activity запускаем цепочку пульса в ресивере
                PulseReceiver.scheduleNextPulse(this)
                updateNotification(StartType.USER)
            }
            ACTION_PULSE -> {
                updateNotification(StartType.PULSE)
            }
            null -> {
                // Если система убила и восстановила сервис сама
                updateNotification(StartType.SYSTEM)
            }
            ACTION_CALL_START -> startCall()
            ACTION_CALL_ANSWER -> answerCall()
            ACTION_CALL_END -> endCall()
            ACTION_STOP -> stop()
        }

        // Теперь разрешаем запуск логики и при пульсе тоже,
        // чтобы репозиторий обнулял свои джобы (initJob) каждые 30 минут
        if (action == ACTION_START || action == null || action == ACTION_PULSE) {
            mainRepository.startDtmf()
        }

        return START_STICKY
    }

    enum class StartType {
        USER,    // Ручной запуск (ACTION_START) время
        SYSTEM,  // Воскрешение системой (intent == null)
        PULSE    // Обновление по таймеру (ACTION_PULSE)
    }

    private fun updateNotification(type: StartType) {
        val prefs = getSharedPreferences("dtmf_stats", MODE_PRIVATE)
        val edit = prefs.edit()

        when (type) {
            StartType.USER -> {
                val now = SimpleDateFormat("HH:mm dd.MM.yyyy", Locale.getDefault()).format(Date())
                edit.clear()
                edit.putString("start_time", now)
                    .putInt("count_system", 0)
                    .putInt("count_pulse", 0)
            }
            StartType.SYSTEM -> edit.putInt("count_system", prefs.getInt("count_system", 0) + 1)
            StartType.PULSE -> edit.putInt("count_pulse", prefs.getInt("count_pulse", 0) + 1)
        }
        edit.apply()

        val stats = """
            Время старта: ${prefs.getString("start_time", "--:--")}
            Перезапуск системой: ${prefs.getInt("count_system", 0)}
            Перезапуск через 30 мин: ${prefs.getInt("count_pulse", 0)}
        """.trimIndent()

        val contentPendingIntent = PendingIntent.getActivity(this, 0,
            Intent(this, com.mcal.dtmf.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Служба DTMF", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DTMF Телефон")
            .setStyle(NotificationCompat.BigTextStyle().bigText(stats))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

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
        const val ACTION_CALL_START = "call_start"
        const val ACTION_CALL_END = "call_end"
        const val ACTION_CALL_ANSWER = "call_answer"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PULSE = "pulse"
        const val NOTIFICATION_CHANNEL_ID = "DTMF"

        fun start(context: Context) {
            val intent = Intent(context, DtmfService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun callStart(context: Context) = context.startService(Intent(context, DtmfService::class.java).apply { action = ACTION_CALL_START })
        fun callEnd(context: Context) = context.startService(Intent(context, DtmfService::class.java).apply { action = ACTION_CALL_END })
        fun callAnswer(context: Context) = context.startService(Intent(context, DtmfService::class.java).apply { action = ACTION_CALL_ANSWER })
        fun stop(context: Context) = context.startService(Intent(context, DtmfService::class.java).apply { action = ACTION_STOP })
    }
}