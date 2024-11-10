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
import android.os.IBinder
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mcal.dtmf.R
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.utils.Utils.Companion.getCurrentBatteryLevel
import com.mcal.dtmf.utils.Utils.Companion.getCurrentBatteryTemperature
import com.mcal.dtmf.utils.Utils.Companion.getCurrentBatteryVoltage
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


class DtmfService : Service(), KoinComponent {
    private val mainRepository: MainRepository by inject()

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_CALL_START -> startCall(false, 0)
            ACTION_CALL_ANSWER -> answerCall()
            ACTION_SERVICE_CALL_ANSWER -> answerServiceCall()
            ACTION_CALL_END -> endCall(false)
            ACTION_STOP -> stop()
            // получение переменных для выбора сим с репозитория
            ACTION_CALL_START_SIM -> {
                val isPressed = intent.getBooleanExtra(EXTRA_CALL_START_SIM_PRESSED, false)
                val sim = intent.getIntExtra(EXTRA_CALL_START_SIM_SIM, 0)
                startCall(isPressed, sim)
            }
        }
        return START_STICKY
    }

    private fun startCall(isButtonPressed: Boolean, sim: Int) {
        mainRepository.getInput()?.let { phone ->
            var slot1: String? = "Нет сигнала"
            var slot2: String? = "Нет сигнала"

            // Проверка есть ли разрешение на выполнение вызова
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptions = subscriptionManager.activeSubscriptionInfoList

            if (subscriptions == null || subscriptions.isEmpty()) {
                mainRepository.noSim()
                return
            }

            subscriptions.forEach { subscription ->
                val simSlotIndex = subscription.simSlotIndex + 1
                val simCarrierName = subscription.carrierName?.toString() ?: "Нет сигнала"

                when (simSlotIndex) {
                    1 -> slot1 = simCarrierName
                    2 -> slot2 = simCarrierName
                }
            }

            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val phoneAccountHandles = telecomManager.callCapablePhoneAccounts

            if (slot1 == "Нет сигнала" && slot2 == "Нет сигнала") {
                mainRepository.networNone()
            } else {
                val phoneAccountCount = phoneAccountHandles.size
                if (phoneAccountCount > 1 || isButtonPressed) {
                    if (isButtonPressed) {
                        mainRepository.wakeUpScreen(this)
                        // Совершить вызов с выбранной SIM-карты
                        val intent = Intent(Intent.ACTION_CALL).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            data = Uri.parse("tel:$phone")
                            putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandles[sim])
                        }
                        startActivity(intent)
                    } else {
                        // Активны обе сим-карты, предлагаем выбрать с какой позвонить
                        mainRepository.selectSimCard(slot1, slot2, phoneAccountCount)
                    }
                } else {
                    // Если активна только одна сим карта, выполняем вызов сразу
                    mainRepository.selectSimCard(slot1, slot2, phoneAccountCount)
                }
            }
        }
    }

    // Если входящий вызов с сервисного номера то отклонить вызов с отправкой сообщения
    private fun endCall(isServiceNumber: Boolean) {
        if (mainRepository.getCallState() == Call.STATE_RINGING) {
            if (isServiceNumber) {
                mainRepository.getCall()!!
                    .reject(
                        true,
                        "Температура батареи: ${getCurrentBatteryTemperature(this) + "°С "}" +
                                "Заряд батареи: ${getCurrentBatteryLevel(this) + "% "}" +
                                "Напряжение батареи: ${getCurrentBatteryVoltage(this)}" + " В"
                    )
            } else {
                mainRepository.getCall()!!.reject(false, null)
            }
        } else {
            mainRepository.getCall()!!.disconnect()
        }
    }

    private fun answerCall() {
        mainRepository.getCall()!!.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    private fun answerServiceCall() {
        endCall(true)
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
        internal const val ACTION_CALL_START_SIM = "call_start_sim"
        internal const val EXTRA_CALL_START_SIM_PRESSED = "extra_call_start_sim_pressed"
        internal const val EXTRA_CALL_START_SIM_SIM = "extra_call_start_sim_sim"
        internal const val ACTION_CALL_END = "call_end"
        internal const val ACTION_CALL_ANSWER = "call_answer"
        internal const val ACTION_SERVICE_CALL_ANSWER = "service_call_answer"
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

        fun callServiceAnswer(context: Context) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_SERVICE_CALL_ANSWER)
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

        // статичный метод для получения переменных с репозитория
        fun callStartSim(context: Context, buttonPressed: Boolean, sim: Int) {
            val intent = Intent(context, DtmfService::class.java)
            intent.setAction(ACTION_CALL_START_SIM)
            intent.putExtra(EXTRA_CALL_START_SIM_PRESSED, buttonPressed)
            intent.putExtra(EXTRA_CALL_START_SIM_SIM, sim)
            context.startService(intent)
        }
    }
}