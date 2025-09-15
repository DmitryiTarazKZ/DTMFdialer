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
import com.mcal.dtmf.recognizer.DataBlock
import com.mcal.dtmf.recognizer.Recognizer
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.recognizer.StatelessRecognizer
import com.mcal.dtmf.scheduler.AlarmScheduler
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
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class MainRepositoryImpl(
    private val context: Application,
    private var textToSpeech: TextToSpeech,

    ) : MainRepository {
    private var utils = Utils(this, CoroutineScope(Dispatchers.IO), context)
    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)
    private var recorderJob = scope
    private var recognizerJob = scope
    private var playSoundJob = scope

    private val _spectrum: MutableStateFlow<Spectrum?> = MutableStateFlow(null)
    private val _key: MutableStateFlow<Char?> = MutableStateFlow(null)
    private val _input: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _input1: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _input2: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _call: MutableStateFlow<Call?> = MutableStateFlow(null)
    private val _callState: MutableStateFlow<Int> = MutableStateFlow(Call.STATE_DISCONNECTED)
    private val _powerState: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _isPlaying: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _isRecording: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _amplitudeCheck: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _flagFrequencyLowHigt: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _outputFrequency: MutableStateFlow<Float?> = MutableStateFlow(0f)
    private val _outputFrequencyLow: MutableStateFlow<Float?> = MutableStateFlow(0f)
    private val _outputFrequencyHigh: MutableStateFlow<Float?> = MutableStateFlow(0f)
    private val _outputAmplitude: MutableStateFlow<Float?> = MutableStateFlow(0f)
    private val _volumeLevelTts: MutableStateFlow<Float?> = MutableStateFlow(80f)
    private val _sim: MutableStateFlow<Int> = MutableStateFlow(0)
    private val _selectedSubscriberNumber: MutableStateFlow<Int> = MutableStateFlow(0)
    private val _frequencyCtcss: MutableStateFlow<Double> = MutableStateFlow(0.0)
    private val _volumeLevelCtcss: MutableStateFlow<Double> = MutableStateFlow(0.08)


    private val blockingQueue = LinkedBlockingQueue<DataBlock>()
    private val recognizer = Recognizer()
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val blockSize = 1024
    private var audioRecord: AudioRecord? = null
    private var volumeLevelCall = 80 // Громкость вызова по умолчанию в процентах
    private var amplitudePtt: Double = 150.000 // Установка начальной точки амплитуды входного сигнала (используется для автоматической остановки записи голосовых заметок
    private var amplitudeCtcssCorrect: Double = 0.02 // Корректирующее значения прибавляется к установленному уровню в момент поднятия трубки
    private var isTorchOnIs = 5 // Задано 5 чтобы исключить случайное срабатывание (Внимание открытый канал...)
    private var isSpeaking = false
    private var lastDialedNumber: String = ""
    private var voxActivation = 500L
    private var numberA = ""
    private var numberB = ""
    private var numberC = ""
    private var numberD = ""
    private var isTorchOn = false
    private var flagVox = false
    private var flagAmplitude = false
    private var flagSimMonitor = false
    private var flagFrequency = false
    private var flagDtmf = false
    private var flagSelective = false
    private var dtmfPlaying = false
    private var lastKeyPressTime: Long = 0
    private var flagDoobleClic = 0
    private var durationVox = 50L // Длительность активирующего тона для отладки платы VOX
    private var periodVox = 1600L // Длительность периода следования тонов для отладки платы VOX
    private var ton = 0
    private var pruning = 1000 // Значение обрезки зукового файла
    private var block = false
    private var subscribersNumber = 0
    private val subscribers = mutableSetOf<Char>() // Список количества абонентов для селективного вызова
    private val callStates = mutableListOf<Int>() // Список изменений вызова для мониторинга точки установки репитера
    private val durationSeconds = 30000 // Время в течении которого происходит попытка дозвона для мониторинга
    private var isStopRecordingTriggered = false


    private var callStartTime: Long = 0 // Время начала исходящего вызова
    private var frequencyCount: Int = 0 // Счетчик частот в диапазоне
    private var flagFrequencyCount = false
    private val alarmScheduler: AlarmScheduler = AlarmScheduler(context = context)

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

        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) { // Проверка на оптимизацию
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
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

    override fun getCallFlow(): Flow<Call?> = flow {
        emitAll(_call)
    }

    override fun getCall(): Call? {
        return _call.value
    }

    override fun setCall(call: Call?) {
        _call.update { call }
    }

    override fun getFrequencyCtcssFlow(): Flow<Double> = flow {
        emitAll(_frequencyCtcss)
    }

    override fun getFrequencyCtcss(): Double {
        return _frequencyCtcss.value
    }

    override fun setFrequencyCtcss(frequencyCtcss: Double) {
        _frequencyCtcss.update { frequencyCtcss }
    }

    override fun getVolumeLevelCtcssFlow(): Flow<Double> = flow {
        emitAll(_volumeLevelCtcss)
    }

    override fun getVolumeLevelCtcss(): Double {
        return _volumeLevelCtcss.value
    }

    override fun setVolumeLevelCtcss(volumeLevelCtcss: Double) {
        _volumeLevelCtcss.update { volumeLevelCtcss }
    }

    override fun getCallStateFlow(): Flow<Int> = flow {
        emitAll(_callState)
    }

    override fun getCallState(): Int {
        return _callState.value
    }

    override fun setCallState(callState: Int) {

        if (getFrequencyCtcss() != 0.0 && (callState == 1)) {
            // Генерируем субтон в поток вызова если исходящий
            utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss())
        }

        if (getFrequencyCtcss() != 0.0 && callState == 4) {
            // Поднимаем уровень субтона если абонент поднял трубку на корректирующее значение
            setVolumeLevelCtcss(getVolumeLevelCtcss() + amplitudeCtcssCorrect)
            utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss())
        }

        if (getFrequencyCtcss() != 0.0 && callState == 7) {
            Log.e("Контрольный лог", "СРАБОТАЛО УСЛОВИЕ")
            // Прекращаем генерацию субтона если вызов и возвращаем уровень на начальное значение
            setVolumeLevelCtcss(getVolumeLevelCtcss() - amplitudeCtcssCorrect / 2) // так как условие срабатывает 2 раза то делим на 2
            utils.stopPlayback()
        }
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
        if (flagDtmf) {
            _input.update { "${durationVox}ms ${getVolumeLevelTts().toInt()}%" }
        } else {
            _input.update { value }
        }
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

    override fun getInput2Flow(): Flow<String> = flow {
        if (_input1.value == null) {
            try {
                getInput2()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_input.filterNotNull())
    }

    override fun getInput2(): String? {
        return _input2.value
    }

    override fun setInput2(value: String) {
        _input2.update { value }
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
            speakText("Внимание! Произошло отключение питания. Смартфон работает от собственного аккумулятора")
        }
        if (previousValue == false && value) {
            speakText("Питание устройства возобновлено. Аккумулятор смартфона заряжается")
        }
        _powerState.update { value }
    }

    override fun getIsRecordingFlow(): Flow<Boolean> = flow {
        if (_isRecording.value == null) {
            try {
                getIsRecording()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_isRecording.filterNotNull())
    }

    override fun getIsRecording(): Boolean {
        return _isRecording.value ?: false
    }

    override fun setIsRecording(value: Boolean) {
        _isRecording.update { value }
    }

    override fun getIsPlayingFlow(): Flow<Boolean> = flow {
        if (_isPlaying.value == null) {
            try {
                getIsPlaying()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_isPlaying.filterNotNull())
    }

    override fun getIsPlaying(): Boolean {
        return _isPlaying.value ?: false
    }

    override fun setIsPlaying(value: Boolean) {
        _isPlaying.update { value }
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

    override fun getFlagFrequencyLowHigtFlow(): Flow<Boolean> = flow {
        if (_flagFrequencyLowHigt.value == null) {
            try {
                getPower()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_flagFrequencyLowHigt.filterNotNull())
    }

    override fun getFlagFrequencyLowHigt(): Boolean {
        return _flagFrequencyLowHigt.value ?: false
    }

    override fun setFlagFrequencyLowHigt(value: Boolean) {
        _flagFrequencyLowHigt.update { value }
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

    // получение переменной истинной частоты с блока распознавания
    override fun getOutputFrequencyFlow(): Flow<Float> = flow {
        if (_outputFrequency.value == null) {
            try {
                getOutputFrequency()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_outputFrequency.filterNotNull())
    }

    override fun getOutputFrequency(): Float {
        return _outputFrequency.value ?: 0f
    }


    override fun setOutputFrequency(outputFrequency: Float) {

        if (flagFrequency) {
            val formattedFrequency = String.format("%07.3f", outputFrequency).replace(',', '.') + "Hz"
            setInput(formattedFrequency)
        }

        // Обновляем выходную частоту
        _outputFrequency.update { outputFrequency }

        // Блок отвечает за речевое сообщение: Переместитесь ближе... если нет вызывных гудков (работает при адекватной гальванической связи проверка 55*)
        if (getCallState() == 1) {
            val currentTime = System.currentTimeMillis()

            if (callStartTime == 0L) {
                callStartTime = System.currentTimeMillis() // Запоминаем время начала вызова
            }

            // Если частота попала в диапазон звучания Контроля посылки вызова, увеличиваем счетчик (сработает если микрофон не блокируется)
            if (outputFrequency in 385f..455f) { // В диапазон попадают частоты вызывных гудков: Европы, США, Великобритании, Японии
                frequencyCount++
            }

            // Если прошло 20 секунд и счетчик < 10 (то есть не было вызывных гудков) произносим сообщение
            if (currentTime - callStartTime >= 20000) {
                if ((frequencyCount < 10 && !flagFrequencyCount) && getAmplitudeCheck()) {
                    frequencyCount = 0
                    flagFrequencyCount = true
                    speakText("Попытка соедениния выполняется дольше обычного. Переместитесь ближе к базовой станции")
                }
            }
        } else {
            callStartTime = 0 // Сбрасываем время начала вызова
            frequencyCount = 0 // Сбрасываем значение счетчика
            flagFrequencyCount = false // Сбрасываем флаг предотвращающий многократный вызов TTC
        }
    }

    // получение переменной НИЖНЕЙ частоты с блока распознавания
    override fun getOutputFrequencyLowFlow(): Flow<Float> = flow {
        if (_outputFrequencyLow.value == null) {
            try {
                getOutputFrequencyLow()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_outputFrequencyLow.filterNotNull())
    }

    override fun getOutputFrequencyLow(): Float {
        return _outputFrequencyLow.value ?: 0f
    }

    override fun setOutputFrequencyLow(outputFrequencyLow: Float) {
        // Используем функцию подмены
        val frequencyToUpdate = substituteFrequencyLow(outputFrequencyLow)
        // Обновляем значение
        _outputFrequencyLow.update { frequencyToUpdate }
    }

    // получение переменной ВЕРХНЕЙ частоты с блока распознавания
    override fun getOutputFrequencyHighFlow(): Flow<Float> = flow {
        if (_outputFrequencyHigh.value == null) {
            try {
                getOutputFrequencyHigh()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_outputFrequencyHigh.filterNotNull())
    }

    override fun getOutputFrequencyHigh(): Float {
        return _outputFrequencyHigh.value ?: 0f
    }

    override fun setOutputFrequencyHigh(outputFrequencyHigh: Float) {
        // Используем функцию подмены
        val frequencyToUpdate = substituteFrequencyHigh(outputFrequencyHigh)
        // Обновляем значение
        _outputFrequencyHigh.update { frequencyToUpdate }
    }

    // получение амплитуды с микрофона
    override fun getOutputAmplitudeFlow(): Flow<Float> = flow {
        if (_outputAmplitude.value == null) {
            try {
                getOutputAmplitude()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_outputAmplitude.filterNotNull())
    }

    override fun getOutputAmplitude(): Float {
        return _outputAmplitude.value ?: 0f
    }

    override fun setOutputAmplitude(outputAmplitude: Float) {
        if (flagAmplitude) {
            val formattedAmplitude = String.format("%07.3f", outputAmplitude).replace(',', '.')
            setInput(formattedAmplitude)
        }

        // Автоматическая остановка записи по отпусканию PTT абонентом или если он замолчит
        if (getIsRecording()) {
            playSoundJob.launch {
                var noExceedDuration = 0L
                while (getIsRecording()) {
                    if (getOutputAmplitude().toDouble() >= amplitudePtt) {
                        noExceedDuration = 0
                    } else {
                        noExceedDuration += 100
                    }
                    // если в течении этого времени (7 сек) амплитуда не превышала порог останавливаем запись
                    if (noExceedDuration >= 7000 && !isStopRecordingTriggered) {
                        isStopRecordingTriggered = true
                        delay(1000)
                        pruning = 7700 // Обрезаем 8 секунд для того чтобы не было слышно как абонент отпустил PTT
                        utils.stopRecording(isTorchOnIs, subscribers, pruning)
                        break
                    }
                    delay(100) // Проверяем каждые 100 мс
                }
            }
        }

        _outputAmplitude.update { outputAmplitude }
    }

    // Общий уровень громкости
    override fun getVolumeLevelTtsFlow(): Flow<Float> = flow {
        if (_volumeLevelTts.value == null) {
            try {
                getVolumeLevelTts()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_volumeLevelTts.filterNotNull())
    }

    override fun getVolumeLevelTts(): Float {
        return _volumeLevelTts.value ?: 0f
    }

    override fun setVolumeLevelTts(volumeLevelTts: Float) {
         setVolumeTts(volumeLevelTts.toInt())
        _volumeLevelTts.update { volumeLevelTts }
    }

    override fun getSelectedSubscriberNumberFlow(): Flow<Int> = flow {
        emitAll(_selectedSubscriberNumber)
    }

    override fun getSelectedSubscriberNumber(): Int {
        return _selectedSubscriberNumber.value
    }

    override fun setSelectedSubscriberNumber(value: Int) {
        _selectedSubscriberNumber.update { value }
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
                        setOutputAmplitude(amplitude.toFloat())

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

    // Функция для вычисления амплитуды
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
                        utils.voxActivation(1500) { utils.playDtmfTone(ton, 1000, 1000) }
                    } else speakText("Звуковой отклик. Установите значение от 17 до 97")
                }
                setInput("")
            }
        }

        val previousKey = _key.value

        if (input.length > 11 && getCallState() == 7) {
            speakText("Поле ввода переполнилось, и поэтому было очищенно")
            setInput("")
        }

        if (key != ' ' && key != previousKey) {

            if (flagDtmf && !dtmfPlaying) {
                val currentTime = System.currentTimeMillis()

                // Проверяем, прошло ли установленное врямя с момента последнего нажатия
                if (currentTime - lastKeyPressTime > durationVox + 400) {
                    val tone = when (key) {
                        '0' -> ToneGenerator.TONE_DTMF_0
                        '1' -> ToneGenerator.TONE_DTMF_1
                        '2' -> ToneGenerator.TONE_DTMF_2
                        '3' -> ToneGenerator.TONE_DTMF_3
                        '4' -> ToneGenerator.TONE_DTMF_4
                        '5' -> ToneGenerator.TONE_DTMF_5
                        '6' -> ToneGenerator.TONE_DTMF_6
                        '7' -> ToneGenerator.TONE_DTMF_7
                        '8' -> ToneGenerator.TONE_DTMF_8
                        '9' -> ToneGenerator.TONE_DTMF_9
                        'A' -> ToneGenerator.TONE_DTMF_A
                        'B' -> ToneGenerator.TONE_DTMF_B
                        'C' -> ToneGenerator.TONE_DTMF_C
                        'D' -> ToneGenerator.TONE_DTMF_D
                        else -> null // Если клавиша не соответствует DTMF
                    }

                    tone?.let {
                        playSoundJob.launch {
                            dtmfPlaying = true // Устанавливаем флаг, что DTMF тон проигрывается
                            lastKeyPressTime = currentTime // Обновляем время последнего нажатия
                            utils.playDtmfTone(it, durationVox + 100, durationVox)
                            delay(durationVox + 100)
                            dtmfPlaying = false // Сбрасываем флаг
                            setInput("")
                        }
                    }
                }
            }

            // Обработка нажатий клавиш быстрого набора
            if ((key in 'A'..'D' && getCall() == null) && (!getFlagFrequencyLowHigt() && !flagDtmf)) {
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
                                    speakText("Этот номер уже закреплен за клавишей А")
                                    setInput("")
                                }
                                numberB -> {
                                    speakText("Этот номер уже закреплен за клавишей Б")
                                    setInput("")
                                }
                                numberC -> {
                                    speakText("Этот номер уже закреплен за клавишей С")
                                    setInput("")
                                }
                                numberD -> {
                                    speakText("Этот номер уже закреплен за клавишей Д")
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
                                        })
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
                            speakText("Этот номер ранее был закреплен за этой клавишей")
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

                            speakText(message)

                            // Установка времени задержки в зависимости от сообщения
                            val delayTime = if (message == "Будет выполнен вызов абонента $num") {
                                12000
                            } else {
                                8000
                            }

                            // if (getSim() == 5) setSim(0) сразу звонок с той сим с которой звонили последний раз
                            setSim(5) // Заставляем выбрать с какой сим карты выполнить вызов
                            delay(delayTime.toDuration(DurationUnit.MILLISECONDS)) // Задержка перед началом вызова
                            DtmfService.callStart(context)
                            setInput1(getInput().toString())  // Закоментировать если надо сразу звонок с той сим с которой звонили последний раз

                        } else {
                            speakText("Клавиша свободна, вы можете закрепить за ней любой номер быстрого набора")
                        }
                    }
                }
            }

            else if (key == '*') {

                // Функция, где обрабатываются одиночные/двойные клики:
                if (flagDoobleClic in 0..1) {
                    playSoundJob.launch {
                        flagDoobleClic++
                        delay(1500) // время для определения одинарного или двойного клика
                        when {
                            // одинарный клик
                            input.isEmpty() && flagDoobleClic == 1 -> {
                                speakText("Наберите номер")
                            }
                            // двойной клик
                            input.isEmpty() && flagDoobleClic == 2 -> {
                                val secondInput = getInput2()
                                // проверяем не только на null, но и на пустую строку
                                if (!secondInput.isNullOrBlank()) {
                                    setInput(secondInput.toString())
                                    if (getSim() == 5) setSim(0) // звонок с последней использованной SIM
                                    DtmfService.callStart(context)
                                } else {
                                    speakText("Вначале выполните звонок")
                                }
                            }
                        }
                        flagDoobleClic = 0
                    }
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
                    utils.lastMissed(context, true)
                    setInput("")
                }

                // Удаленное сообщение о текущем времени по команде 1*
                else if (input == "1" && getCall() == null) {
                    utils.speakCurrentTime()
                    setInput("")
                }

                // Удаленный контроль температуры и заряда аккумулятора по команде 2*
                else if (input == "2" && getCall() == null) {
                    val (temperatureText, levelText) = batteryStatus(context)
                    speakText("Температура батареи смартфона $temperatureText. Заряд батареи $levelText. Батарея смартфона: ${if (getPower()) "Заряжается. " else "Разряжается. Расходуйте заряд экономно"} ")
                    setInput("")
                }

                // Уровень сигнала SIM-карт по команде 3*
                else if (input == "3" && getCall() == null) {
                    utils.speakSimSignalLevels(context)
                }

                // Удаленная проверка последнего СМС по команде 4*
                else if (input == "4" && getCall() == null) {
                    val smsText =  utils.getLastIncomingSms(context)
                    speakText(smsText)
                    setInput("")
                }

                // Голосовой поиск абонента в телефонной книге по команде 5*
                else if ((input == "5" && getCall() == null) && !getIsRecording()) { // Блокируем команду если идет запись голосовой заметки
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Назовите имя абонента которому вы хотите позвонить")
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
                                        speakText("Не удалось распознать сказанное. Попробуйте еще раз")
                                        setInput1("")
                                    }
                                }
                            }
                        } else {
                            speakText("Отсутствует интернет. Голосовой поиск не доступен")
                            setInput("")
                            setInput1("")
                        }
                    }
                }

                // свободная команда 6*
                else if (input == "6" && getCall() == null) {
                // СВОБОДНАЯ КОМАНДА!!!!!!!!!!!!
                }

                // Запись голосовой заметки
                else if (input == "7" && getCall() == null) {
                    isStopRecordingTriggered = false
                    utils.startRecording(isTorchOnIs, subscribers)
                    setInput("")
                }

                // свободная команда
                else if (input == "8" && getCall() == null) {
                    // СВОБОДНАЯ КОМАНДА!!!!!!!!!!!
                }

                // Воспроизведение голосовой заметки
                else if (input == "9" && getCall() == null) {
                    utils.playRecordedFile(isTorchOnIs, subscribers, 0)
                    setInput("")

                }

                // Настройка VOX Контрольное предложение не менять под него нарисована Диаграмма настройки
                else if (input == "11") {
                    if (getCall() == null && block) {
                        textToSpeech.setOnUtteranceProgressListener(null)
                        setInput("")
                        speakText(
                            "Один. Два. Три. Четыре. Пять. Поверка работоспособности вокс системы. Шесть. Семь. Восемь. Девять. Десять")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Настройка VOX Нижний порог
                else if (input == "22") {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            speakText(
                                "Установите нужный период в мили секундах, для настройки и измерения времени удержания вокс")
                            setInput("")
                            delay(16000)
                            periodVox = getInput()?.toLongOrNull() ?: 1600 // Оптимальное значение при увелечение до 2500 в режиме с одной рацией невозможно принять или прервать вызов
                            if (periodVox < 4001) {
                                if (periodVox == 1600L) {
                                    speakText("Установлен рекомендуемый период $periodVox милисекунд с длительностью тона $durationVox")
                                } else {
                                    speakText("Период следования настроен на $periodVox милисекунд длительность на $durationVox")
                                }
                            } else speakText("Ожидается значение от 0 до 4000 милисекунд")

                            delay(12000)
                            utils.playDtmfTones(periodVox, durationVox)
                            setInput("")
                        }
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Настройка VOX Верхний порог
                else if (input == "33") {
                    if (getCall() == null && block) {
                        utils.playDtmfTones(periodVox + 200, durationVox)
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Настройка VOX измерение времени срабатывания и времени до момента открытия шумоподавителя
                else if (input == "44" || flagVox) {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            if (!flagVox) {
                                speakText("Введите длительность тона в милисекундах")
                            }
                            setInput("")
                            flagVox = true
                            delay(10000)
                            durationVox = getInput()?.toLongOrNull() ?: 50
                            if (durationVox > 999) {
                                speakText("Ожидается значение от 0 до 999 милисекунд")
                                setInput("")
                                flagVox = false
                            } else {
                                delay(5000)
                                utils.playDtmfTone(0, 1000, durationVox)
                                delay(2000)
                                setInput("")
                                flagVox = false
                                speakText(
                                    "Был проигран тон с длительностью $durationVox милисекунд")
                            }
                        }
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                else if (input == "45") {
                    if (block) {
                        speakText("Измерение амплитуды входного сигнала включено")
                        flagAmplitude = true
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Установка точки амплитуды
                else if (input == "46") {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            speakText("Установите порог для определения перехода на прием, последние 3 цифры установятся после запятой")
                            setInput("")
                            delay(19000)
                            val userInput = getInput()
                            if (userInput != null && userInput.length < 4) {
                                speakText("Введите не менее 4 цифр")
                                setInput("")
                            } else {
                                // Преобразуем ввод в Double, используя toDoubleOrNull
                                val inputValue = userInput?.toLongOrNull() ?: 0
                                // Форматируем значение, отделяя последние 3 цифры
                                val integerPart = inputValue / 1000 // Целая часть
                                val decimalPart = inputValue % 1000 // Дробная часть
                                amplitudePtt = integerPart + decimalPart / 1000.0 // Преобразуем в формат 0.XXX
                                speakText("Порог установлен на ${String.format("%07.3f", amplitudePtt)}")
                                setInput("")
                            }
                        }
                    } else {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                else if (input == "47") {
                    if (block) {
                        speakText("Измерение частоты входного сигнала включено")
                        flagFrequency = true
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                else if (input == "48") {
                    if (block) {
                        speakText("Измерение нижней и верхней частоты двухтонального набора включено")
                        setFlagFrequencyLowHigt(true)
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                else if (input == "49") {
                    if (block) {
                        speakText("Генерация двухтональных команд включена. Длительность настроена на $durationVox милисекунд. Громкость на ${getVolumeLevelTts().toInt()} процентов")
                        flagDtmf = true
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                else if (input == "82913746") {
                    if (block) {
                        speakText("Внимание! Разблокирована команда для включения селективного вызова")
                        flagSelective = true
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Прямой ввод частоты субтонов
                else if (input == "58") {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            speakText("Внимание! Прямой ввод частоты субтона репитера. Введите частоту")
                            setInput("")
                            delay(20000)
                            val userInput = getInput()
                            if (userInput != null && userInput.length > 4) {
                                speakText("Введите не более 4 цифр")
                                setInput("")
                            } else {
                                // Преобразуем ввод в число
                                val inputValue = userInput?.toLongOrNull() ?: 1230
                                val formattedValue = String.format("%.1f", inputValue / 10.0)
                                val finalValue = formattedValue.replace(',', '.')
                                setFrequencyCtcss(finalValue.toDouble())
                                speakText("Частота субтона репитера установлена на ${formatVolumeLevel(getFrequencyCtcss())} герца. Громкость ${formatVolumeLevel(getVolumeLevelCtcss() * 1000)} процентов")
                                setInput("")
                            }
                        }
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Проигрывание DTMF тонов от 0 до 9 для проверки гальванической развязки
                else if (input == "55") {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            utils.voxActivation(1500, voxActivation) { utils.playDtmfTones(600, 300) }
                            setInput("")
                            delay(12000)
                            if (getInput() == "") speakText("Гальваническая связь полностью отсутствует. Отличный показатель для неселективного вызова")
                            if (getInput() == "0123456789") speakText("Гальваническая связь есть, все тона восприняты без ошибок")
                            if (getInput() != "" && getInput() != "0123456789") { speakText("Гальваническая связь есть, тона прошли с ошибками") }
                            setInput("")
                        }
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Установка громкости субтона
                else if (input == "66") {
                    if (getCall() == null && block) {
                        setInput("")
                        playSoundJob.launch {
                            speakText("Введите громкость субтона")
                            delay(14000)
                            val volumeCtcsInput = getInput()
                            val volumeInputValue = volumeCtcsInput?.toIntOrNull()

                            if (volumeInputValue != null && volumeInputValue in 0..1000) {
                                setVolumeLevelCtcss(volumeInputValue / 1000.0) // Преобразуем значение от 0-1000 в диапазон от 0.01 до 1
                                speakText(
                                    "Громкость субтона установлена на $volumeInputValue процентов. его частота ${formatVolumeLevel(getFrequencyCtcss())} герц")
                            } else {
                                speakText("Ожидается значение от нуля до тысячи")
                            }
                            setInput("")
                        }
                    } else {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Команда на увеличение громкости речевых сообщений
                else if (input == "77") {
                    if (getCall() == null && block) {
                        val currentVolume = getVolumeLevelTts()
                        if (currentVolume < 100) {
                            val step = if (currentVolume < 30) 1f else 10f
                            val newVolume = (currentVolume + step).coerceAtMost(100f)
                            setVolumeLevelTts(newVolume)
                            speakText("Громкость речевых сообщений увеличена и теперь составляет ${newVolume.toInt()} процентов")
                        } else {
                            speakText("Достигнут максимальный уровень громкости")
                        }
                        setInput("")
                    } else {
                        speakText("Команда заблокирована")
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
                                "Громкость вызова увеличена и теперь составляет $volumeLevelCall процентов")
                        } else {
                            speakText("Достигнут максимальный уровень громкости")
                        }
                        setInput("")
                    } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Установка времени тона активации для устранения проглатывания начальных слов всех речевых сообщений
                else if (input == "99") {
                    if (getCall() == null && block) {
                        setInput("")
                        playSoundJob.launch {
                        speakText("Введите длительность тона активации")
                        delay(14000)
                        val activationTimeInput = getInput()
                        val activationTime = activationTimeInput?.toLongOrNull()
                        if (activationTime != null && activationTime in 0..2000) {
                            voxActivation = activationTime
                            speakText(
                                "Длительность тона установлена на $activationTimeInput миллисекунд")
                        } else {
                            speakText("Ожидается значение от нуля до двух тысяч")
                        }
                        setInput("")
                        }
                    }  else  {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Переключение в двухканальный режим
                else if (input == "123") {
                    if (getCall() == null && block) {
                        voxActivation = 0
                        setFrequencyCtcss(60.0) // Устанавливаем частоту субтона в 60 герц
                        setVolumeLevelCtcss(0.08) // Устанавливаем амплитуду субтона в 80%
                        speakText("Произведено переключение в двухканальный режим, требуется подключение двух радиостанций")
                        setInput("")
                    } else {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Установка корректирующего значения амплитуды субтона
                else if (input == "124") {
                    if (getCall() == null && block) {
                        playSoundJob.launch {
                            speakText("Установите корректирующее значение амплитуды субтона в процентах")
                            setInput("")
                            delay(19000)
                            val userInput = getInput()
                            if (userInput.isNullOrEmpty()) {
                                amplitudeCtcssCorrect = 0.02
                                speakText("Установлено значение по умолчанию 20%")
                                setInput("")
                            } else {
                                val inputValue = userInput.toIntOrNull() ?: 0
                                if (inputValue > 1000) {
                                    speakText("Ожидается значение от 1 до 1000 процентов")
                                    setInput("")
                                } else {
                                    amplitudeCtcssCorrect = inputValue / 1000.0
                                    speakText("Корректирующее значение установлено на $inputValue%")
                                setInput("")
                                }
                            }
                        }
                    } else {
                        speakText("Команда заблокирована")
                        setInput("")
                    }
                }

                // Разблокировка служебных команд
                else if (input == "1379" && getCall() == null) {
                    speakText("Служебные команды разблокированы")
                    block = true
                    setInput("")
                }

                // Проверка свободной памяти 9999*
                else if (input == "9999" && getCall() == null) {
                    val mb = utils.getAvailableMemoryInMB()
                    val androidVersion = Build.VERSION.RELEASE
                    val sdkVersion = Build.VERSION.SDK_INT

                    val memoryMessage = if (mb > 1000) {
                        "Объем свободной памяти составляет ${mb / 1024} гигабайта. Версия андроид $androidVersion. Версия сдк $sdkVersion"
                    } else {
                        "Объем свободной памяти составляет $mb мегабайт. Версия андроид $androidVersion. Версия сдк $sdkVersion"
                    }
                    speakText(memoryMessage)
                    setInput("")
                }

                // Блок выполнения исходящего вызова по нажатию звездочки
                else if ((getCall() == null && input.length in 3..11) && (!getFlagFrequencyLowHigt() && !flagDtmf)) {
                    setSim(5)
                    DtmfService.callStart(context)
                    setInput1(getInput().toString())
                    setInput2(getInput().toString())
                }
            }

            // Блок выполнения действий над набранным номером
            else if (getInput1()?.length in 3..11) {

                // Очистка поля набора если после набора номера и нажатия * передумали звонить
                if (key == '#') {
                    setInput("")
                    setInput1("")
                    if (!isSpeaking) {
                        speakText("Номеронабиратель, очищен")
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
                else if ((key == '4' && getCall() == null) && !getIsRecording()) { // Блокируем команду если идет запись голосовой заметки
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Произнесите сообщение, которое вы хотите отправить на этот номер")
                            setInput("")
                            delay(10000)
                            withContext(Dispatchers.Main) {
                                stopDtmf()
                                utils.startSpeechRecognition(context) { result ->
                                    startDtmf()
                                    if (result != null) {
                                        if (result.length > 70) {
                                            speakText("Сообщение слишком длинное")
                                            setInput1("")
                                        } else {
                                            val phoneNumber = getInput1()
                                            setInput1("")
                                            if (phoneNumber?.length == 11) {
                                                val isSent = utils.sendSms(context, phoneNumber, result)
                                                if (isSent) {
                                                    val number = utils.numberToText(phoneNumber)
                                                    speakText("Сообщение \"$result\" успешно отправлено на номер $number ")
                                                    setInput1("")
                                                } else {
                                                    speakText("Не удалось отправить сообщение")
                                                    setInput1("")
                                                }
                                            } else {
                                                speakText("Номер телефона должен содержать 11 цифр")
                                                setInput1("")
                                            }
                                        }
                                    } else {
                                        speakText("Не удалось распознать сказанное. Попробуйте еще раз")
                                        setInput1("")
                                    }
                                }
                            }
                        } else {
                            speakText("Отсутствует интернет. Отправка сообщения не возможна")
                            setInput("")
                        }
                    }
                    return
                }

                // Добавление контакта в телефонную книгу
                else if ((key == '5' && getCall() == null) && !getIsRecording()) { // Блокируем команду если идет запись голосовой заметки
                    playSoundJob.launch {
                        if (utils.isOnline(context)) {
                            speakText("Произнесите фамилию и имя абонента, которого Вы хотите добавить в телефонную книгу")
                            setInput("")
                            delay(10000)
                            withContext(Dispatchers.Main) {
                                    stopDtmf()
                                utils.startSpeechRecognition(context) { result ->
                                    startDtmf()
                                    if (result != null) {
                                        if (result.length > 25) {
                                            speakText("Слишком длинное имя контакта")
                                            setInput1("")
                                        } else {
                                            val phoneNumber = getInput1()
                                            setInput1("")
                                            if (phoneNumber?.length == 11) {
                                                utils.saveContact(phoneNumber, result, context)
                                                val number = utils.numberToText(phoneNumber)
                                                speakText("Контакт $result с номером $number успешно добавлен в телефонную книгу")
                                                setInput1("")
                                            }
                                        }
                                    } else {
                                        speakText("Не удалось распознать сказанное. Попробуйте еще раз")
                                        setInput1("")
                                    }
                                }
                            }
                        } else {
                            speakText("Отсутствует интернет. Не получится сохранить контакт в телефонной книге")
                            setInput("")
                        }
                    }
                    return
                } else setInput1("")
            }

            // Остановка вызова если он есть а если нету то очистка поля ввода
            else if (key == '#') {

                flagDoobleClic = 0
                flagAmplitude = false
                flagFrequency = false
                flagSelective = false
                flagSimMonitor = false
                setFlagFrequencyLowHigt(false)
                flagDtmf = false
                textToSpeech.stop()
                isSpeaking = false
                setInput2("")

                if (getCall() == null) {
                    setInput("")

                    // Команда для установки будильника
                    if (input == "1") {
                        setInput("")
                        playSoundJob.launch {
                            speakText("Установка будильника, введите время в 24-часовом формате")
                            delay(17000)
                            val timeInput = getInput()
                            if (timeInput != null && timeInput.length == 4 && timeInput.all { it.isDigit() }) {
                                val hours = timeInput.substring(0, 2).toIntOrNull()
                                val minutes = timeInput.substring(2, 4).toIntOrNull()

                                if (hours != null && minutes != null && hours in 0..23 && minutes in 0..59) {
                                    alarmScheduler.setAlarm(hours, minutes)
                                    val formattedTime = utils.formatRussianTime(hours, minutes)
                                    speakText("Будильник установлен на $formattedTime")
                                } else {
                                    speakText("Некорректное время. Часы должны быть от 0 до 23, минуты от 0 до 59.")
                                }
                            } else {
                                speakText("Нужно ввести только 4 цифры")
                            }
                            setInput("")
                        }
                    }

                    // остановка будильника по команде 00#
                    else if (input == "00" && getCall() == null) {
                        alarmScheduler.stopAlarm()
                        speakText("Будильник отключен")
                    }

                    // свободная команда 2#
                    else if (input == "2" && getCall() == null) {
                        // СВОБОДНАЯ КОМАНДА!!!!!!!!!!!!
                    }

                    // Мониторинг сигнала SIM-карт по команде 3#
                    else if (input == "3" && getCall() == null) {
                        checkCallStateSequence()
                    }

                    // Команда удаления всех SMS по 4#
                    else if (input == "4" && getCall() == null) {
                        utils.deleteAllSms(context)
                    }

                    // Голосовой поиск абонента в телефонной книге по команде 5# с последующим удалением
                    else if ((input == "5" && getCall() == null) && !getIsRecording()) { // Блокируем команду если идет запись голосовой заметки
                        playSoundJob.launch {
                            if (utils.isOnline(context)) {
                                speakText("Назовите фамилию и имя абонента которого вы хотите удалить с телефонной книги")
                                setInput("")
                                delay(10000)
                                withContext(Dispatchers.Main) {
                                    stopDtmf()
                                    utils.startSpeechRecognition(context) { result ->
                                        startDtmf()
                                        if (result != null) {
                                            val isDeleted = utils.deleteContactByName(result, context)
                                            if (isDeleted) {
                                                speakText("Контакт $result успешно удален из телефонной книги")
                                                setInput1("")
                                            } else {
                                                speakText("Контакта $result нет в вашей телефонной книге. Проверьте правильность имени.")
                                                setInput1("")
                                            }
                                        } else {
                                            speakText("Не удалось распознать сказанное. Попробуйте еще раз")
                                            setInput1("")
                                        }
                                    }
                                }
                            } else {
                                speakText("Отсутствует интернет. Голосовой поиск не доступен")
                                setInput1("")
                                setInput("")
                            }
                        }
                    }

                    // Очистка номеров быстрого набора по команде 6*
                    else if (input == "6" && getCall() == null) {
                        textToSpeech.setOnUtteranceProgressListener(null)
                        numberA = ""
                        numberB = ""
                        numberC = ""
                        numberD = ""
                        lastDialedNumber = ""
                        speakText("Все номера быстрого набора, были очищены")
                        setInput("")
                    }

                    // свободная команда
                    else if (input == "8" && getCall() == null) {
                        //СВОБОДНАЯ КОМАНДА!!!!!!!!!!!!!
                    }

                    // Удаление голосовой заметки 99999#
                    else if (input == "99999" && getCall() == null) {
                        utils.deleteRecordedFile(isTorchOnIs, subscribers)
                        setInput("")

                    }

                    // Команда отключения генерации тонов CTCSS
                    else if (input == "58") {
                        if (getCall() == null && block) {
                            setFrequencyCtcss(0.0)
                            speakText("Внимание! Открытый канал, Все субтона репитера отключены")
                            utils.stopPlayback()
                            subscribers.clear() // Очищаем счетчик количества абонентов
                            setInput("")
                        } else  {
                        speakText("Команда заблокирована")
                        setInput("")
                        }
                    }

                    // Команда на уменьшение громкости речевых сообщений
                    else if (input == "77") {
                        if (getCall() == null && block) {
                            val currentVolume = getVolumeLevelTts()
                            if (currentVolume > 0) {
                                val step = if (currentVolume <= 30) 1f else 10f
                                val newVolume = (currentVolume - step).coerceAtLeast(0f)
                                setVolumeLevelTts(newVolume)
                                speakText("Громкость речевых сообщений уменьшена и теперь составляет ${newVolume.toInt()} процентов")
                            } else {
                                speakText("Достигнут минимальный уровень громкости")
                            }
                            setInput("")
                        } else {
                            speakText("Команда заблокирована")
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
                                    "Громкость вызова уменьшена и теперь составляет $volumeLevelCall процентов")
                            } else {
                                speakText("Достигнут минимальный уровень громкости")
                            }
                            setInput("")
                        } else  {
                            speakText("Команда заблокирована")
                            setInput("")
                        }
                    }

                    // Откат двухканального режима (возврат к одноканальному)
                    else if (input == "123" && getCall() == null) {
                        voxActivation = 500
                        setFrequencyCtcss(0.0)
                        setVolumeLevelCtcss(0.08)
                        speakText("Выполнен возврат к одноканальному режиму, достаточно одной радиостанции")
                        setInput("")
                    }

                    // Удаленная проверка последнего принятого вызова по команде 0#
                    else if (input == "0" && getCall() == null) {
                        utils.lastMissed(context, false)
                        setInput("")
                    }

                    // Очистка всего журнала вызовов по команде 00000#
                    else if (input == "00000" && getCall() == null) {
                        utils.clearCallLog(context)
                        setInput("")
                    }

                    // Блокировка служебных команд
                    else if (input == "1379" && getCall() == null) {
                        speakText("Установлена блокировка на служебные команды")
                        block = false
                        setInput("")
                    }

                    else if (!isSpeaking) {
                        if (getIsRecording()) {
                            pruning = 800 // Обрезаем последюю секунду чтобы потом не было слышно завершающего DTMF тона решетки
                            utils.stopRecording(isTorchOnIs, subscribers, pruning)
                        } else speakText("Номеронабиратель, очищен")
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


                        if ((getInput() == "000" || getInput() == "111" || getInput() == "555") && flagSelective) {
                            getInput()?.toIntOrNull()?.let {
                                isTorchOnIs = it
                            }
                        }

                        // Проверка на селективный вызов
                        if (isTorchOnIs == 111) {
                            when (key) {
                                'R' -> {
                                    subscribers.add('1') // Добавляем абонента, по тону 1000гц
                                    setSelectedSubscriberNumber(1)
                                    if (getFrequencyCtcss() == 203.5) {
                                        utils.playRecordedFile(isTorchOnIs, subscribers, 1)
                                    } else {
                                        setFrequencyCtcss(203.5) // Субтон для первой радиостанции
                                        speakText("Первый, репитер переключен на тебя")
                                    }
                                }
                                'S' -> {
                                    subscribers.add('2') // Добавляем абонента, по тону 1450гц
                                    setSelectedSubscriberNumber(2)
                                    if (getFrequencyCtcss() == 218.1) {
                                        utils.playRecordedFile(isTorchOnIs, subscribers, 2)
                                    } else {
                                        setFrequencyCtcss(218.1) // Субтон для первой радиостанции
                                        speakText("Второй, репитер переключен на тебя")
                                    }
                                }
                                'T' -> {
                                    subscribers.add('3') // Добавляем абонента, по тону 1750гц
                                    setSelectedSubscriberNumber(3)
                                    if (getFrequencyCtcss() == 233.6) {
                                        utils.playRecordedFile(isTorchOnIs, subscribers, 3)
                                    } else {
                                        setFrequencyCtcss(233.6) // Субтон для первой радиостанции
                                        speakText("Третий, репитер переключен на тебя")
                                    }
                                }
                                'V' -> {
                                    subscribers.add('4') // Добавляем абонента, по тону 2100гц
                                    setSelectedSubscriberNumber(4)
                                    if (getFrequencyCtcss() == 250.3) {
                                        utils.playRecordedFile(isTorchOnIs, subscribers, 4)
                                    } else {
                                        setFrequencyCtcss(250.3) // Субтон для первой радиостанции
                                        speakText("Четвертый, репитер переключен на тебя")
                                    }
                                }
                            }
                        }

                        if (isTorchOnIs == 0) {
                           setFrequencyCtcss(0.0)
                           subscribersNumber = 0
                           subscribers.clear()
                           speakText("Внимание открытый канал. Селективный вызов и звуковой отклик отключены")
                           isTorchOnIs = 5 // Устанавливаем в 5 чтобы исключить самопроизвольное срабатывание данного условия
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

    // Функция мониторинга возможности выполнить вызов для нахождения точки установки репитера
    private fun checkCallStateSequence() {
        scope.launch {
            speakText("Поиск точки расположения репитера. Попытки выполнить вызов будут выполняться непрерывно")
            delay(11000)

            flagSimMonitor = true
            var currentSim = 0

            while (flagSimMonitor) {
                setInput("87057895564") // Тестовый звонок будет выполняться на этот номер (можно указать любой)
                setSim(currentSim)
                delay(500) // Без этой задержки происходил откат к стандартной звонилке
                DtmfService.callStart(context)

                // Записываем состояние вызова в массив и если встретилась комбинация 1->7
                // (вызов отклонили) или 1->4 (трубку подняли) выходим из цикла
                for (i in 1..durationSeconds) {
                    val currentState = getCallState()
                    callStates.add(currentState)
                    if (callStates.size >= 2) {
                        val lastIndex = callStates.size - 1
                        if (callStates[lastIndex - 1] == 1 && (callStates[lastIndex] == 7 || callStates[lastIndex] == 4)) break
                    }
                    delay(100) // Фиксируем значения каждые 100мс
                }

                var isSuccess = false

                // Анализируем весь массив и если встретилась комбинация 9->1 (сеть есть дозвон пошел) выходим из цикла
                for (i in 0 until callStates.size - 1) {
                    if (callStates[i] == 9 && callStates[i + 1] == 1) {
                        isSuccess = true
                        break
                    }
                }

                callStates.clear()

                if (isSuccess) {
                    if (getCall() != null) DtmfService.callEnd(context)
                    delay(5000)
                    speakText("Попытка вызова с ${if (currentSim == 0) "первой" else "второй"} сим карты выполнена успешно")
                    setInput("")
                } else {
                    if (getCall() != null) DtmfService.callEnd(context)
                    speakText("Попытка вызова с ${if (currentSim == 0) "первой" else "второй"} сим карты не удалась, переместитесь в другое место, здесь нет сигнала базовой станции")
                    setInput("")
                }

                // Переключаем на следующую SIM-карту
                currentSim = if (currentSim == 0) 1 else 0

                // Задержка перед следующим вызовом
                delay(40000)
            }
        }
    }

    // Функции для подмены значений частот для двойного частотомера
    private fun substituteFrequencyLow(frequency: Float): Float {
        return when (frequency) {
            703.125f -> 697.000f
            765.625f -> 770.000f
            859.375f -> 852.000f
            937.500f -> 941.000f
            else -> frequency
        }
    }

    private fun substituteFrequencyHigh(frequency: Float): Float {
        return when (frequency) {
            1203.125f -> 1209.000f
            1328.125f -> 1336.000f
            1343.750f -> 1336.000f
            1484.375f -> 1477.000f
            1640.625f -> 1633.000f
            else -> frequency
        }
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
            speakText("Ошибка извлечения имени из телефонной книги")
        } else {
            val callerName = utils.getContactNameByNumber(callerNumber, context)
            while (getCallState() == 2) {
                val message = if (callerName.isNullOrEmpty()) {
                    "Внимание! Вам звонит абонент, имени которого нет в телефонной книге. Примите или отклоните вызов"
                } else {
                    "Внимание! Вам звонит абонент $callerName. Примите или отклоните вызов"
                }
                speakText(message)
                delay(17000)

            }
        }
    }

    override fun speakText(text: String) {

        // Воспроизводим соответствующий CTCSS (если значение = 0.0 то тон не воспроизводится)
        if (getFrequencyCtcss() != 0.0) { utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss()) }

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
                    if (getCallState() == 7) {
                        utils.stopPlayback()
                    }
                }

                @Deprecated("This method overrides a deprecated member", ReplaceWith("..."))
                override fun onError(utteranceId: String?) {
                    utils.stopPlayback()
                }
            })

            val utteranceId = System.currentTimeMillis().toString()

            CoroutineScope(Dispatchers.Main).launch {
                if (text == "Наберите номер") {
                utils.voxActivation(0, voxActivation) { textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
                } else if (text != "Один. Два. Три. Четыре. Пять. Поверка работоспособности вокс системы. Шесть. Семь. Восемь. Девять. Десять") {
                    utils.voxActivation(1500, voxActivation) { textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
                }  else {
                    delay(1500)
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
        }
    }
}

//  Log.d("Контрольный лог", "ЗНАЧЕНИЕ: $.")



