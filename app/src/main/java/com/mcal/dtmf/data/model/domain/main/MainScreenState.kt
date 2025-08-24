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
    val isRecording: Boolean = false, // Флаг определения отпускания PTT при записи голосовой заметки
    val flagFrequencyLowHigt: Boolean = false, // Флаг для отображения верней и нижней частоты DTMF
    val outputFrequency: Float = 0f, // Получение частоты с блока распознавания
    val outputFrequencyLow: Float = 0f, // Получение нижней частоты с блока распознавания
    val outputFrequencyHigh: Float = 0f, // Получение верхней частоты с блока распознавания
    val frequencyCtcss: Double = 0.0, // Значение частоты субтона
    val volumeLevelCtcss: Double = 0.08, // Уровень громкости субтона
    val isPlaying: Boolean = false
)