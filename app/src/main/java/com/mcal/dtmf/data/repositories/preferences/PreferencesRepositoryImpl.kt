package com.mcal.dtmf.data.repositories.preferences


import android.content.Context
import androidx.preference.PreferenceManager
import com.mcal.dtmf.data.repositories.main.LogLevel
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.utils.LogManager
import kotlinx.coroutines.flow.*



class PreferencesRepositoryImpl(
    private val context: Context,
) : PreferencesRepository {
    companion object {
        private const val NUMBER_A = "number_a"
        private const val NUMBER_B = "number_b"
        private const val NUMBER_C = "number_c"
        private const val NUMBER_D = "number_d"
        private const val NUMBER_SERVICE = "number_service"
        private const val FLASH_SIGNAL = "flash_signal"
        private const val ERROR_CONTROL = "error_control"
        private const val DELAY_VOX = "delay_vox"
        private const val DELAY_VOX1 = "delay_vox1"
        private const val DELAY_VOX2 = "delay_vox2"
        private const val IS_EMERGENCY = "is_emergency"
        private const val IS_EMERGENCY1 = "is_emergency1"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val _numberA: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _numberB: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _numberC: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _numberD: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _serviceNumber: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _flashSignal: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _errorControl: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _voxActivation: MutableStateFlow<Long?> = MutableStateFlow(null)
    private val _voxHold: MutableStateFlow<Long?> = MutableStateFlow(null)
    private val _voxThreshold: MutableStateFlow<Long?> = MutableStateFlow(null)
    private val _modeSelection: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _voxSetting: MutableStateFlow<Boolean?> = MutableStateFlow(null)


    /**
     * Сервисный номер
     */

    override fun getServiceNumberFlow(): Flow<String> = flow {
        if (_serviceNumber.value == null) {
            try {
                getServiceNumber()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_serviceNumber.filterNotNull())
    }

    override fun getServiceNumber(): String {
        return _serviceNumber.value ?: prefs.getString(NUMBER_SERVICE, "") ?: ""
    }

    override fun setServiceNumber(number: String) {
        prefs.edit().putString(NUMBER_SERVICE, number).apply()
        _serviceNumber.update { number }
    }

    /**
     * Получение значения выбранного типа связи
     */

    override fun getModeSelectionFlow(): Flow<String> = flow {
        if (_modeSelection.value == null) {
            try {
                getModeSelection()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_modeSelection.filterNotNull())
    }

    override fun getModeSelection(): String {
        val default = "Репитер (2 Канала+)" // Значение по умолчанию
        return _modeSelection.value ?: prefs.getString(IS_EMERGENCY, default) ?: default
    }

    override fun setModeSelection(value: String) {
        prefs.edit().putString(IS_EMERGENCY, value).apply()
        _modeSelection.update { value }
    }

    /**
     * Отладка порога и удержания VOX
     */

    override fun getVoxSettingFlow(): Flow<Boolean> = flow {
        if (_voxSetting.value == null) {
            try {
                getVoxSetting()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_voxSetting.filterNotNull())
    }

    override fun getVoxSetting(): Boolean {
        return _voxSetting.value ?: prefs.getBoolean(IS_EMERGENCY1, false)
    }

    override fun setVoxSetting(enabled: Boolean) {
        prefs.edit().putBoolean(IS_EMERGENCY1, enabled).apply()
        _voxSetting.update { enabled }
    }

    /**
     *  Включение/Отключение сигнальной вспышки в режиме супертелефон
     */
    override fun getFlashSignalFlow(): Flow<Boolean> = flow {
        if (_flashSignal.value == null) {
            try {
                getFlashSignal()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_flashSignal.filterNotNull())
    }

    override fun getFlashSignal(): Boolean {
        return _flashSignal.value ?: prefs.getBoolean(FLASH_SIGNAL, false)
    }

    override fun setFlashSignal(enabled: Boolean) {
        prefs.edit().putBoolean(FLASH_SIGNAL, enabled).apply()
        _flashSignal.update { enabled }
    }

    /**
     *  Включение/Отключение сигнальной вспышки в режиме супертелефон
     */
    override fun getErrorControlFlow(): Flow<Boolean> = flow {
        if (_errorControl.value == null) {
            try {
                getFlashSignal()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_errorControl.filterNotNull())
    }

    override fun getErrorControl(): Boolean {
        return _errorControl.value ?: prefs.getBoolean(ERROR_CONTROL, false)
    }

    override fun setErrorControl(enabled: Boolean) {
        prefs.edit().putBoolean(ERROR_CONTROL, enabled).apply()
        _errorControl.update { enabled }
    }

    /**
     * Активация VOX системы посредством начального проигрывания тона 1000гц заданной длительности
     */
    override fun getVoxActivationFlow(): Flow<Long> = flow {
        if (_voxActivation.value == null) {
            try {
                getVoxActivation()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_voxActivation.filterNotNull())
    }

    override fun getVoxActivation(): Long {
        return _voxActivation.value ?: prefs.getLong(DELAY_VOX, 250L)
    }

    override fun setVoxActivation(ms: Long) {
        prefs.edit().putLong(DELAY_VOX, ms).apply()
        _voxActivation.update { ms }
    }

    /**
     * Настройка VOX системы время удержания
     */
    override fun getVoxHoldFlow(): Flow<Long> = flow {
        if (_voxHold.value == null) {
            try {
                getVoxHold()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_voxHold.filterNotNull())
    }

    override fun getVoxHold(): Long {
        return _voxHold.value ?: prefs.getLong(DELAY_VOX1, 500L)
    }

    override fun setVoxHold(ms: Long) {
        prefs.edit().putLong(DELAY_VOX1, ms).apply()
        _voxHold.update { ms }
    }

    /**
     * Настройка VOX системы порог срабатывания
     */
    override fun getVoxThresholdFlow(): Flow<Long> = flow {
        if (_voxThreshold.value == null) {
            try {
                getVoxThreshold()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_voxThreshold.filterNotNull())
    }

    override fun getVoxThreshold(): Long {
        val default = 500L // Значение по умолчанию
        return _voxThreshold.value ?: prefs.getLong(DELAY_VOX2, default)
    }

    override fun setVoxThreshold(ms: Long) {
        prefs.edit().putLong(DELAY_VOX2, ms).apply()
        _voxThreshold.update { ms }
    }


    /**
     * Закрепленный номер за буквой A
     */
    override fun getNumberAFlow(): Flow<String> = flow {
        if (_numberA.value == null) {
            try {
                getNumberA()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_numberA.filterNotNull())
    }

    override fun getNumberA(): String {
        return _numberA.value ?: prefs.getString(NUMBER_A, "") ?: ""
    }

    override fun setNumberA(number: String) {
        prefs.edit().putString(NUMBER_A, number).apply()
        _numberA.update { number }
    }

    /**
     * Закрепленный номер за буквой B
     */
    override fun getNumberBFlow(): Flow<String> = flow {
        if (_numberB.value == null) {
            try {
                getNumberB()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_numberB.filterNotNull())
    }

    override fun getNumberB(): String {
        return _numberB.value ?: prefs.getString(NUMBER_B, "") ?: ""
    }

    override fun setNumberB(number: String) {
        prefs.edit().putString(NUMBER_B, number).apply()
        _numberB.update { number }
    }

    /**
     * Закрепленный номер за буквой C
     */
    override fun getNumberCFlow(): Flow<String> = flow {
        if (_numberC.value == null) {
            try {
                getNumberC()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_numberC.filterNotNull())
    }

    override fun getNumberC(): String {
        return _numberC.value ?: prefs.getString(NUMBER_C, "") ?: ""
    }

    override fun setNumberC(number: String) {
        prefs.edit().putString(NUMBER_C, number).apply()
        _numberC.update { number }
    }

    /**
     * Закрепленный номер за буквой D
     */
    override fun getNumberDFlow(): Flow<String> = flow {
        if (_numberD.value == null) {
            try {
                getNumberD()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_numberD.filterNotNull())
    }

    override fun getNumberD(): String {
        return _numberD.value ?: prefs.getString(NUMBER_D, "") ?: ""
    }

    override fun setNumberD(number: String) {
        prefs.edit().putString(NUMBER_D, number).apply()
        _numberD.update { number }
    }
}
