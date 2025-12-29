package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.mcal.dtmf.data.repositories.main.MainRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PowerReceiver : BroadcastReceiver(), KoinComponent {

    private val mainRepository: MainRepository by inject()

    override fun onReceive(context: Context, intent: Intent?) {
        // Проверяем тип подключения (USB, AC или беспроводная)
        val chargePlug = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        val wirelessCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS

        val isCharging = usbCharge || acCharge || wirelessCharge

        // Отправляем результат в репозиторий
        mainRepository.setPower(isCharging)
    }
}