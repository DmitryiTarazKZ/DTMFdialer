package com.mcal.dtmf.data.model.domain.settings

data class SettingsScreenState(
    val isPlayMusic: Boolean = false,
    val isFlashSignal: Boolean = false,
    val isNoDtmModule: Boolean = false,
    val serviceNumber: String = "",
    var delayMusic: Long = 150L,
    var delayMusic1: Long = 500L,
    var delayMusic2: Long = 500L,
    var connType: String = "",
    var soundSource: String = "",
)
