package com.mcal.dtmf.receiver

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import com.mcal.dtmf.service.DtmfService
import com.mcal.dtmf.data.repositories.main.MainRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SmsReceiver : BroadcastReceiver(), KoinComponent {

    private val mainRepository: MainRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNotEmpty()) {
                val sender = messages[0].displayOriginatingAddress ?: "Неизвестный"
                val body = messages.joinToString("") { it.displayMessageBody }
                val time = messages[0].timestampMillis

                saveSmsToDatabase(context, sender, body, time)

                try {
                    val activityIntent = Intent(context, com.mcal.dtmf.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(activityIntent)
                    mainRepository.incrementSmsCount()
                    mainRepository.startDtmf()
                    mainRepository.handleIncomingSms(context)
                } catch (e: Exception) { }
            }
        }
    }

    private fun saveSmsToDatabase(context: Context, address: String, body: String, time: Long) {
        val values = ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", time)
            put("read", 0)
            put("type", 1)
        }
        try {
            context.contentResolver.insert(Uri.parse("content://sms/inbox"), values)
        } catch (e: Exception) { }
    }
}