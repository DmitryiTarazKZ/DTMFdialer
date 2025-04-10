package com.mcal.dtmf.data.repositories.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Call
import android.util.Log
import androidx.core.app.ActivityCompat
import com.mcal.dtmf.recognizer.DataBlock
import com.mcal.dtmf.recognizer.Recognizer
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.recognizer.StatelessRecognizer
import com.mcal.dtmf.service.DtmfService
import com.mcal.dtmf.utils.Utils
import com.mcal.dtmf.utils.Utils.Companion.batteryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class MainRepositoryImpl(
    private val context: Application,
    private var textToSpeech: TextToSpeech,

    ) : MainRepository {
    private var utils = Utils(this, CoroutineScope(Dispatchers.IO))
    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)
    private var recorderJob = scope
    private var recognizerJob = scope
    private var playSoundJob = scope

    private val _spectrum: MutableStateFlow<Spectrum?> = MutableStateFlow(null)
    private val _key: MutableStateFlow<Char?> = MutableStateFlow(null)
    private val _input: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _input1: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _call: MutableStateFlow<Call?> = MutableStateFlow(null)
    private val _callState: MutableStateFlow<Int> = MutableStateFlow(Call.STATE_DISCONNECTED)
    private val _powerState: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _amplitudeCheck: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _sim: MutableStateFlow<Int> = MutableStateFlow(0)
    private val recordedFiles = mutableListOf<String>() // Список для хранения путей к голосовым заметкам

    private val blockingQueue = LinkedBlockingQueue<DataBlock>()
    private val recognizer = Recognizer()
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val blockSize = 1024
    private var audioRecord: AudioRecord? = null
    private var volumeLevelTts = 80
    private var volumeLevelCall = 80
    private var isTorchOnIs = 0
    private var isSpeaking = false
    private var lastDialedNumber: String = ""
    private var voxActivation = 500L
    private var numberA = ""
    private var numberB = ""
    private var numberC = ""
    private var numberD = ""
    private var isTorchOn = false
    private var flagVox = false
    private var durationVox = 50L
    private var periodVox = 2500L
    private var ton = 0
    private var block = false
    private var availableMB = 0L

    private var audioRecord1: AudioRecord? = null
    private var recordedFilePath: String? = null
    private var isRecording = false
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var volumeLevelCtcss: Double = 0.1  // ниже 0.05 перестает срабатывать если поднять выше 0.15 тон становится слышен в приемном тракте
    private var frequencyCtcss: Double = 0.0  // Субтона по умолчанию отключены

    init {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        if (notificationManager.isNotificationPolicyAccessGranted()) {
            if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                try {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        } else {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) { // Проверка на оптимизацию
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        }

        // Устанавливаем громкость для каждого потока отдельно
        val volumeSettings = mapOf(
            AudioManager.STREAM_RING to 0.7,
            AudioManager.STREAM_MUSIC to 0.9,
            AudioManager.STREAM_NOTIFICATION to 0.7,
            AudioManager.STREAM_SYSTEM to 0.7,
            AudioManager.STREAM_VOICE_CALL to 0.9,
            AudioManager.STREAM_ALARM to 0.7,
            AudioManager.STREAM_DTMF to 0.7,
        )

        volumeSettings.forEach { (stream, volumeFraction) ->
            audioManager.setStreamVolume(
                stream,
                (audioManager.getStreamMaxVolume(stream) * volumeFraction).toInt(),
                0
            )
        }
    }

    private fun setVolumeTts(level: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * level / 100),
            0
        )
    }

    private fun setVolumeCall(level: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            (audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * level / 100),
            0
        )
    }

    // Контрольные тона для отладки VOX
    private fun playDtmfTones(period: Long, duration: Long) {
        playSoundJob.launch {
            val dtmfDigits = listOf(
                ToneGenerator.TONE_DTMF_0,
                ToneGenerator.TONE_DTMF_1,
                ToneGenerator.TONE_DTMF_2,
                ToneGenerator.TONE_DTMF_3,
                ToneGenerator.TONE_DTMF_4,
                ToneGenerator.TONE_DTMF_5,
                ToneGenerator.TONE_DTMF_6,
                ToneGenerator.TONE_DTMF_7,
                ToneGenerator.TONE_DTMF_8,
                ToneGenerator.TONE_DTMF_9,
                ToneGenerator.TONE_DTMF_P
            )

            for (i in dtmfDigits.indices) {
              delay(period) // период проигрывания
            playDtmfTone(dtmfDigits[i], period, duration)
            }
        }
    }

    private fun playDtmfTone(tone: Int, period: Long, duration: Long) {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100) // 100 - громкость
        playSoundJob.launch {
            toneGenerator.startTone(tone, duration.toInt()) // Длительность тона
            delay(period) // период проигрывания
            toneGenerator.release()
        }
    }

    private fun voxActivation(delayMillis: Long = 2000, onComplete: suspend () -> Unit) {
        val sampleRate = 44100 // Частота дискретизации
        val frequency = 800.0 // Частота звука (не допустимо выбирать частоту активации кратной или равной тонам определения)
        val duration = voxActivation // Длительность звука в миллисекундах
        val bufferSize = (sampleRate * duration / 1000).toInt() // Размер буфера

        playSoundJob.launch {
            try {
                delay(delayMillis)

                // Создание звука
                val buffer = ShortArray(bufferSize)
                val angleIncrement = 2.0 * Math.PI * frequency / sampleRate
                var angle = 0.0

                for (i in buffer.indices) {
                    buffer[i] = (Short.MAX_VALUE * sin(angle)).toInt().toShort()
                    angle += angleIncrement
                    if (angle >= 2 * Math.PI) angle -= 2 * Math.PI
                }

                // Воспроизведение звука
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()
                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.stop()
                audioTrack.release()

                onComplete()
            } catch (e: CancellationException) {
                // Обработка отмены
            }
        }
    }

    private fun playCTCSS(frequency: Double) {
        val sampleRate = 44100 // Частота дискретизации
        val lowCutoffFrequency = frequency * 0.8 // Нижняя частота среза (например, 80% от частоты)
        val highCutoffFrequency = frequency * 1.2 // Верхняя частота среза (например, 120% от частоты)
        val order = 2 // Порядок фильтра

        // Инициализация AudioTrack
        if (audioTrack == null) {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }

        // Проверка состояния AudioTrack
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e("AudioTrack", "AudioTrack не инициализирован")
            return
        }

        if (!isPlaying) {
            audioTrack?.play()
            isPlaying = true

            val bufferSize = sampleRate / 2 // Увеличиваем размер буфера для более плавного сигнала
            val buffer = FloatArray(bufferSize) // Используем FloatArray для большей точности
            val angleIncrement = 2.0 * Math.PI * frequency / sampleRate
            var angle = 0.0

            // Параметры фильтра Баттерворта
            val a = DoubleArray(order + 1) // Коэффициенты фильтра
            val b = DoubleArray(order + 1) // Коэффициенты фильтра
            val x = DoubleArray(order + 1) // Входные значения
            val y = DoubleArray(order + 1) // Выходные значения
            val yHigh = DoubleArray(order + 1) // Выходные значения для верхнего фильтра

            // Расчет коэффициентов фильтра Баттерворта для полосового фильтра
            val nyquist = sampleRate / 2.0
            val normalizedLowCutoff = lowCutoffFrequency / nyquist
            val normalizedHighCutoff = highCutoffFrequency / nyquist

            // Расчет коэффициентов для нижнего фильтра
            val wcLow = tan(PI * normalizedLowCutoff)
            val kLow = 1.0 / (1.0 + sqrt(2.0) * wcLow + wcLow * wcLow)
            b[0] = kLow
            b[1] = 2 * b[0]
            b[2] = b[0]
            a[1] = 2 * (1 - wcLow * wcLow) * kLow
            a[2] = (1 - sqrt(2.0) * wcLow + wcLow * wcLow) * kLow

            // Расчет коэффициентов для верхнего фильтра
            val wcHigh = tan(PI * normalizedHighCutoff)
            val kHigh = 1.0 / (1.0 + sqrt(2.0) * wcHigh + wcHigh * wcHigh)
            val bHigh = DoubleArray(order + 1)
            val aHigh = DoubleArray(order + 1)
            bHigh[0] = kHigh
            bHigh[1] = 2 * bHigh[0]
            bHigh[2] = bHigh[0]
            aHigh[1] = 2 * (1 - wcHigh * wcHigh) * kHigh
            aHigh[2] = (1 - sqrt(2.0) * wcHigh + wcHigh * wcHigh) * kHigh

            playSoundJob.launch {
                while (isPlaying) {
                    for (i in 0 until bufferSize) {
                        val rawValue = volumeLevelCtcss * sin(angle) // Генерация синусоиды

                        // Применение нижнего фильтра Баттерворта
                        x[0] = rawValue
                        y[0] = b[0] * x[0] + b[1] * x[1] + b[2] * x[2] - a[1] * y[1] - a[2] * y[2]
                        // Сдвиг значений
                        for (j in order downTo 1) {
                            x[j] = x[j - 1]
                            y[j] = y[j - 1]
                        }

                        // Применение верхнего фильтра Баттерворта
                        val filteredValue = y[0]
                        x[0] = filteredValue
                        yHigh[0] = bHigh[0] * x[0] + bHigh[1] * x[1] + bHigh[2] * x[2] - aHigh[1] * yHigh[1] - aHigh[2] * yHigh[2]
                        // Сдвиг значений для верхнего фильтра
                        for (j in order downTo 1) {
                            x[j] = x[j - 1]
                            yHigh[j] = yHigh[j - 1]
                        }

                        buffer[i] = yHigh[0].toFloat() // Запись отфильтрованного значения
                        angle += angleIncrement
                        if (angle >= 2 * Math.PI) angle -= 2 * Math.PI
                    }

                    // Преобразование FloatArray в ByteArray
                    val byteBuffer = ByteArray(bufferSize * 2)
                    for (i in buffer.indices) {
                        val value = (buffer[i] * 32767).toInt() // Преобразование в 16-битный формат
                        // Ограничиваем значения, чтобы избежать клиппинга
                        val clampedValue = value.coerceIn(-32768, 32767)
                        byteBuffer[2 * i] = (clampedValue and 0xFF).toByte()
                        byteBuffer[2 * i + 1] = (clampedValue shr 8 and 0xFF).toByte()
                    }

                    // Запись в AudioTrack с обработкой ошибок
                    val result = audioTrack?.write(byteBuffer, 0, byteBuffer.size)
                    if (result == AudioTrack.ERROR_INVALID_OPERATION) {
                        Log.e("AudioTrack", "Ошибка записи: INVALID_OPERATION")
                        break
                    } else if (result == AudioTrack.ERROR) {
                        Log.e("AudioTrack", "Ошибка записи: ERROR")
                        break
                    }
                }
            }
        }
    }

    private fun stopPlayback() {
        if (isPlaying) {
            isPlaying = false
            audioTrack?.stop()
            // audioTrack?.release() // Не освобождайте, если хотите использовать его повторно
            // audioTrack = null // Не обнуляйте, если хотите использовать его повторно
        }
    }

    override fun getCallFlow(): Flow<Call?> = flow {
        emitAll(_call)
    }

    override fun getCall(): Call? {
        return _call.value
    }

    override fun setCall(call: Call?) {
        _call.update { call }
    }

    override fun getCallStateFlow(): Flow<Int> = flow {
        emitAll(_callState)
    }

    override fun getCallState(): Int {
        return _callState.value
    }

    override fun setCallState(callState: Int) {
        Log.d("Контрольный лог", "ЗНАЧЕНИЕ callState: $callState ЗНАЧЕНИЕ frequencyCtcss: $frequencyCtcss")
        if (frequencyCtcss != 0.0 && callState == 4) {
            playCTCSS(frequencyCtcss)
            Log.d("Контрольный лог", "УСЛОВИЕ  ВНУТРИ CallState СРАБОТАЛО ЗАПУСК")
        } // Генерируем субтон в поток вызова если трубку подняли
        if (frequencyCtcss != 0.0 && callState == 7) {
            stopPlayback()
            Log.d("Контрольный лог", "УСЛОВИЕ  ВНУТРИ CallState СРАБОТАЛО ОСТАНОВ")
        } // Прекращаем генерацию если вызов завершен
        _callState.update { callState }
    }

    override fun getKeyFlow(): Flow<Char> = flow {
        if (_key.value == null) {
            try {
                getKey()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_key.filterNotNull())
    }

    override fun getKey(): Char? {
        return _key.value
    }

    override fun setKey(key: Char) {
        _key.update { key }
    }

    override fun getInputFlow(): Flow<String> = flow {
        if (_input.value == null) {
            try {
                getInput()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_input.filterNotNull())
    }

    override fun getInput(): String? {
        return _input.value
    }

    override fun setInput(value: String) {
        _input.update { value }
    }

    override fun getInput1Flow(): Flow<String> = flow {
        if (_input1.value == null) {
            try {
                getInput1()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_input.filterNotNull())
    }

    override fun getInput1(): String? {
        return _input1.value
    }

    override fun setInput1(value: String) {
        _input1.update { value }
    }

    override fun getPowerFlow(): Flow<Boolean> = flow {
        if (_powerState.value == null) {
            try {
                getPower()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_powerState.filterNotNull())
    }

    override fun getPower(): Boolean {
        return _powerState.value ?: false
    }

    override fun setPower(value: Boolean) {
        val previousValue = _powerState.value
        if (previousValue == true && !value) {
            speakText("Внимание! Произошло отключение питания. Устройство работает от собственного аккумулятора", false)
        }
        if (previousValue == false && value) {
            speakText("Питание устройства возобновлено. Аккумулятор заряжается", false)
        }
        _powerState.update { value }
    }

    override fun getAmplitudeCheckFlow(): Flow<Boolean> = flow {
        if (_amplitudeCheck.value == null) {
            try {
                getPower()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_amplitudeCheck.filterNotNull())
    }

    override fun getAmplitudeCheck(): Boolean {
        return _amplitudeCheck.value ?: true
    }

    override fun setAmplitudeCheck(value: Boolean) {
        _amplitudeCheck.update { value }
    }

    override fun getSimFlow(): Flow<Int> = flow {
        emitAll(_sim)
    }

    override fun getSim(): Int {
        return _sim.value
    }

    override fun setSim(value: Int) {
        _sim.update { value }
    }

    // Запись с микрофона в массив для анализа DTMF
    @SuppressLint("MissingPermission")
    override suspend fun record() {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        ).also { audio ->
            runCatching {
                val buffer = ShortArray(blockSize)
                audio.startRecording()
                coroutineScope {
                    var previousAmplitudeCheck = false // Предыдущее состояние флага
                    while (isActive) {
                        val bufferReadSize = audio.read(buffer, 0, blockSize)
                        val dataBlock = DataBlock(buffer, blockSize, bufferReadSize)
                        blockingQueue.put(dataBlock)
                        val amplitude = calculateAmplitude(buffer, bufferReadSize)
                        //Log.d("Контрольный лог", "АМПЛИТУДА: $amplitude")
                        val currentAmplitudeCheck = amplitude > 0
                        if (currentAmplitudeCheck != previousAmplitudeCheck) {
                            setAmplitudeCheck(currentAmplitudeCheck)
                            previousAmplitudeCheck = currentAmplitudeCheck
                        }
                    }
                }
            }
            audio.stop()
        }
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        availableMB = getAvailableMemoryInMB()
        if (availableMB < 5) {
            speakText("В памяти нет места для записи, осталось всего 5 мегабайт. Освободите память", false)
            return
        }
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) {
            return
        }
        val fileName = "voice_note_${System.currentTimeMillis()}.pcm" // Сохраняем в формате PCM
        recordedFilePath = File(context.getExternalFilesDir(null), fileName).absolutePath // Устанавливаем путь к файлу
        audioRecord1 = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (audioRecord1?.state != AudioRecord.STATE_INITIALIZED) {
            return
        }
        audioRecord1?.startRecording()
        isRecording = true
        val buffer = ShortArray(bufferSize)
        FileOutputStream(recordedFilePath).use { fos ->
            while (isRecording) {
                val readSize = audioRecord1?.read(buffer, 0, buffer.size) ?: 0
                if (readSize > 0) {
                    val byteBuffer = ByteArray(readSize * 2)
                    for (i in 0 until readSize) {
                        val amplifiedSample = (buffer[i] * 4).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        byteBuffer[i * 2] = (amplifiedSample and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (amplifiedSample shr 8 and 0xFF).toByte()
                    }
                    fos.write(byteBuffer)
                } else {
                    // speakText("Ошибка чтения с буфера", false)
                }
            }
        }
        audioRecord1?.stop()
        audioRecord1?.release()
        audioRecord1 = null
        recordedFiles.add(recordedFilePath!!)
    }

    // Функция для получения доступной памяти в мегабайтах
    private fun getAvailableMemoryInMB(): Long {
        val storageStat = StatFs(context.getExternalFilesDir(null)?.absolutePath)
        val availableBytes = storageStat.availableBlocksLong * storageStat.blockSizeLong
        return availableBytes / (1024 * 1024) // Преобразуем байты в мегабайты
    }

    // Функция для остановки записи
    private fun stopRecording() {
        try {
            if (isRecording) {
                audioRecord1?.stop() // Останавливаем запись
                audioRecord1?.release() // Освобождаем ресурсы
                audioRecord1 = null // Сбрасываем ссылку
                isRecording = false // Устанавливаем флаг записи в false

                val message = if (availableMB < 100) {
                    "Запись сохранена. Заканчивается память, осталось $availableMB мегабайт"
                } else {
                    "Запись сохранена."
                }
                speakText(message, false)
            }
        } catch (e: IllegalStateException) {
            speakText("Не удалось остановить запись", false)
        }
    }

    // Функция для воспроизведения записанного файла
    private fun playRecordedFile(index: Int, trimDurationMs: Int) {

        if (frequencyCtcss != 0.0) { playCTCSS(frequencyCtcss) } // Для речевых сообщений

        // Проверяем, что индекс находится в допустимом диапазоне
        if (index < 0 || index >= recordedFiles.size) {
            speakText("Записи с таким номером нет", false)
            return
        }

        // Получаем путь к файлу по индексу
        val path = recordedFiles[index]
        val file = File(path)

        if (file.exists()) {
            // Создаем AudioAttributes
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            // Создаем AudioFormat
            val audioFormat = AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO) // Моно
                .setSampleRate(44100) // Частота дискретизации
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT) // 16 бит
                .build()

            // Создаем AudioTrack для воспроизведения в режиме потока
            val audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE // Генерация ID сессии
            )

            // Устанавливаем громкость на 100%
            audioTrack.setVolume(1.0f)

            // Открываем поток для чтения файла
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(1024) // Используем буфер фиксированного размера
                val byteArrayOutputStream = ByteArrayOutputStream()
                var bytesRead: Int

                // Читаем данные из файла и записываем в ByteArrayOutputStream
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead)
                }

                // Получаем массив байтов из ByteArrayOutputStream
                val audioData = byteArrayOutputStream.toByteArray()

                // Рассчитываем количество байтов для обрезки
                val bytesPerSecond = audioFormat.sampleRate * 2 // 2 байта на сэмпл (16 бит)
                val trimBytes = (trimDurationMs * bytesPerSecond) / 1000 // Обрезаем указанное время в миллисекундах

                // Убедимся, что не обрезаем больше, чем есть в аудиофайле
                val trimmedAudioData = if (audioData.size > trimBytes) {
                    audioData.copyOf(audioData.size - trimBytes)
                } else {
                    audioData // Если обрезка больше, чем размер, просто используем оригинал
                }

                // Воспроизводим обрезанный звук
                audioTrack.play() // Начинаем воспроизведение
                audioTrack.write(trimmedAudioData, 0, trimmedAudioData.size)
            }

            audioTrack.stop() // Останавливаем воспроизведение после завершения
            audioTrack.release() // Освобождаем ресурсы
            if (frequencyCtcss != 0.0) { stopPlayback() } // Отключаем субтон
        } else {
            // Если файл не существует, произносим сообщение
            speakText("Нет записанных файлов", false)
        }
    }

    // Функция для удаления записанного файла
    private fun deleteRecordedFile(trackIndex: Int?) {
        if (trackIndex != null) {
            // Удаляем конкретную запись
            val filePath = recordedFiles[trackIndex]
            val file = File(filePath)
            if (file.exists() && file.delete()) {
                speakText("Запись под номером ${trackIndex + 1} успешно удалена", false)
                recordedFiles.removeAt(trackIndex) // Удаляем запись из списка
            } else {
                speakText("Не удалось удалить запись", false)
            }
        } else {
            // Удаляем все записи
            recordedFiles.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete() // Удаляем файл, если он существует
                }
            }
            recordedFiles.clear() // Очищаем список записанных файлов
            speakText("Все голосовые записи успешно удалены", false)
        }
    }

    // Функция для вычисления амплитуды (нужна для теста на блокировку микрофона)
    private fun calculateAmplitude(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += abs(buffer[i].toDouble())
        }
        return sum / readSize
    }

    //Получение данных с блока распознавания
    override suspend fun recognize() {
        coroutineScope {
            while (isActive) {
                val dataBlock = blockingQueue.take()
                val spectrum = dataBlock.FFT()
                spectrum.normalize()
                _spectrum.update { spectrum }
                val statelessRecognizer = StatelessRecognizer(spectrum)
                val key = recognizer.getRecognizedKey(statelessRecognizer.getRecognizedKey())
                clickKey(getInput() ?: "", key)
                setKey(key)
            }
        }
    }

    //Основной блок обработки нажатий клавиш
    override fun clickKey(input: String, key: Char?) {

        if (key == ' ') {
            val cameraId = cameraManager.cameraIdList[0]
            if (isTorchOn) {
                cameraManager.setTorchMode(cameraId, false)
                isTorchOn = false
                if (isTorchOnIs == 555) {
                    if (input != "" && input.length == 2) {
                        ton = input.toInt()
                    }
                    if (ton in 17..97) {
                        voxActivation(1500) { playDtmfTone(ton, 1000, 1000) }
                    } else speakText("Звуковой отклик. Установите значение от 17 до 97", false)
                }
                setInput("")
            }
        }

        val previousKey = _key.value

        if (input.length > 11 && getCallState() == 7) {
            speakText("Поле ввода переполнилось, и поэтому было очищенно",false)
            setInput("")
        }

        if (key != ' ' && key != previousKey) {

            // Обработка нажатий клавиш быстрого набора
            if (key in 'A'..'D' && getCall() == null) {
                playSoundJob.launch {
                    delay(200)
                    val currentNumber = when (key) {
                        'A' -> numberA
                        'B' -> numberB
                        'C' -> numberC
                        'D' -> numberD
                        else -> ""
                    }

                    if (input.length in 3..11) {
                        if (currentNumber != input) {
                            when (input) {
                                numberA -> {
                                    speakText("Этот номер уже закреплен за клавишей А",false)
                                    setInput("")
                                }
                                numberB -> {
                                    speakText("Этот номер уже закреплен за клавишей Б",false)
                                    setInput("")
                                }
                                numberC -> {
                                    speakText("Этот номер уже закреплен за клавишей С",false)
                                    setInput("")
                                }
                                numberD -> {
                                    speakText("Этот номер уже закреплен за клавишей Д",false)
                                    setInput("")
                                }
                                else -> {
                                    val num = utils.numberToText(input)
                                    playSoundJob.launch {
                                        val callerName = utils.getContactNameByNumber(input, context)
                                        speakText(if (callerName.isNullOrEmpty()) {
                                            "Номер $num был закреплен за этой клавишей"
                                        } else {
                                            "Номер абонента $callerName был закреплен за этой клавишей"
                                        },false)
                                    }
                                    when (key) {
                                        'A' -> numberA = input
                                        'B' -> numberB = input
                                        'C' -> numberC = input
                                        'D' -> numberD = input
                                    }
                                    setInput("")
                                }
                            }
                        } else {
                            speakText("Этот номер ранее был закреплен за этой клавишей",false)
                        }
                    } else  {
                        val number = when (key) {
                            'A' -> numberA
                            'B' -> numberB
                            'C' -> numberC
                            'D' -> numberD
                            else -> ""
                        }

                        if (number.length in 3..11) {
                            setInput(number)
                            val num = utils.numberToText(number)
                            val callerName = utils.getContactNameByNumber(number, context)

                            val message = if (callerName.isNullOrEmpty()) {
                                "Будет выполнен вызов абонента $num"
                            } else {
                                "Номер абонента $callerName набран"
                            }

                            speakText(message,false)

                            // Установка времени задержки в зависимости от сообщения
                            val delayTime = if (message == "Будет выполнен вызов абонента $num") {
                                12000
                            } else {
                                7000
                            }

                            delay(delayTime.toDuration(DurationUnit.MILLISECONDS)) // Задержка перед началом вызова
                            DtmfService.callStart(context)

                        } else {
                            speakText("Клавиша свободна, вы можете закрепить за ней любой номер быстрого набора",false)
                        }
                    }
                }
            }

            else if (key == '*') {

                if (input == "") {
                   speakText("Наберите номер", false)
                }

                // Прием входящего вызова по нажатию звездочки
                if (getCall() != null) {
                    if (getCallState() == 2) {
                        DtmfService.callAnswer(context)
                        setInput("")
                    }
                }

                // Удаленная проверка пропущенного вызова по команде 0*
                else if (input == "0" && getCall() == null) {
                    utils.lastMissed(context)
                }

                // Удаленное сообщение о текущем времени по команде 1*
                else if (input == "1" && getCall() == null) {
                    utils.speakCurrentTime()
                    setInput("")
                }

                // Удаленный контроль температуры и заряда аккумулятора по команде 2*
                else if (input == "2" && getCall() == null) {
                    val (temperatureText, levelText) = batteryStatus(context)
                    speakText("Температура батареи $temperatureText. Заряд батареи $levelText. Зарядное устройство: ${if (getPower()) "Подключено. " else "Не подключено. "} ",false )
                    setInput("")
                }

                // Очистка номеров быстрого набора по команде 4*
                else if (input == "3" && getCall() == null) {
                    textToSpeech.setOnUtteranceProgressListener(null)
                    numberA = ""
                    numberB = ""
                    numberC = ""
                    numberD = ""
                    lastDialedNumber = ""
                    speakText("Все номера быстрого набора, были очищены", false)
                    setInput("")
                }

                // Удаленная проверка последнего СМС по команде 4*
                else if (input == "4" && getCall() == null) {
                    val smsText =  utils.getLastIncomingSms(context)
                    speakText("$smsText",false)
                    setInput("")
                }

                // Проверка свободной памяти 9999*
                else if (input == "9999" && getCall() == null) {
                    val mb = getAvailableMemoryInMB()
                    val memoryMessage = if (mb > 1000) {
                        "Объем свободной памяти составляет ${mb / 1024} гигабайта" // Преобразуем в ГБ
                    } else {
                        "Объем свободной памяти составляет $mb мегабайт"
                    }
                    speakText(memoryMessage, false)
                    setInput("")
                }

                // Голосовой поиск абонента в телефонной книге по команде 5*
                else if (input == "5" && getCall() == null) {
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Назовите имя абонента которому вы хотите позвонить", false)
                            setInput("")
                            delay(7000)
                            withContext(Dispatchers.Main) {
                                stopDtmf()
                                utils.startSpeechRecognition(context) { result ->
                                   startDtmf()
                                    setInput1("")
                                    if (result != null) {
                                        utils.getNameContact(result, context)
                                    } else {
                                        speakText("Не удалось распознать сказанное. Попробуйте еще раз", false)
                                        setInput1("")
                                    }
                                }
                            }
                        } else {
                            speakText("Отсутствует интернет. Голосовой поиск не доступен",false)
                            setInput("")
                            setInput1("")
                        }
                    }
                }

                // Запись голосовой заметки
                else if (input == "7" && getCall() == null) {
                    playSoundJob.launch {
                        val noteCount = recordedFiles.size
                        val message = if (noteCount == 0) {
                            "Голосовая запись номер один, можете говорить"
                        } else {
                            "Голосовая запись номер ${noteCount + 1}, можете говорить"
                        }
                        speakText(message, false)
                        delay(6000)
                        startRecording()
                    }
                    setInput("")
                }

                // Удаление голосовой заметки
                else if (input == "8" && getCall() == null) {
                    playSoundJob.launch {
                        val noteCount = recordedFiles.size
                        Log.e("DeleteRecordedFile", "Значение $noteCount")
                        if (noteCount == 0) {
                            speakText("Нет данных для удаления", false)
                            setInput("")
                        } else {
                            speakText("Введите номер записи которую требуется удалить. Для удаления всех записей введите 381", false)
                            setInput("")
                            delay(18000)
                            val userInput1 = getInput()?.toLongOrNull()
                            if (userInput1 != null && userInput1 == 381L) {
                                deleteRecordedFile(null) // Удаляем все записи
                                setInput("")
                            } else if (userInput1 != null && userInput1 in 1..noteCount) {
                                deleteRecordedFile(userInput1.toInt() - 1) // Удаляем конкретную запись
                                setInput("")
                            } else {
                                speakText("Удаление отменено", false)
                                setInput("")
                            }
                        }
                    }
                }

                // Воспроизведение голосовой заметки
                else if (input == "9" && getCall() == null) {
                    playSoundJob.launch {
                        val noteCount = recordedFiles.size
                        if (noteCount == 0) {
                            speakText("Голосовые записи отсутствуют", false)
                            setInput("")
                        } else if (noteCount == 1) {
                            setInput("")
                            delay(1500)
                            playRecordedFile(0, 1000) // Обрезаем последнюю секунду
                        } else {
                            val noteCountText = if (noteCount == 2) "две" else noteCount.toString()
                            speakText("Какую запись требуется воспроизвести? Всего их $noteCountText", false)
                            setInput("")
                            delay(10000)
                            val userInput = getInput()?.toLongOrNull()
                            if (userInput != null && userInput in 1..noteCount) {
                                playRecordedFile(userInput.toInt() - 1, 1000)
                            } else {
                                if (userInput == null) {
                                    playRecordedFile(noteCount - 1, 1000)
                                } else {
                                    speakText("Неверный номер записи", false)
                                }
                            }
                        }
                        setInput("")
                    }
                }

                // Настройка VOX Контрольное предложение не менять под него нарисована Диаграмма настройки
                else if (input == "11") {
                    if (getCall() == null && block) {
                        textToSpeech.setOnUtteranceProgressListener(null)
                        setInput("")
                        speakText(
                            "Один. Два. Три. Четыре. Пять. Поверка работоспособности вокс системы. Шесть. Семь. Восемь. Девять. Десять",
                            false
                        )
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Настройка VOX Нижний порог
                else if (input == "22") {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            speakText(
                                "Установите время удержания в мили секундах на которое требуется настроить вокс",
                                false
                            )
                            setInput("")
                            delay(15000)
                            periodVox = getInput()?.toLongOrNull() ?: 2500
                            if (periodVox < 4000) {
                                speakText(
                                    "Период следования настроен на $periodVox длительность на $durationVox",
                                    false
                                )
                            } else speakText("Ожидается значение от 0 до 4000 милисекунд", false)

                            delay(10000)
                            playDtmfTones(periodVox - 100, durationVox)
                            setInput("")
                        }
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Настройка VOX Верхний порог
                else if (input == "33") {
                    if (getCall() == null && block) {
                        playDtmfTones(periodVox + 100, durationVox)
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Настройка VOX измерение времени срабатывания и времени до момента открытия шумоподавителя
                else if (input == "44" || flagVox) {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            if (!flagVox) {
                                speakText("Введите длительность тона в милисекундах", false)
                            }
                            setInput("")
                            flagVox = true
                            delay(13000)
                            durationVox = getInput()?.toLongOrNull() ?: 50
                            if (durationVox > 1000) {
                                speakText("Ожидается значение от 0 до 1000 милисекунд", false)
                                setInput("")
                                flagVox = false
                            } else {
                                delay(5000)
                                playDtmfTone(0, 1000, durationVox)
                                delay(4000)
                                setInput("")
                                flagVox = false
                                speakText(
                                    "Был проигран тон с длительностью $durationVox милисекунд",
                                    false
                                )
                            }
                        }
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Прямой ввод частоты субтонов
                else if (input == "58") {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            speakText("Внимание! Прямой ввод частоты субтона репитера. Введите частоту", false)
                            setInput("")
                            delay(18000)
                            val userInput = getInput()
                            if (userInput != null && userInput.length > 4) {
                                speakText("Введите не более 4 цифр", false)
                                setInput("")
                            } else {
                                // Преобразуем ввод в число
                                val inputValue = userInput?.toLongOrNull() ?: 1230
                                val formattedValue = String.format("%.1f", inputValue / 10.0)
                                val finalValue = formattedValue.replace(',', '.')
                                frequencyCtcss = finalValue.toDouble()
                                speakText("Частота субтона репитера установлена на ${formatVolumeLevel(frequencyCtcss)} герца. Громкость ${formatVolumeLevel(volumeLevelCtcss * 1000)} процентов", false)
                                setInput("")
                            }
                        }
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Проигрывание DTMF тонов от 0 до 9 для проверки гальванической развязки
                else if (input == "55") {
                    if (getCall() == null && block) {
                        voxActivation(1500) { playDtmfTones(300, 300) }
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Установка времени тона активации для устранения проглатывания начальных слов всех речевых сообщений
                else if (input == "66") {
                    if (getCall() == null && block) {
                        setInput("")
                        playSoundJob.launch {
                            speakText("Введите громкость субтона", false)
                            delay(14000)
                            val volumeCtcsInput = getInput()
                            val volumeInputValue = volumeCtcsInput?.toIntOrNull()

                            if (volumeInputValue != null && volumeInputValue in 0..1000) {
                                volumeLevelCtcss = (volumeInputValue / 1000.0) // Преобразуем значение от 0-1000 в диапазон от 0.01 до 1
                                speakText(
                                    "Громкость субтона установлена на ${volumeInputValue} процентов. его частота ${formatVolumeLevel(frequencyCtcss)} герц",
                                    false
                                )
                            } else {
                                speakText("Ожидается значение от нуля до тысячи", false)
                            }
                            setInput("")
                        }
                    } else {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Команда на увеличение громкости речевых сообщений
                else if (input == "77") {
                    if (getCall() == null && block) {
                        if (volumeLevelTts < 100) {
                            val step = if (volumeLevelTts < 30) 1 else 10
                            volumeLevelTts += step
                            setVolumeTts(volumeLevelTts)
                            if (volumeLevelTts > 100) {
                                volumeLevelTts = 100
                            }
                            speakText(
                                "Громкость речевых сообщений увеличена и теперь составляет $volumeLevelTts процентов",
                                false
                            )
                        } else {
                            speakText("Достигнут максимальный уровень громкости", false)
                        }
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Команда на увеличение громкости звонка
                else if (input == "88") {
                    if (getCall() == null && block) {
                        if (volumeLevelCall < 100) {
                            val step = if (volumeLevelCall < 30) 1 else 10
                            volumeLevelCall += step
                            setVolumeCall(volumeLevelCall)
                            if (volumeLevelCall > 100) {
                                volumeLevelCall = 100
                            }
                            speakText(
                                "Громкость вызова увеличена и теперь составляет $volumeLevelCall процентов",
                                false
                            )
                        } else {
                            speakText("Достигнут максимальный уровень громкости", false)
                        }
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Установка времени тона активации для устранения проглатывания начальных слов всех речевых сообщений
                else if (input == "99") {
                    if (getCall() == null && block) {
                        setInput("")
                        playSoundJob.launch {
                        speakText("Введите длительность тона активации", false)
                        delay(14000)
                        val activationTimeInput = getInput()
                        val activationTime = activationTimeInput?.toLongOrNull()
                        if (activationTime != null && activationTime in 0..2000) {
                            voxActivation = activationTime
                            speakText(
                                "Длительность тона установлена на $activationTimeInput миллисекунд",
                                false
                            )
                        } else {
                            speakText("Ожидается значение от нуля до двух тысяч", false)
                        }
                        setInput("")
                        }
                    }  else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Разблокировка служебных команд
                else if (input == "1379" && getCall() == null) {
                    speakText("Служебные команды разблокированы", false)
                    block = true
                    setInput("")
                }

                // Блок выполнения исходящего вызова по нажатию звездочки
                else if (getCall() == null && input.length in 3..11) {
                    setSim(5)
                    DtmfService.callStart(context)
                    setInput1(getInput().toString())
                }
            }

            // Блок выполнения действий над набранным номером
            else if (getInput1()?.length in 3..11) {

                // Очистка поля набора если после набора номера и нажатия * передумали звонить
                if (key == '#') {
                    setInput("")
                    setInput1("")
                    if (!isSpeaking) {
                        speakText("Номеронабиратель, очищен", false)
                    }
                }

                // Звонок с первой сим карты
                if (key == '1' && getCall() == null) {
                        setSim(0)
                        setInput(getInput1().toString())
                        DtmfService.callStart(context)
                        setInput1("")

                }

                // Звонок со второй сим карты
                else if (key == '2' && getCall() == null) {
                        setSim(1)
                        setInput(getInput1().toString())
                        DtmfService.callStart(context)
                        setInput1("")
                }

                // Отправка надиктованного сообщения на набранный номер
                else if (key == '4' && getCall() == null) {
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Произнесите сообщение, которое вы хотите отправить на этот номер", false)
                            setInput("")
                            delay(10000)
                            withContext(Dispatchers.Main) {
                                stopDtmf()
                                utils.startSpeechRecognition(context) { result ->
                                    startDtmf()
                                    if (result != null) {
                                        if (result.length > 70) {
                                            speakText("Сообщение слишком длинное", false)
                                            setInput1("")
                                        } else {
                                            val phoneNumber = getInput1()
                                            setInput1("")
                                            if (phoneNumber?.length == 11) {
                                                val isSent = utils.sendSms(context, phoneNumber, result)
                                                if (isSent) {
                                                    val number = utils.numberToText(phoneNumber)
                                                    speakText("Сообщение \"$result\" успешно отправлено на номер $number ", false)
                                                    setInput1("")
                                                } else {
                                                    speakText("Не удалось отправить сообщение", false)
                                                    setInput1("")
                                                }
                                            } else {
                                                speakText("Номер телефона должен содержать 11 цифр", false)
                                                setInput1("")
                                            }
                                        }
                                    } else {
                                        speakText("Не удалось распознать сказанное. Попробуйте еще раз", false)
                                        setInput1("")
                                    }
                                }
                            }
                        } else {
                            speakText("Отсутствует интернет. Отправка сообщения не возможна", false)
                            setInput("")
                        }
                    }
                    return
                }

                // Добавление контакта в телефонную книгу
                else if (key == '5' && getCall() == null) {
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Произнесите фамилию и имя абонента, которого Вы хотите добавить в телефонную книгу", false)
                            setInput("")
                            delay(10000)
                            withContext(Dispatchers.Main) {
                                    stopDtmf()
                                utils.startSpeechRecognition(context) { result ->
                                    startDtmf()
                                    if (result != null) {
                                        if (result.length > 25) {
                                            speakText("Слишком длинное имя контакта", false)
                                            setInput1("")
                                        } else {
                                            val phoneNumber = getInput1()
                                            setInput1("")
                                            if (phoneNumber?.length == 11) {
                                                utils.saveContact(phoneNumber, result, context)
                                                val number = utils.numberToText(phoneNumber)
                                                speakText("Контакт $result с номером $number успешно добавлен в телефонную книгу", false)
                                                setInput1("")
                                            }
                                        }
                                    } else {
                                        speakText("Не удалось распознать сказанное. Попробуйте еще раз", false)
                                        setInput1("")
                                    }
                                }
                            }
                        } else {
                            speakText("Отсутствует интернет. Не получится сохранить контакт в телефонной книге", false)
                            setInput("")
                        }
                    }
                    return
                } else setInput1("")
            }

            // Остановка вызова если он есть а если нету то очистка поля ввода
            else if (key == '#') {

                textToSpeech.stop()
                isSpeaking = false
                if (getCall() == null) {
                    setInput("")

                    // Голосовой поиск абонента в телефонной книге по команде 5# с последующим удалением
                    if (input == "5" && getCall() == null) {
                        playSoundJob.launch {
                            if (utils.isOnline(context)) {
                                speakText("Назовите фамилию и имя абонента которого вы хотите удалить с телефонной книги", false)
                                setInput("")
                                delay(10000)
                                withContext(Dispatchers.Main) {
                                    stopDtmf()
                                    utils.startSpeechRecognition(context) { result ->
                                        startDtmf()
                                        if (result != null) {
                                            val isDeleted = utils.deleteContactByName(result, context)
                                            if (isDeleted) {
                                                speakText("Контакт $result успешно удален из телефонной книги", false)
                                                setInput1("")
                                            } else {
                                                speakText("Контакта $result нет в вашей телефонной книге. Проверьте правильность имени.", false)
                                                setInput1("")
                                            }
                                        } else {
                                            speakText("Не удалось распознать сказанное. Попробуйте еще раз", false)
                                            setInput1("")
                                        }
                                    }
                                }
                            } else {
                                speakText("Отсутствует интернет. Голосовой поиск не доступен",false)
                                setInput1("")
                                setInput("")
                            }
                        }
                    }

                    // Команда отключения генерации тонов CTCSS
                    else if (input == "58") {
                        if (getCall() == null && block) {
                            frequencyCtcss = 0.0
                            speakText("Внимание! Открытый канал, Все субтона репитера отключены", false)
                            stopPlayback()
                            setInput("")
                        } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                        }
                    }

                    // Команда на уменьшение громкости речевых сообщений
                    else if (input == "77") {
                        if (getCall() == null && block) {
                            if (volumeLevelTts > 0) {
                                val step = if (volumeLevelTts <= 30) 1 else 10
                                volumeLevelTts -= step
                                setVolumeTts(volumeLevelTts)
                                if (volumeLevelTts < 0) {
                                    volumeLevelTts = 0
                                }
                                speakText(
                                    "Громкость речевых сообщений уменьшена и теперь составляет $volumeLevelTts процентов",
                                    false
                                )
                            } else {
                                speakText("Достигнут минимальный уровень громкости", false)
                            }
                            setInput("")
                        } else  {
                            speakText("Команда заблокирована", false)
                            setInput("")
                        }
                    }

                    // Команда на уменьшение громкости звонка
                    else if (input == "88") {
                        if (getCall() == null && block) {
                            if (volumeLevelCall > 0) {
                                val step = if (volumeLevelCall <= 30) 1 else 10
                                volumeLevelCall -= step
                                setVolumeCall(volumeLevelCall)
                                if (volumeLevelCall < 0) {
                                    volumeLevelCall = 0
                                }
                                speakText(
                                    "Громкость вызова уменьшена и теперь составляет $volumeLevelCall процентов",
                                    false
                                )
                            } else {
                                speakText("Достигнут минимальный уровень громкости", false)
                            }
                            setInput("")
                        } else  {
                            speakText("Команда заблокирована", false)
                            setInput("")
                        }
                    }

                    // Блокировка служебных команд
                    else if (input == "1379" && getCall() == null) {
                        speakText("Установлена блокировка на служебные команды", false)
                        block = false
                        setInput("")
                    }

                    else if (!isSpeaking) {
                        if (isRecording) {
                            stopRecording()
                        } else speakText("Номеронабиратель, очищен", false)
                    }
                } else {
                    DtmfService.callEnd(context)
                }
            } else {
                if (key != 'R' && key != 'S' && key != 'T' && key != 'V') {
                    setInput(input + key)
                } else {
                    val cameraId = cameraManager.cameraIdList[0]
                    if (!isTorchOn) {
                        cameraManager.setTorchMode(cameraId, true)
                        isTorchOn = true

                        // Проверка на селективный вызов
                        if (getInput() == "000" || getInput() == "111" || getInput() == "555") {
                            getInput()?.toIntOrNull()?.let {
                                isTorchOnIs = it
                            }
                        }

                        if (isTorchOnIs == 111) {
                            when (key) {
                                'R' -> {
                                    frequencyCtcss = 203.5 // Субтон для первой радиостанции
                                    speakText("Первая радиостанция вы зарегистрированы. Ваш рабочий субтон ${formatVolumeLevel(frequencyCtcss)} герца", false)
                                }
                                'S' -> {
                                    frequencyCtcss = 218.1 // Субтон для второй радиостанции
                                    speakText("Вторая радиостанция вы зарегистрированы. Ваш рабочий субтон ${formatVolumeLevel(frequencyCtcss)} герца", false)
                                }
                                'T' -> {
                                    frequencyCtcss = 233.6 // Субтон для третьей радиостанции
                                    speakText("Третья радиостанция вы зарегистрированы. Ваш рабочий субтон ${formatVolumeLevel(frequencyCtcss)} герца", false)
                                }
                                'V' -> {
                                    frequencyCtcss = 250.3 // Субтон для четвертой радиостанции
                                    speakText("Четвертая радиостанция вы зарегистрированы. Ваш рабочий субтон ${formatVolumeLevel(frequencyCtcss)} герца", false)
                                }
                            }
                        }

                        if (isTorchOnIs == 0) {
                           frequencyCtcss = 0.0
                           speakText("Селективный вызов и звуковой отклик отключены", false)
                        }
                    }
                }
            }
        }
    }

    // Запуск дтмф анализа
    override fun startDtmf() {
        DtmfService.start(context)
        initJob()
        setInput("")
        recorderJob.launch { record() }
        recognizerJob.launch { recognize() }
    }

    private fun initJob() {
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + job)
        recorderJob = scope
        recognizerJob = scope
        playSoundJob = scope
    }

    // Остановка дтмф анализа
    override fun stopDtmf() {
        setInput("")
        isSpeaking = false
        job.cancel()
        audioRecord?.stop()
        audioRecord = null
        blockingQueue.clear()
        recognizer.clear()
        DtmfService.stop(context)
    }

    // Функция для преобразования значения в текстовый формат
    private fun formatVolumeLevel(volume: Double): String {
        val formattedVolume = String.format("%.2f", volume)
        return formattedVolume.replace(".", " ")
    }

    // Основная функция озвучивания входящих вызовов
    override suspend fun speakSuperTelephone() {
        isSpeaking = false
        delay(500) // без этой задержки не получалось корректное значение callerNumber
        val callerNumber = (_input.value).toString()
        if (callerNumber.isEmpty()) {
            speakText("Ошибка извлечения имени из телефонной книги", false)
        } else {
            val callerName = utils.getContactNameByNumber(callerNumber, context)
            while (getCallState() == 2) {
                val message = if (callerName.isNullOrEmpty()) {
                    "Внимание! Вам звонит абонент, имени которого нет в телефонной книге. Примите или отклоните вызов"
                } else {
                    "Внимание! Вам звонит абонент $callerName. Примите или отклоните вызов"
                }
                speakText(message, false)
                delay(17000)

            }
        }
    }

    override fun speakText(text: String, flagVoise: Boolean) {

        Log.d("Контрольный лог", "ЗНАЧЕНИЕ frequencyCtcss: $frequencyCtcss ЗНАЧЕНИЕ volumeLevelCtcss: $volumeLevelCtcss")

        if (frequencyCtcss != 0.0) {
            playCTCSS(frequencyCtcss)
            Log.d("Контрольный лог", "УСЛОВИЕ  ВНУТРИ ТТС СРАБОТАЛО")
        } // Для речевых сообщений } // Воспроизводим соответствующий CTCSS (если значение = 0.0 то тон не воспроизводится)

        if (isSpeaking) { return }

        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.getDefault()
            }

            isSpeaking = true

            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    textToSpeech.setOnUtteranceProgressListener(null)
                    textToSpeech.stop()
                    textToSpeech.shutdown()
                    if (frequencyCtcss != 0.0) { stopPlayback() }
                }

                override fun onError(utteranceId: String?) {}
            })

            val utteranceId = System.currentTimeMillis().toString()

            CoroutineScope(Dispatchers.Main).launch {
                if (text != "Один. Два. Три. Четыре. Пять. Поверка работоспособности вокс системы. Шесть. Семь. Восемь. Девять. Десять") {
                    voxActivation(1500) { textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
                } else {
                    delay(1500)
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
        }
    }
}

//  Log.d("Контрольный лог", "ЗНАЧЕНИЕ: $conType")



