package com.mcal.dtmf.data.repositories.preferences

import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    fun getServiceNumberFlow(): Flow<String>
    fun getServiceNumber(): String
    fun setServiceNumber(number: String)

    fun getFlashSignalFlow(): Flow<Boolean>
    fun getFlashSignal(): Boolean
    fun setFlashSignal(enabled: Boolean)

    fun getErrorControlFlow(): Flow<Boolean>
    fun getErrorControl(): Boolean
    fun setErrorControl(enabled: Boolean)

    fun getVoxActivationFlow(): Flow<Long>
    fun getVoxActivation(): Long
    fun setVoxActivation(ms: Long)

    fun getVoxHoldFlow(): Flow<Long>
    fun getVoxHold(): Long
    fun setVoxHold(ms: Long)

    fun getVoxThresholdFlow(): Flow<Long>
    fun getVoxThreshold(): Long
    fun setVoxThreshold(ms: Long)

    fun getModeSelectionFlow(): Flow<String>
    fun getModeSelection(): String
    fun setModeSelection(value: String)

    fun getVoxSettingFlow(): Flow<Boolean>
    fun getVoxSetting(): Boolean
    fun setVoxSetting(enabled: Boolean)

    fun getNumberAFlow(): Flow<String>
    fun getNumberA(): String
    fun setNumberA(number: String)

    fun getNumberBFlow(): Flow<String>
    fun getNumberB(): String
    fun setNumberB(number: String)

    fun getNumberCFlow(): Flow<String>
    fun getNumberC(): String
    fun setNumberC(number: String)

    fun getNumberDFlow(): Flow<String>
    fun getNumberD(): String
    fun setNumberD(number: String)
}
