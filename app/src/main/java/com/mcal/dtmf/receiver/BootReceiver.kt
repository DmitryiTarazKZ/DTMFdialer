package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcal.dtmf.service.DtmfService // Убедись, что путь к сервису верный
import org.koin.core.component.KoinComponent

open class BootReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        // Список экшенов, при которых мы хотим оживить сервис
        val actionsToRestart = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (actionsToRestart.contains(action)) {
            // Запускаем сервис напрямую в фоне
            // Используем твой метод DtmfService.start(context)
            try {
                DtmfService.start(context)
            } catch (e: Exception) {
                // На Android 9 (API 28) запуск сервиса из фона разрешен,
                // если это не ограничено специфическими настройками вендора
            }
        }
    }
}