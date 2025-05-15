package com.mcal.dtmf.data.repositories.main

import android.content.Context
import android.telecom.Call
import android.telecom.InCallService
import com.mcal.dtmf.recognizer.Spectrum
import kotlinx.coroutines.flow.Flow

interface MainRepository {

    fun getCallFlow(): Flow<Call?>
    fun getCall(): Call?
    fun setCall(call: Call?)

    fun getFrequencyCtcssFlow(): Flow<Double>
    fun getFrequencyCtcss(): Double
    fun setFrequencyCtcss(frequencyCtcss: Double)

    fun getVolumeLevelCtcssFlow(): Flow<Double>
    fun getVolumeLevelCtcss(): Double
    fun setVolumeLevelCtcss(volumeLevelCtcss: Double)

    fun getCallStateFlow(): Flow<Int>
    fun getCallState(): Int
    fun setCallState(callState: Int)

    fun getKeyFlow(): Flow<Char>
    fun getKey(): Char?
    fun setKey(key: Char)

    fun getInputFlow(): Flow<String>
    fun getInput(): String?
    fun setInput(value: String)

    fun getInput1Flow(): Flow<String>
    fun getInput1(): String?
    fun setInput1(value: String)

    fun getInput2Flow(): Flow<String>
    fun getInput2(): String?
    fun setInput2(value: String)

    fun getPowerFlow(): Flow<Boolean>
    fun getPower(): Boolean?
    fun setPower(value: Boolean)

    fun getIsRecordingFlow(): Flow<Boolean>
    fun getIsRecording(): Boolean?
    fun setIsRecording(value: Boolean)

    fun getAmplitudeCheckFlow(): Flow<Boolean>
    fun getAmplitudeCheck(): Boolean?
    fun setAmplitudeCheck(value: Boolean)

    fun getFlagFrequencyLowHigtFlow(): Flow<Boolean>
    fun getFlagFrequencyLowHigt(): Boolean?
    fun setFlagFrequencyLowHigt(value: Boolean)

    fun getSimFlow(): Flow<Int>
    fun getSim(): Int
    fun setSim(value: Int)

    fun getOutputFrequencyFlow(): Flow<Float>
    fun getOutputFrequency(): Float
    fun setOutputFrequency(outputFrequency: Float)

    fun getOutputFrequencyLowFlow(): Flow<Float>
    fun getOutputFrequencyLow(): Float
    fun setOutputFrequencyLow(outputFrequencyLow: Float)

    fun getOutputFrequencyHighFlow(): Flow<Float>
    fun getOutputFrequencyHigh(): Float
    fun setOutputFrequencyHigh(outputFrequencyHigh: Float)

    fun getOutputAmplitudeFlow(): Flow<Float>
    fun getOutputAmplitude(): Float
    fun setOutputAmplitude(outputAmplitude: Float)

    fun getVolumeLevelTtsFlow(): Flow<Float>
    fun getVolumeLevelTts(): Float
    fun setVolumeLevelTts(volumeLevelTts: Float)

    fun getSelectedSubscriberNumberFlow(): Flow<Int>
    fun getSelectedSubscriberNumber(): Int
    fun setSelectedSubscriberNumber(value: Int)

    suspend fun record()
    suspend fun recognize()

    fun clickKey(input: String, key: Char?)
    fun stopDtmf()

    fun startDtmf()

    fun speakText(text: String)

    suspend fun speakSuperTelephone()

}
