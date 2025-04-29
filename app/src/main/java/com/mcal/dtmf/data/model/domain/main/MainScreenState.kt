package com.mcal.dtmf.data.model.domain.main

import android.telecom.Call

data class MainScreenState(
    val keys: List<Char> = listOf(
        '1', '2', '3', 'A',
        '4', '5', '6', 'B',
        '7', '8', '9', 'C',
        '*', '0', '#', 'D'
    ),
    val input: String = "", // Переменная поля ввода
    val key: Char = ' ', // Значение из блока Фурье
    val callState: Int = Call.STATE_DISCONNECTED, // Состояние вызова
    val amplitudeCheck: Boolean = false, // Флаг проверки амплитуды
    val amplitudeCheck1: Boolean = false // Флаг определения нажатия PTT
)