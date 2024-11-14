package com.mcal.dtmf.ui.preferences

import android.util.Log
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.mcal.dtmf.data.model.domain.settings.SettingsScreenState
import com.mcal.dtmf.data.repositories.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ScreenModel {
    private val _exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("PreferencesViewModel", throwable.toString())
        throwable.printStackTrace()
    }

    private val _screenState = MutableStateFlow(
        SettingsScreenState(
            serviceNumber = preferencesRepository.getServiceNumber(),
            connType = preferencesRepository.getConnType(),
            soundSource = preferencesRepository.getSoundSource(),
            soundTest = preferencesRepository.getSoundTest(),
            delayMusic = preferencesRepository.getDelayMusic(),
            delayMusic1 = preferencesRepository.getDelayMusic1(),
            delayMusic2 = preferencesRepository.getDelayMusic2(),
            isPlayMusic = preferencesRepository.getPlayMusic(),
            isFlashSignal = preferencesRepository.getFlashSignal(),
            isNoDtmModule = preferencesRepository.getDtmModule(),
        )
    )
    val screenState = _screenState.asStateFlow()

    init {
        fetchData()
    }

    private fun fetchData() = screenModelScope.launch(_exceptionHandler) {
        preferencesRepository.getServiceNumberFlow().map { number ->
            _screenState.update {
                it.copy(
                    serviceNumber = number
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getConnTypeFlow().map { connType ->
            _screenState.update {
                it.copy(
                    connType = connType
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getSoundSourceFlow().map { soundSource ->
            _screenState.update {
                it.copy(
                    soundSource = soundSource
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getSoundTestFlow().map { enabled ->
            _screenState.update {
                it.copy(
                    soundTest = enabled
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getDelayMusicFlow().map { delayMusic ->
            _screenState.update {
                it.copy(
                    delayMusic = delayMusic
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getDelayMusic1Flow().map { delayMusic1 ->
            _screenState.update {
                it.copy(
                    delayMusic1 = delayMusic1
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getDelayMusic2Flow().map { delayMusic2 ->
            _screenState.update {
                it.copy(
                    delayMusic2 = delayMusic2
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getPlayMusicFlow().map { enabled ->
            _screenState.update {
                it.copy(
                    isPlayMusic = enabled
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getFlashSignalFlow().map { enabled ->
            _screenState.update {
                it.copy(
                    isFlashSignal = enabled
                )
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getDtmModuleFlow().map { enabled ->
            _screenState.update {
                it.copy(
                    isNoDtmModule = enabled
                )
            }
        }.launchIn(screenModelScope)

    }

    fun setServiceNumber(value: String) {
        preferencesRepository.setServiceNumber(value)
    }

    fun setConnType(value: String) {
        preferencesRepository.setConnType(value)
    }

    fun setSoundSource(value: String) {
        preferencesRepository.setSoundSource(value)
    }

    fun setSoundTest(value: Boolean) {
        preferencesRepository.setSoundTest(value)
    }

    fun setDelayMusic(value: Long) {
        preferencesRepository.setDelayMusic(value)
    }

    fun setDelayMusic1(value: Long) {
        preferencesRepository.setDelayMusic1(value)
    }

    fun setDelayMusic2(value: Long) {
        preferencesRepository.setDelayMusic2(value)
    }

    fun setFlashSignal(value: Boolean) {
        preferencesRepository.setFlashSignal(value)
    }

    fun setNoDtmModule(value: Boolean) {
        preferencesRepository.setDtmModule(value)
    }
}
