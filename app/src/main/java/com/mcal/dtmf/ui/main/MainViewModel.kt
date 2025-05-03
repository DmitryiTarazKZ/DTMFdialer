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
            amplitudeCheck1 = mainRepository.getAmplitudeCheck1() ?: false,
            outputFrequency = mainRepository.getOutput()
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
        mainRepository.getAmplitudeCheck1Flow().map { value ->
            _screenState.update {
                it.copy(
                    amplitudeCheck1 = value
                )
            }
        }.launchIn(screenModelScope)
        mainRepository.getOutputFlow().map { value ->
            _screenState.update {
                it.copy(
                    outputFrequency = value
                )
            }
        }.launchIn(screenModelScope)
    }
    fun onClickButton(input: String, key: Char) {
        mainRepository.clickKey(input, key)
    }
}





