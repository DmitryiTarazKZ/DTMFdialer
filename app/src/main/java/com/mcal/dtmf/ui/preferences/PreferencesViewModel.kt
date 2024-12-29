package com.mcal.dtmf.ui.preferences

import android.util.Log
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.mcal.dtmf.data.model.domain.settings.SettingsScreenState
import com.mcal.dtmf.data.repositories.main.LogLevel
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.data.repositories.preferences.PreferencesRepository
import com.mcal.dtmf.utils.LogManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val mainRepository: MainRepository
) : ScreenModel {
    private val _exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("PreferencesViewModel", throwable.toString())
        throwable.printStackTrace()
    }

    private val _screenState = MutableStateFlow(
        SettingsScreenState(
            serviceNumber = preferencesRepository.getServiceNumber(),
            modeSelection = preferencesRepository.getModeSelection(),
            voxSetting = preferencesRepository.getVoxSetting(),
            voxActivation = preferencesRepository.getVoxActivation(),
            voxHold = preferencesRepository.getVoxHold(),
            voxThreshold = preferencesRepository.getVoxThreshold(),
            isFlashSignal = preferencesRepository.getFlashSignal(),
            isErrorControl = preferencesRepository.getErrorControl(),
        )
    )
    val screenState = _screenState.asStateFlow()

    val logs = LogManager.logs
    private var previousMode: String? = null // Добавлено для хранения предыдущего режима


    init {
        fetchData()
    }

    private fun fetchData() = screenModelScope.launch(_exceptionHandler) {

        preferencesRepository.getServiceNumberFlow().map { number ->
            _screenState.update {
                it.copy(serviceNumber = number)
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getModeSelectionFlow().map { modeSelection ->
            _screenState.update {
                it.copy(modeSelection = modeSelection)
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getVoxSettingFlow().map { enabled ->
            _screenState.update {
                it.copy(voxSetting = enabled)
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getVoxActivationFlow().map { voxActivation ->
            _screenState.update {
                it.copy(voxActivation = voxActivation)
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getVoxHoldFlow().map { voxHold ->
            _screenState.update {
                it.copy(voxHold = voxHold)
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getVoxThresholdFlow().map { voxThreshold ->
            _screenState.update {
                it.copy(voxThreshold = voxThreshold)
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getFlashSignalFlow().map { enabled ->
            _screenState.update {
                it.copy(isFlashSignal = enabled)
            }
        }.launchIn(screenModelScope)

        preferencesRepository.getErrorControlFlow().map { enabled ->
            _screenState.update {
                it.copy(isErrorControl = enabled)
            }
        }.launchIn(screenModelScope)
    }

    fun setServiceNumber(value: String) {
        preferencesRepository.setServiceNumber(value)
    }

    fun setModeSelection(value: String) {
        preferencesRepository.setModeSelection(value)
        if (previousMode != value) { // Проверка на изменение режима
            LogManager.logOnMain(LogLevel.INFO, "Выбран режим: $value", mainRepository.getErrorControl())
            previousMode = value // Обновление предыдущего режима
        }
        if (value != "Репитер (2 Канала)") {
            mainRepository.setStartDtmf(!mainRepository.getStartDtmf())
        }
    }

    fun setVoxSetting(value: Boolean) {
        preferencesRepository.setVoxSetting(value)
    }

    fun setVoxActivation(value: Long) {
        preferencesRepository.setVoxActivation(value)
    }

    fun setVoxHold(value: Long) {
        preferencesRepository.setVoxHold(value)
    }

    fun setVoxThreshold(value: Long) {
        preferencesRepository.setVoxThreshold(value)
    }

    fun setFlashSignal(value: Boolean) {
        preferencesRepository.setFlashSignal(value)
    }

    fun setErrorControl(value: Boolean) {
        preferencesRepository.setErrorControl(value)
    }
}