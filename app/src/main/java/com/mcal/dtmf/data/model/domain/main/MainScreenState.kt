package com.mcal.dtmf.data.model.domain.main

import android.telecom.Call
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.utils.CallDirection

data class MainScreenState(
    val isException: Boolean = false,
    val exceptionMessage: String = "",
    val keys: List<Char> = listOf(
        '1', '2', '3', 'A',
        '4', '5', '6', 'B',
        '7', '8', '9', 'C',
        '*', '0', '#', 'D'
    ),
    val input: String = "",
    val spectrum: Spectrum? = null,
    val key: Char = ' ',
    val numberA: String = "",
    val numberB: String = "",
    val numberC: String = "",
    val numberD: String = "",
    var connectType: String = "Супертелефон",
    val callDirection: Int = CallDirection.DIRECTION_UNKNOWN,
    val callState: Int = Call.STATE_DISCONNECTED,
    val isSpeakerOn: Boolean = false,
    val delayMusic: Long = 200L,

    val micClickKeyCode: Int = 0,
    val timer: Int = 0,
    val isPowerConnected: Boolean = false,
    val isConnect: Boolean = false,

    val flashlight: Boolean = false,
    val isServiceStarted: Boolean = false,
    var outputFrequency: Float = 0f,

)
