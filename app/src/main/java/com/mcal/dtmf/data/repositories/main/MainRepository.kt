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

    fun getPowerFlow(): Flow<Boolean>
    fun getPower(): Boolean?
    fun setPower(value: Boolean)

    fun getAmplitudeCheckFlow(): Flow<Boolean>
    fun getAmplitudeCheck(): Boolean?
    fun setAmplitudeCheck(value: Boolean)

    fun getSimFlow(): Flow<Int>
    fun getSim(): Int
    fun setSim(value: Int)

    suspend fun record()
    suspend fun recognize()

    fun clickKey(input: String, key: Char?)
    fun stopDtmf()

    fun startDtmf()

    fun speakText(text: String, flagVoise: Boolean)

    suspend fun speakSuperTelephone()

}
