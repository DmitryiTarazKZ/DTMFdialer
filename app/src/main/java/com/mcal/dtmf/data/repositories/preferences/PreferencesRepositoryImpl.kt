package com.mcal.dtmf.data.repositories.preferences

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
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
        private const val PLAY_MUSIC = "play_music"
        private const val FLASH_SIGNAL = "flash_signal"
        private const val DELAY_VOX = "delay_vox"
        private const val DELAY_VOX1 = "delay_vox1"
        private const val DELAY_VOX2 = "delay_vox2"
        private const val IS_EMERGENCY = "is_emergency"
        private const val IS_EMERGENCY1 = "is_emergency1"
        private const val IS_EMERGENCY2 = "is_emergency2"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val _numberA: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _numberB: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _numberC: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _numberD: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _serviceNumber: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _playMusic: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _flashSignal: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _delayMusic: MutableStateFlow<Long?> = MutableStateFlow(null)
    private val _delayMusic1: MutableStateFlow<Long?> = MutableStateFlow(null)
    private val _delayMusic2: MutableStateFlow<Long?> = MutableStateFlow(null)
    private val _connType: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _soundSource: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _soundTest: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _soundSourceAvailability: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())
    private val sampleRate = 16000 // Частота дискретизации запись звука
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // Конфигурация канала моно
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // Формат записи
    private val blockSize = 1024 // Размер буфера записи


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

    override fun getConnTypeFlow(): Flow<String> = flow {
        if (_connType.value == null) {
            try {
                getConnType()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_connType.filterNotNull())
    }

    override fun getConnType(): String {
        val default = "Репитер (2 Канала+)" // Значение по умолчанию
        return _connType.value ?: prefs.getString(IS_EMERGENCY, default) ?: default
    }

    override fun setConnType(value: String) {
        prefs.edit().putString(IS_EMERGENCY, value).apply()
        _connType.update { value }
    }

    /**
     * Получение значения источника звука для электронной VOX системы
     */

    override fun getSoundSourceFlow(): Flow<String> = flow {
        if (_soundSource.value == null) {
            try {
                getSoundSource()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_soundSource.filterNotNull())
    }

    override fun getSoundSource(): String {
        val default = "MIC" // Значение по умолчанию
        return _soundSource.value ?: prefs.getString(IS_EMERGENCY1, default) ?: default
    }

    override fun setSoundSource(value: String) {
        prefs.edit().putString(IS_EMERGENCY1, value).apply()
        _soundSource.update { value }
    }

    /**
     * Тестирование доступных источников
     */

    override fun getSoundTestFlow(): Flow<Boolean> = flow {
        if (_soundTest.value == null) {
            try {
                getSoundTest()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    override fun getSoundTest(): Boolean {
        return _soundTest.value ?: prefs.getBoolean(IS_EMERGENCY2, false)
    }

    override fun setSoundTest(enabled: Boolean, onSourceTested: (String, Boolean) -> Unit) {
        prefs.edit().putBoolean(IS_EMERGENCY2, enabled).apply()
        if (enabled) {
            // Проверяем разрешение на запись аудио
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                _soundTest.update { false }
                return
            }

            val audioSources = listOf(
                "MIC", "VOICE_UPLINK", "VOICE_DOWNLINK", "VOICE_CALL",
                "CAMCORDER", "VOICE_RECOGNITION", "VOICE_COMMUNICATION",
                "REMOTE_SUBMIX", "UNPROCESSED", "VOICE_PERFORMANCE"
            )

            // Создаем карту доступности источников звука
            val soundSourceAvailability = mutableMapOf<String, Boolean>()

            // Запускаем тестирование в отдельном потоке
            Thread {
                audioSources.forEach { sourceName ->
                    try {
                        val soundSource = when (sourceName) {
                            "MIC" -> MediaRecorder.AudioSource.MIC
                            "VOICE_UPLINK" -> MediaRecorder.AudioSource.VOICE_UPLINK
                            "VOICE_DOWNLINK" -> MediaRecorder.AudioSource.VOICE_DOWNLINK
                            "VOICE_CALL" -> MediaRecorder.AudioSource.VOICE_CALL
                            "CAMCORDER" -> MediaRecorder.AudioSource.CAMCORDER
                            "VOICE_RECOGNITION" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                            "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            "REMOTE_SUBMIX" -> MediaRecorder.AudioSource.REMOTE_SUBMIX
                            "UNPROCESSED" -> MediaRecorder.AudioSource.UNPROCESSED
                            "VOICE_PERFORMANCE" -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
                            else -> MediaRecorder.AudioSource.DEFAULT
                        }

                        // Используем фиксированный размер буфера
                        val bufferSize = blockSize
                        var audioRecord: AudioRecord? = null
                        try {
                            audioRecord = AudioRecord(
                                soundSource, sampleRate, channelConfig,
                                audioFormat, bufferSize
                            )

                            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                                audioRecord.startRecording()
                                Thread.sleep(200) // Увеличена задержка для стабильности
                                audioRecord.stop()
                                soundSourceAvailability[sourceName] = true
                                onSourceTested(sourceName, true) // Сообщаем об успехе
                            } else {
                                soundSourceAvailability[sourceName] = false
                                onSourceTested(sourceName, false) // Сообщаем о неудаче
                            }
                        } catch (e: Exception) {
                            soundSourceAvailability[sourceName] = false
                            onSourceTested(sourceName, false) // Сообщаем о неудаче
                        } finally {
                            audioRecord?.release()
                        }

                    } catch (e: Exception) {
                        soundSourceAvailability[sourceName] = false
                        onSourceTested(sourceName, false) // Сообщаем о неудаче
                    }
                }
            }.start()

            // Обновляем доступность источников звука
            _soundSourceAvailability.update { soundSourceAvailability }
        }
        _soundTest.update { enabled }
    }

    /**
     *  Включение/Отключение озвучивания
     */
    override fun getPlayMusicFlow(): Flow<Boolean> = flow {
        if (_playMusic.value == null) {
            try {
                getPlayMusic()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_playMusic.filterNotNull())
    }

    override fun getPlayMusic(): Boolean {
        return _playMusic.value ?: prefs.getBoolean(PLAY_MUSIC, false)
    }

    override fun setPlayMusic(enabled: Boolean) {
        prefs.edit().putBoolean(PLAY_MUSIC, enabled).apply()
        _playMusic.update { enabled }
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
     * Активация VOX системы посредством начального проигрывания тона 1000гц заданной длительности
     */
    override fun getDelayMusicFlow(): Flow<Long> = flow {
        if (_delayMusic.value == null) {
            try {
                getDelayMusic()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_delayMusic.filterNotNull())
    }

    override fun getDelayMusic(): Long {
        return _delayMusic.value ?: prefs.getLong(DELAY_VOX, 250L)
    }

    override fun setDelayMusic(ms: Long) {
        prefs.edit().putLong(DELAY_VOX, ms).apply()
        _delayMusic.update { ms }
    }

    /**
     * Настройка VOX системы время удержания
     */
    override fun getDelayMusic1Flow(): Flow<Long> = flow {
        if (_delayMusic1.value == null) {
            try {
                getDelayMusic1()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_delayMusic1.filterNotNull())
    }

    override fun getDelayMusic1(): Long {
        return _delayMusic1.value ?: prefs.getLong(DELAY_VOX1, 500L)
    }

    override fun setDelayMusic1(ms: Long) {
        prefs.edit().putLong(DELAY_VOX1, ms).apply()
        _delayMusic1.update { ms }
    }

    /**
     * Настройка VOX системы порог срабатывания
     */
    override fun getDelayMusic2Flow(): Flow<Long> = flow {
        if (_delayMusic2.value == null) {
            try {
                getDelayMusic2()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_delayMusic2.filterNotNull())
    }

    override fun getDelayMusic2(): Long {
        val default = 500L // Значение по умолчанию
        return _delayMusic2.value ?: prefs.getLong(DELAY_VOX2, default)
    }

    override fun setDelayMusic2(ms: Long) {
        prefs.edit().putLong(DELAY_VOX2, ms).apply()
        _delayMusic2.update { ms }
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
