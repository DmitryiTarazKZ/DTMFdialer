package com.mcal.dtmf.data.repositories.preferences

import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    fun getServiceNumberFlow(): Flow<String>
    fun getServiceNumber(): String
    fun setServiceNumber(number: String)

    fun getPlayMusicFlow(): Flow<Boolean>
    fun getPlayMusic(): Boolean
    fun setPlayMusic(enabled: Boolean)

    fun getFlashSignalFlow(): Flow<Boolean>
    fun getFlashSignal(): Boolean
    fun setFlashSignal(enabled: Boolean)

    fun getDelayMusicFlow(): Flow<Long>
    fun getDelayMusic(): Long
    fun setDelayMusic(ms: Long)

    fun getDelayMusic1Flow(): Flow<Long>
    fun getDelayMusic1(): Long
    fun setDelayMusic1(ms: Long)

    fun getDelayMusic2Flow(): Flow<Long>
    fun getDelayMusic2(): Long
    fun setDelayMusic2(ms: Long)

    fun getConnTypeFlow(): Flow<String>
    fun getConnType(): String
    fun setConnType(value: String)

    fun getSoundSourceFlow(): Flow<String>
    fun getSoundSource(): String
    fun setSoundSource(value: String)

    fun setSoundTest(enabled: Boolean, onSourceTested: (String, Boolean) -> Unit = { _, _ -> })
    fun getSoundTest(): Boolean
    fun getSoundTestFlow(): Flow<Boolean>

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
