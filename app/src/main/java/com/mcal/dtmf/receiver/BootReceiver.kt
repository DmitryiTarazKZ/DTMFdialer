package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcal.dtmf.MainActivity
import com.mcal.dtmf.data.repositories.main.LogLevel
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.utils.LogManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


open class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val mainRepository: MainRepository by inject()
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> { // Загрузка ОС Андроид завершена
                    LogManager.logOnMain(
                        LogLevel.ERROR, "Загрузка ОС Андроид завершена. АВТОЗАПУСК ПО ВКЛЮЧЕНИЮ. Запускаем MainActivity хотя это и нарушает архитектурные принципы андроид", mainRepository.getErrorControl())

                    // Запускаем MainActivity хотя это и нарушает архитектурные принципы андроид
                    val mainActivityIntent = Intent(context, MainActivity::class.java)
                    mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(mainActivityIntent)

                    return
                }
            }
        }
    }
}
