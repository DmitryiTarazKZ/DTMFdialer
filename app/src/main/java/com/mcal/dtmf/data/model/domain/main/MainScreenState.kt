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
    val input: String = "", // Переменная поля ввода
    val spectrum: Spectrum? = null, // Значение для отрисовки спектра
    val key: Char = ' ', // Значение из блока Фурье
    val numberA: String = "", // Номер быстрого набора A
    val numberB: String = "", // Номер быстрого набора B
    val numberC: String = "", // Номер быстрого набора C
    val numberD: String = "", // Номер быстрого набора D
    var modeSelection: String = "", // Выбор режима работы
    val callDirection: Int = CallDirection.DIRECTION_UNKNOWN, // Направление вызова
    val callState: Int = Call.STATE_DISCONNECTED, // Состояние вызова
    val isSpeakerOn: Boolean = false, // Значение для громкоговорителя
    val micClickKeyCode: Int = 0, // Значение кодов нажатия кнопок на гарнитуре
    val timer: Int = 0, // Значение основного таймера
    val isPowerConnected: Boolean = false, // Значение подключено ли зарядное устройство
    val isConnect: Boolean = false, // Значение подключенны ли наушники
    val flashlight: Boolean = false, // Значение включена ли вспышка
    var outputFrequency1: Float = 0f, // Истинное значение частоты звука

)
