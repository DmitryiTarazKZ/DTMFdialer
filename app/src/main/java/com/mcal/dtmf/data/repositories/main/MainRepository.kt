package com.mcal.dtmf.data.repositories.main

import android.content.Context
import android.telecom.Call
import android.telecom.InCallService
import com.mcal.dtmf.recognizer.Spectrum
import kotlinx.coroutines.flow.Flow

interface MainRepository {

    fun enableSpeaker()
    fun disableSpeaker()

    fun getTimerFlow(): Flow<Long>
    fun getTimer(): Long
    fun setTimer(duration: Long)

    fun getCallServiceFlow(): Flow<InCallService?>
    fun getCallService(): InCallService?
    fun setCallService(callService: InCallService?)

    fun getCallAudioRouteFlow(): Flow<Int>
    fun getCallAudioRoute(): Int
    fun setCallAudioRoute(audioRoute: Int)

    fun getCallFlow(): Flow<Call?>
    fun getCall(): Call?
    fun setCall(call: Call?)

    fun getCallDirectionFlow(): Flow<Int>
    fun getCallDirection(): Int
    fun setCallDirection(callDirection: Int)

    fun getCallStateFlow(): Flow<Int>
    fun getCallState(): Int
    fun setCallState(callState: Int)

    fun getSpectrumFlow(): Flow<Spectrum>
    fun getSpectrum(): Spectrum?
    fun setSpectrum(spectrum: Spectrum)

    fun getKeyFlow(): Flow<Char>
    fun getKey(): Char?
    fun setKey(key: Char)

    fun getInputFlow(): Flow<String>
    fun getInput(): String?
    fun setInput(value: String, withoutTimer: Boolean = false)

    fun getMicKeyClickFlow(): Flow<Int>
    fun getMicKeyClick(): Int?
    fun setMicKeyClick(value: Int)

    fun getFlashlightFlow(): Flow<Boolean>
    fun getFlashlight(): Boolean?
    fun setFlashlight(value: Boolean)

    fun getPowerFlow(): Flow<Boolean>
    fun getPower(): Boolean?
    fun setPower(value: Boolean)

    fun getIsConnectFlow(): Flow<Boolean>
    fun getIsConnect(): Boolean?
    fun setIsConnect(value: Boolean)

    suspend fun record()

    suspend fun recognize()

    fun clickKey(input: String, key: Char?)

    fun stopDtmf()

    fun wakeUpScreen(context: Context)

    fun getStartDtmfFlow(): Flow<Boolean>
    fun getStartDtmf(): Boolean
    fun setStartDtmf(enabled: Boolean, hasProblem: Boolean = false)

    fun getServiceNumberFlow(): Flow<String>
    fun getServiceNumber(): String
    fun setServiceNumber(number: String)

    fun getFlashSignalFlow(): Flow<Boolean>
    fun getFlashSignal(): Boolean
    fun setFlashSignal(enabled: Boolean)

    fun getErrorControlFlow(): Flow<Boolean>
    fun getErrorControl(): Boolean
    fun setErrorControl(enabled: Boolean)

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

    fun getModeSelectionFlow(): Flow<String>
    fun getModeSelection(): String
    fun setModeSelection(value: String)

    fun getOutput1Flow(): Flow<Float>
    fun getOutput1(): Float
    fun setOutput1(outputFrequency1: Float)

    fun startDtmf()

    fun selectSimCard(slot1: String?, slot2: String?, phoneAccount: Int)

    fun speakText(text: String)

    suspend fun speakSuperTelephone()
}
