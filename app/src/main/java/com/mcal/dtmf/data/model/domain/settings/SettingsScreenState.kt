package com.mcal.dtmf.data.model.domain.settings

data class SettingsScreenState(
    val isFlashSignal: Boolean = false, // Переключатель сигнальной вспышки
    val isErrorControl: Boolean = false, // Переключатель для записи ошибок
    val serviceNumber: String = "", // Сервисный номер
    var voxActivation: Long = 150L, // время на которое прозвучит тон 1000гц
    var voxHold: Long = 500L, // Время удержания вспышки в режиме VOX
    var voxThreshold: Long = 500L, // Порог срабатывания вспышки в режиме VOX
    var modeSelection: String = "", // Выбор режима работы
    var voxSetting: Boolean = false // Переключатель отладки VOX системы
)
