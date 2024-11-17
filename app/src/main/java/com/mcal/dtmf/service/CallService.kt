package com.mcal.dtmf.service



import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.data.repositories.preferences.PreferencesRepository
import com.mcal.dtmf.utils.CallDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


class CallService : InCallService(), KoinComponent {
    private val mainRepository: MainRepository by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            mainRepository.setCallState(state)
            mainRepository.setCallDirection(getCallDirection(state))
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        // Устанавливаем звонок
        mainRepository.setCall(call)
        // Устанавливаем сервис
        mainRepository.setCallService(this)
        // Получаем и устанавливаем значения
        val callState: Int = getCallState(call)
        val callDirection: Int = getCallDirection(callState)
        mainRepository.setCallState(callState)
        mainRepository.setCallDirection(callDirection)

        // Если входящий, то проверяем сначала на сервисный номер, иначе включаем дтмф
        if (callDirection == CallDirection.DIRECTION_INCOMING) {
            val callNumber = call.details.handle.schemeSpecificPart.trim()
            val serviceNumber = preferencesRepository.getServiceNumber().trim()

            if (containsNumbers(callNumber, serviceNumber)) {
                DtmfService.callServiceAnswer(this)
                return
            }

            if (mainRepository.getFlashlight() ?: false) {
                // Вспышка уже включена, ничего не делаем
            } else if (mainRepository.getConnType() != "Супертелефон"){
                // Включаем вспышку
                mainRepository.setFlashlight(true)
            }

            if (!mainRepository.getStartFlashlight()) { // если дтмф выключен, то мы его включаем
                mainRepository.setStartFlashlight(true)
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    mainRepository.speakSuperTelephone()
                }
            }
            mainRepository.setInput(callNumber.replace("+", ""))
        }

        // Каллбэк на изменение состояния звонка для определения направления и не только
        call.registerCallback(callback)
    }

    @Deprecated("Этот метод устарел")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            mainRepository.setCallAudioRoute(audioState.route)
        }
    }

    private fun containsNumbers(callNumber: String, serviceNumber: String): Boolean {
        if (callNumber.isEmpty() || serviceNumber.isEmpty()) {
            return false
        }
        val cNumber = extractNumbers(callNumber)
        val sNumber = extractNumbers(serviceNumber)
        return cNumber == sNumber
    }

    private fun extractNumbers(text: String): String {
        val regex = "(\\d+|8\\d+)".toRegex()
        return regex.findAll(text.asDigits()).map {
            if (it.value.startsWith('8')) {
                "7${it.value.substring(1)}"
            } else {
                it.value
            }
        }.joinToString("").trim()
    }

    private fun String.asDigits(): String {
        return filter { it.isDigit() }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val callState: Int = getCallState(call)
        //Задержка отключения вспышки после завершения вызова
        mainRepository.setTimer(7000)
        mainRepository.setCall(null)
        mainRepository.setInput("", withoutTimer = true)
        mainRepository.setCallState(callState)
        mainRepository.setCallDirection(getCallDirection(callState))
        call.unregisterCallback(callback)
    }

    private fun getCallState(call: Call): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return call.details.state
        }
        return call.state
    }

    private fun getCallDirection(callState: Int): Int {
        return when (callState) {
            Call.STATE_RINGING -> {
                CallDirection.DIRECTION_INCOMING
            }

            Call.STATE_CONNECTING -> {
                CallDirection.DIRECTION_OUTGOING
            }

            Call.STATE_DISCONNECTED -> {
                CallDirection.DIRECTION_UNKNOWN
            }

            Call.STATE_ACTIVE -> {
                CallDirection.DIRECTION_ACTIVE
            }

            else -> {
                mainRepository.getCallDirection()
            }
        }
    }
}