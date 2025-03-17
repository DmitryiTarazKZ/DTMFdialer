package com.mcal.dtmf.data.repositories.main

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Call
import android.util.Log
import com.mcal.dtmf.R
import com.mcal.dtmf.recognizer.DataBlock
import com.mcal.dtmf.recognizer.Recognizer
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.recognizer.StatelessRecognizer
import com.mcal.dtmf.service.DtmfService
import com.mcal.dtmf.utils.Utils
import com.mcal.dtmf.utils.Utils.Companion.batteryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sin
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

    private val blockingQueue = LinkedBlockingQueue<DataBlock>()
    private val recognizer = Recognizer()
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val blockSize = 1024
    private var audioRecord: AudioRecord? = null
    private var volumeLevelTts = 90
    private var volumeLevelCall = 90
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
    private var periodVox = 3000L

    // УПРАВЛЕНИЕ СУБТОНАМИ ДЛЯ СЕЛЕКТИВНОГО ВЫЗОВА
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private val SAMPLE_RATE = 44100
    private val AMPLITUDE = 0.1
    private var playSineWaveJob: Job? = null

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

    private fun playDtmfTones1(duration: Long, tone: Int) {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100) // 100 - громкость
        playSoundJob.launch {
            toneGenerator.startTone(tone, duration.toInt()) // Длительность тона
            delay(duration) // период проигрывания
            toneGenerator.release()
        }
    }

    private fun delayTon1000hz(delayMillis: Long = 2000,onComplete: suspend () -> Unit) {
        val mediaPlayer = MediaPlayer.create(context, R.raw.beep1000hz)
        playSoundJob.launch {
            try {
                delay(delayMillis)
                if (isActive) {
                    mediaPlayer.start()
                    delay(voxActivation)
                    mediaPlayer.stop()
                    mediaPlayer.release()
                }
                onComplete()
            } catch (e: CancellationException) {
                mediaPlayer.release()
            }
        }
    }

    // УПРАВЛЕНИЕ СУБТОНАМИ ДЛЯ СЕЛЕКТИВНОГО ВЫЗОВА
    private fun playSineWave(frequency: Double) {
        Log.d("Контрольный лог", "ЗАДАН СУБТОН ЧАСТОТОЙ: $frequency")
        if (audioTrack == null) {
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT),
                AudioTrack.MODE_STREAM
            )
        }

        playSineWaveJob = CoroutineScope(Dispatchers.IO).launch {
            if (!isPlaying) {
                audioTrack?.play()
                isPlaying = true

                val bufferSize = SAMPLE_RATE / 10 // 100 мс
                val buffer = ByteArray(bufferSize * 2)
                val angleIncrement = 2.0 * Math.PI * frequency / SAMPLE_RATE
                var angle = 0.0

                while (isPlaying) {
                    for (i in 0 until bufferSize) {
                        val value = (AMPLITUDE * 32767 * sin(angle)).toInt()
                        buffer[2 * i] = (value and 0xFF).toByte()
                        buffer[2 * i + 1] = (value shr 8 and 0xFF).toByte()
                        angle += angleIncrement
                        if (angle >= 2 * Math.PI) angle -= 2 * Math.PI
                    }
                    audioTrack?.write(buffer, 0, buffer.size)
                }
            }
        }
    }

    private fun stopPlayback() {
        if (isPlaying) {
            isPlaying = false
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null // Освобождаем AudioTrack
            playSineWaveJob?.cancel()
        }
    }

    private fun playSineWave1(frequency: Double, duration: Int) {
        val sampleRate = 44100 // Частота дискретизации
        val numSamples = duration * sampleRate / 1000 // Количество выборок
        val generatedSnd = ShortArray(numSamples)

        // Генерация синусоидального сигнала
        for (i in 0 until numSamples) {
            generatedSnd[i] = (Short.MAX_VALUE * Math.sin(2 * Math.PI * i / (sampleRate / frequency))).toInt().toShort()
        }

        // Создание AudioTrack для воспроизведения звука
        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            generatedSnd.size * 2,
            AudioTrack.MODE_STATIC
        )

        // Заполнение AudioTrack сгенерированным звуком
        audioTrack.write(generatedSnd, 0, generatedSnd.size)
        audioTrack.play()

        // Запуск в корутине, чтобы избежать блокировки основного потока
        playSoundJob.launch {
            withContext(Dispatchers.IO) {
                // Длительность воспроизведения
                Thread.sleep(duration.toLong())
                audioTrack.stop()
                audioTrack.release()
            }
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

    // Значение подключено ли зарядное устройство
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

    // Функция для вычисления амплитуды (нужна для теста на блокировку микрофона)
    private fun calculateAmplitude(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += Math.abs(buffer[i].toDouble())
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
                    delayTon1000hz(1500) { playDtmfTones1(1000, 93) }
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
                }}

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

                // Удаленный контроль температуры и заряда аккумулятора по команде 2*
                else if (input == "2" && getCall() == null) {
                    val (temperatureText, levelText) = batteryStatus(context)
                    speakText("Температура батареи $temperatureText. Заряд батареи $levelText. Зарядное устройство: ${if (getPower()) "Подключено. " else "Не подключено. "} ",false )
                    setInput("")
                }

                // Удаленное сообщение о текущем времени по команде 1*
                else if (input == "1" && getCall() == null) {
                utils.speakCurrentTime()
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
                    setInput("")
                    speakText("Все номера быстрого набора, были очищены", false)


                }

                // Голосовой поиск абонента в телефонной книге по команде 5*
                else if (input == "5" && getCall() == null) {
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Назовите имя абонента которому вы хотите позвонить", false)
                            setInput("")
                            delay(7000)
                            // Переключаемся на основной поток так как нажатие кнопок с радиостанции обрабатывается в корутине рекогнайзер
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

                // Настройка VOX !!!КОНТРОЛЬНОЕ ПРЕДЛОЖЕНИЕ НЕ МЕНЯТЬ ТАК КАК ПОД НЕГО НАПИСАНА ДИАГРАММА НАСТРОЙКИ VOX!!!
                else if (input == "11" && getCall() == null) {
                    textToSpeech.setOnUtteranceProgressListener(null)
                    setInput("")
                    speakText("Один. Два. Три. Четыре. Пять. Поверка работоспособности вокс системы. Шесть. Семь. Восемь. Девять. Десять.", false)
                }

                // Настройка VOX
                else if (input == "22" && getCall() == null) {
                    playSoundJob.launch {
                        speakText("Установите время удержания в мили секундах на которое требуется настроить вокс", false)
                        setInput("")
                        delay(15000)
                        periodVox = getInput()?.toLongOrNull() ?: 2500
                        if (periodVox < 4000) {
                            speakText("Период следования настроен на $periodVox длительность на $durationVox", false)
                        } else speakText("Ожидается значение от 0 до 4000 милисекунд", false)

                        delay(10000)
                        playDtmfTones(periodVox - 100, durationVox)
                        setInput("")
                    }
                }

                // Настройка VOX
                else if (input == "33" && getCall() == null) {
                    playDtmfTones(periodVox + 100, durationVox)
                    setInput("")
                }

                else if (input == "54" && getCall() == null) {
                    playSineWave1(100.0, 10000)
                    setInput("")
                }

                else if (input == "55" && getCall() == null) {
                    playSineWave(150.0)
                    setInput("")
                }

                else if (input == "56" && getCall() == null) {
                    playSineWave(200.0)
                    setInput("")
                }

                else if (input == "57" && getCall() == null) {
                    playSoundJob.launch {
                        stopPlayback()
                        setInput("")
                    }
                }

                else if (input == "44" || flagVox) {
                    playSoundJob.launch {
                        if (!flagVox) { speakText("Введите длительность тона в милисекундах", false) }
                        setInput("")
                        flagVox = true
                        delay(10000)
                        durationVox = getInput()?.toLongOrNull() ?: 50
                        if (durationVox > 500)  {
                            speakText("Ожидается значение от 0 до 500 милисекунд", false)
                            setInput("")
                            flagVox = false
                        } else  {
                            delay(5000)
                            playDtmfTones1(durationVox, 0)
                            delay(5000)
                            setInput("")
                            flagVox = false
                            speakText("Был проигран тон с длительностью $durationVox милисекунд", false)
                        }
                    }
                }

                // Проигрывание DTMF тонов от 0 до 9
                else if (input == "66" && getCall() == null) {
                    delayTon1000hz(1500) { playDtmfTones(200, 200) }
                    setInput("")
                }

                // Удаленная проверка последнего СМС по команде 9*
                else if (input == "4" && getCall() == null) {
                    val smsText =  utils.getLastIncomingSms(context)
                    speakText("$smsText",false)
                    setInput("")
                }

                // Установка времени тона активации
                else if (input == "99" && getCall() == null) {
                    setInput("")
                    playSoundJob.launch {
                        speakText("Введите длительность тона активации", false)
                        delay(14000) // При значении 14 секунд времени хватает на то чтобы успеть ввести значение
                        val activationTimeInput = getInput()
                        val activationTime = activationTimeInput?.toLongOrNull()
                        if (activationTime != null && activationTime in 0..2000) {
                            voxActivation = activationTime
                            speakText("Длительность тона установлена на $activationTimeInput миллисекунд", false)
                        } else {
                            speakText("Ожидается значение от нуля до двух тысяч", false)
                        }
                        setInput("")
                    }
                }

                // Команда на увеличение громкости звонка
                else if (input == "88" && getCall() == null) {
                    if (volumeLevelCall < 100) {
                        val step = if (volumeLevelCall < 30) 1 else 10
                        volumeLevelCall += step
                        setVolumeCall(volumeLevelCall)
                        if (volumeLevelCall > 100) {
                            volumeLevelCall = 100
                        }
                        speakText("Громкость вызова увеличена и теперь составляет $volumeLevelCall процентов", false)
                    } else {
                        speakText("Достигнут максимальный уровень громкости", false)
                    }
                    setInput("")
                }

                // Команда на увеличение громкости
                else if (input == "77" && getCall() == null) {
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

                if (key == '#') {
                    setInput("")
                    setInput1("")
                    if (!isSpeaking) {
                        speakText("Номеронабиратель, очищен", false)
                    }
                }

                if (key == '1' && getCall() == null) {
                        setSim(0)
                        setInput(getInput1().toString())
                        DtmfService.callStart(context)
                        setInput1("")

                }

                else if (key == '2' && getCall() == null) {
                        setSim(1)
                        setInput(getInput1().toString())
                        DtmfService.callStart(context)
                        setInput1("")
                }

                else if (key == '4' && getCall() == null) {
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Произнесите сообщение, которое вы хотите отправить на этот номер", false)
                            setInput("")
                            delay(10000)
                            // Переключаемся на основной поток так как нажатие кнопок с радиостанции обрабатывается в корутине рекогнайзер
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
                                                // Отправляем SMS
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

                else if (key == '5' && getCall() == null) {
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Произнесите фамилию и имя абонента, которого Вы хотите добавить в телефонную книгу", false)
                            setInput("")
                            delay(10000)
                            // Переключаемся на основной поток так как нажатие кнопок с радиостанции обрабатывается в корутине рекогнайзер
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
                stopPlayback()

                textToSpeech.stop()
                isSpeaking = false
                when {
                    getCall() == null -> {
                        setInput("")

                        // Голосовой поиск абонента в телефонной книге по команде 5# с последующим удалением
                        if (input == "5" && getCall() == null) {
                            playSoundJob.launch {
                                if (utils.isOnline(context)) {
                                    speakText("Назовите фамилию и имя абонента которого вы хотите удалить с телефонной книги", false)
                                    setInput("")
                                    delay(10000)
                                    // Переключаемся на основной поток так как нажатие кнопок с радиостанции обрабатывается в корутине рекогнайзер
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

                        // Команда на уменьшение громкости звонка
                        if (input == "88" && getCall() == null) {
                            if (volumeLevelCall > 0) {
                                val step = if (volumeLevelCall <= 30) 1 else 10
                                volumeLevelCall -= step
                                setVolumeCall(volumeLevelCall)
                                if (volumeLevelCall < 0) {
                                    volumeLevelCall = 0
                                }
                                speakText("Громкость звонка уменьшена и теперь составляет $volumeLevelCall процентов", false)
                            } else {
                                speakText("Достигнут минимальный уровень громкости", false)
                            }
                            setInput("")
                        }

                        // Команда на уменьшение громкости ттс
                        else if (input == "77" && getCall() == null) {
                            if (volumeLevelTts > 0) {
                                val step = if (volumeLevelTts <= 30) 1 else 10
                                volumeLevelTts -= step
                                setVolumeTts(volumeLevelTts)
                                if (volumeLevelTts < 0) {
                                    volumeLevelTts = 0
                                }
                                speakText("Громкость речевых сообщений уменьшена и теперь составляет $volumeLevelTts процентов", false)
                            } else {
                                speakText("Достигнут минимальный уровень громкости", false)
                            }
                            setInput("")
                        } else {
                            if (!isSpeaking) {
                              //  speakText("Номеронабиратель, очищен", false)
                            }
                        }

                    }
                    else -> {
                        DtmfService.callEnd(context)
                    }
                }
            }
            else {
                if (key != 'E') {
                    setInput(input + key)
                } else {
                    val cameraId = cameraManager.cameraIdList[0]
                    if (!isTorchOn) {
                        cameraManager.setTorchMode(cameraId, true)
                        isTorchOn = true
                        if (getInput() == "000" || getInput() == "555") {
                            getInput()?.toIntOrNull()?.let {
                                isTorchOnIs = it
                            }
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
        if (isSpeaking) { return }

        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) { textToSpeech.language = Locale.getDefault() }

            isSpeaking = true

            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    textToSpeech.setOnUtteranceProgressListener(null)
                    textToSpeech.stop()
                    textToSpeech.shutdown()
                }

                override fun onError(utteranceId: String?) {}
            })

            val utteranceId = System.currentTimeMillis().toString()

            CoroutineScope(Dispatchers.Main).launch {
                if (text != "Один. Два. Три. Четыре. Пять. Поверка работоспособности вокс системы. Шесть. Семь. Восемь. Девять. Десять.") {
                    delayTon1000hz(1500) { textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
                }  else {
                    delay(1500)
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
        }
    }
}

//  Log.d("Контрольный лог", "setStartFlashlight ВЫЗВАНА: $conType")



