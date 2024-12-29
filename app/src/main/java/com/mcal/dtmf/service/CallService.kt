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
import com.mcal.dtmf.utils.LogManager
import com.mcal.dtmf.data.repositories.main.LogLevel


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

        val callNumber = call.details.handle?.schemeSpecificPart?.trim() ?: "fun onCallAdded() номер NULL"
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
            LogManager.logOnMain(LogLevel.INFO, "fun onCallAdded() Входящий вызов с номера: $callNumber", mainRepository.getErrorControl())
            val serviceNumber = preferencesRepository.getServiceNumber().trim()

            if (containsNumbers(callNumber, serviceNumber)) {
                LogManager.logOnMain(LogLevel.INFO, "fun onCallAdded() Совпадение номера входящего вызова с сервисным номером, отправка смс", mainRepository.getErrorControl())
                DtmfService.callServiceAnswer(this)
                return
            }

            if (mainRepository.getFlashlight() ?: false) {
            } else if (mainRepository.getModeSelection() != "Супертелефон"){
                mainRepository.setFlashlight(true)
            }

            if (!mainRepository.getStartDtmf()) {
                mainRepository.setStartDtmf(true)
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    mainRepository.speakSuperTelephone()
                }
            }
            mainRepository.setInput(callNumber.replace("+", ""))
        }

        // Каллбэк на изменение состояния звонка
        call.registerCallback(callback)
    }

    private var previousRoute: Int? = null // Хранит предыдущее состояние маршрута

    @Deprecated("Этот метод устарел")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            val currentRoute = audioState.route
            val routeStr = when (currentRoute) {
                CallAudioState.ROUTE_EARPIECE -> "ДИНАМИК НЕ ГРОМКОЙ СВЯЗИ (код = 1)"
                CallAudioState.ROUTE_BLUETOOTH -> "BLUETOOTH (код = 2)"
                CallAudioState.ROUTE_SPEAKER -> "ГРОМКОГОВОРИТЕЛЬ (код = 8)"
                CallAudioState.ROUTE_WIRED_HEADSET -> "ПРОВОДНУЮ ГАРНИТУРУ (код = 4)"
                else -> "UNKNOWN"
            }

            // Проверка на изменение маршрута
            if (currentRoute != previousRoute) {
                LogManager.logOnMain(LogLevel.INFO, "fun onCallAudioStateChanged() Смена аудиомаршрута, звук выводится на: $routeStr", mainRepository.getErrorControl())
                mainRepository.setCallAudioRoute(currentRoute)
                previousRoute = currentRoute // Обновляем предыдущее состояние
            }
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