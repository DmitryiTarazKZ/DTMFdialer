package com.mcal.dtmf.ui.main

import android.telecom.CallAudioState
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.mcal.dtmf.data.model.domain.main.MainScreenState
import com.mcal.dtmf.data.repositories.main.MainRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


class MainViewModel(
    private val mainRepository: MainRepository,
) : ScreenModel {
    private val _exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        _screenState.update {
            it.copy(
                isException = true,
                exceptionMessage = throwable.toString()
            )
        }
        throwable.printStackTrace()
    }

    private val _screenState = MutableStateFlow(
        MainScreenState(
            isConnect = mainRepository.getIsConnect() ?: false,
            connectType = mainRepository.getConnType(),
            input = mainRepository.getInput() ?: "",
            numberA = mainRepository.getNumberA(),
            numberB = mainRepository.getNumberB(),
            numberC = mainRepository.getNumberC(),
            numberD = mainRepository.getNumberD()
        )
    )
    val screenState = _screenState.asStateFlow()
    init {
        fetchData()
    }
    private fun fetchData() = screenModelScope.launch(_exceptionHandler) {
        mainRepository.getCallStateFlow().map { callState ->
            _screenState.update {
                it.copy(
                    callState = callState
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getCallDirectionFlow().map { callDirection ->
            _screenState.update {
                it.copy(
                    callDirection = callDirection
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getSpectrumFlow().map { spectrum ->
            _screenState.update {
                it.copy(
                    spectrum = spectrum
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getKeyFlow().map { key ->
            _screenState.update {
                it.copy(
                    key = key
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getFlashlightFlow().map { flashlight ->
            _screenState.update {
                it.copy(
                    flashlight = flashlight
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getMicKeyClickFlow().map { test ->
            _screenState.update {
                it.copy(
                    micClickKeyCode = test
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getInputFlow().map { input ->
            _screenState.update {
                it.copy(
                    input = input
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getNumberAFlow().map { number ->
            _screenState.update {
                it.copy(
                    numberA = number
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getNumberBFlow().map { number ->
            _screenState.update {
                it.copy(
                    numberB = number
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getNumberCFlow().map { number ->
            _screenState.update {
                it.copy(
                    numberC = number
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getNumberDFlow().map { number ->
            _screenState.update {
                it.copy(
                    numberD = number
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getConnTypeFlow().map { connType ->
            _screenState.update {
                it.copy(
                    connectType = connType
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getPowerFlow().map { powerState ->
            _screenState.update {
                it.copy(
                    isPowerConnected = powerState
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getIsConnectFlow().map { isConnect ->
            _screenState.update {
                it.copy(
                    isConnect = isConnect
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getTimerFlow().map { timer ->
            _screenState.update {
                it.copy(
                    timer = timer.toInt() / 1000
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getCallAudioRouteFlow().map { audioRoute ->
            _screenState.update {
                it.copy(
                    isSpeakerOn = checkSpeaker(audioRoute)
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getOutput1Flow().map { outputFrequency ->
            _screenState.update {
                it.copy(
                    outputFrequency = outputFrequency
                )
            }
        }.launchIn(screenModelScope)
    }
    fun onClickButton(input: String, key: Char) {
        mainRepository.clickKey(input, key)
    }
    fun flashLight() {
        mainRepository.setStartFlashlight(!mainRepository.getStartFlashlight())
    }
    fun speaker() {
        if (checkSpeaker(mainRepository.getCallAudioRoute())) {
            mainRepository.disableSpeaker()
        } else {
            mainRepository.enableSpeaker()
        }
    }

    // Включение громкоговорителя в режиме cупертелефон если кабель не используется
    fun speakerOn() {
            mainRepository.enableSpeaker()
    }


}
private fun checkSpeaker(audioRoute: Int): Boolean {
    return audioRoute == CallAudioState.ROUTE_SPEAKER
}

