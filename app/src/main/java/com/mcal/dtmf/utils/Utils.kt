package com.mcal.dtmf.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.TELEPHONY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import java.io.File


class Utils {
    companion object {

        // Получение данных о том подключенны наушники или нет
        fun registerHeadphoneReceiver(context: Context, callback: (Boolean) -> Unit) {
            val headphoneReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == AudioManager.ACTION_HEADSET_PLUG) {
                        val isConnected = intent.getIntExtra("state", 0) == 1
                        callback(isConnected)
                    }
                }
            }
            context.registerReceiver(headphoneReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
        }

        // Получения текущего уровня сигнала сотовой сети (от 0 до 4 где 0 отсутствие сигнала)
        fun getCurentCellLevel(context: Context, callback: (Int, String) -> Unit) {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val telephonyManager = context.getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                telephonyManager.listen(object : PhoneStateListener() {
                    @Deprecated("Этот метод устарел")
                    override fun onSignalStrengthsChanged(signalStrengths: SignalStrength) {
                        val signalStrength = signalStrengths.level
                        val simOperatorName = telephonyManager.simOperatorName
                        callback(signalStrength, simOperatorName)
                    }
                }, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            }
        }

        // регистрация BroadcastReceiver, который будет слушать события изменения состояния батареи.
        fun getCurrentBatteryTemperature(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                ((intent!!.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).toFloat()) / 10).toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }

        fun getCurrentBatteryVoltage(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                ((intent!!.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)).toFloat() / 1000).toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }

        fun getCurrentBatteryLevel(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                (intent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)).toFloat().toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }
        // Выгрузка файлов с assets в Donwload на устройство
        fun copyFileFromAssets(context: Context, path: String) {
            val filename = path.split("/").last()
            val copiedFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                filename
            )
            if (!copiedFile.exists()) {
                context.assets.open(path).use { input ->
                    copiedFile.outputStream().use { output ->
                        input.copyTo(output, 1024)
                    }
                }
            }
        }
    }
}