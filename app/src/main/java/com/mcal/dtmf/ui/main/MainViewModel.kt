package com.mcal.dtmf.ui.main

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.mcal.dtmf.data.model.domain.main.MainScreenState
import com.mcal.dtmf.data.repositories.main.MainRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(
    private val mainRepository: MainRepository,
) : ScreenModel {

    private val _screenState = MutableStateFlow(
        MainScreenState(
            callState = mainRepository.getCallState(),
            key = mainRepository.getKey() ?: ' ',
            input = mainRepository.getInput() ?: "",
            amplitudeCheck = mainRepository.getAmplitudeCheck() ?: false,
            flagFrequencyLowHigt = mainRepository.getFlagFrequencyLowHigt() ?: false,
            isRecording = mainRepository.getIsRecording() ?: false,
            outputFrequency = mainRepository.getOutputFrequency(),
            outputFrequencyLow = mainRepository.getOutputFrequencyLow(),
            outputFrequencyHigh = mainRepository.getOutputFrequencyHigh(),
            frequencyCtcss = mainRepository.getFrequencyCtcss(),
            volumeLevelCtcss = mainRepository.getVolumeLevelCtcss(),
            isPlaying = mainRepository.getIsPlaying() ?: false,
            micClickKeyCode = mainRepository.getMicKeyClick() ?: 0,
            timer = mainRepository.getTimer(),
            magneticField = mainRepository.getMagneticField() ?: false,
            magneticFieldFlag = mainRepository.getMagneticFieldFlag() ?: false,
            statusDtmf = mainRepository.getStatusDtmf() ?: false
        )
    )
    val screenState = _screenState.asStateFlow()
    init {
        fetchData()
    }
    private fun fetchData() = screenModelScope.launch {

        mainRepository.getCallStateFlow().map { callState ->
            _screenState.update {
                it.copy(
                    callState = callState
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

        mainRepository.getInputFlow().map { input ->
            _screenState.update {
                it.copy(
                    input = input
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getAmplitudeCheckFlow().map { value ->
            _screenState.update {
                it.copy(
                    amplitudeCheck = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getFlagFrequencyLowHigtFlow().map { value ->
            _screenState.update {
                it.copy(
                    flagFrequencyLowHigt = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getIsRecordingFlow().map { value ->
            _screenState.update {
                it.copy(
                    isRecording = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getIsPlayingFlow().map { value ->
            _screenState.update {
                it.copy(
                    isPlaying = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getMagneticFieldFlow().map { value ->
            _screenState.update {
                it.copy(
                    magneticField = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getStatusDtmfFlow().map { value ->
            _screenState.update {
                it.copy(
                    statusDtmf = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getMagneticFieldFlagFlow().map { value ->
            _screenState.update {
                it.copy(
                    magneticFieldFlag = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getOutputFrequencyFlow().map { value ->
            _screenState.update {
                it.copy(
                    outputFrequency = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getOutputFrequencyLowFlow().map { value ->
            _screenState.update {
                it.copy(
                    outputFrequencyLow = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getOutputFrequencyHighFlow().map { value ->
            _screenState.update {
                it.copy(
                    outputFrequencyHigh = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getFrequencyCtcssFlow().map { value ->
            _screenState.update {
                it.copy(
                    frequencyCtcss = value
                )
            }
        }.launchIn(screenModelScope)

        mainRepository.getVolumeLevelCtcssFlow().map { value ->
            _screenState.update {
                it.copy(
                    volumeLevelCtcss = value
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

        mainRepository.getTimerFlow().map { timer ->
            _screenState.update {
                it.copy(
                    timer = timer
                )
            }
        }.launchIn(screenModelScope)
    }
    fun onClickButton(input: String, key: Char) {
        mainRepository.clickKey(input, key)
    }
}





