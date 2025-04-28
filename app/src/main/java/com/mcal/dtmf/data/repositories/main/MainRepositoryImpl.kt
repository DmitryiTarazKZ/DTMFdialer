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
import android.net.Uri
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
import kotlinx.coroutines.flow.asFlow
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
    private val _call: MutableStateFlow<Call?> = MutableStateFlow(null)
    private val _callState: MutableStateFlow<Int> = MutableStateFlow(Call.STATE_DISCONNECTED)
    private val _powerState: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _isRecording: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _amplitudeCheck: MutableStateFlow<Boolean?> = MutableStateFlow(null)
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


    private var subscribersNumber = 0
    private val subscribers = mutableSetOf<Char>()

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
        Log.e("Контрольный лог", "ЗНАЧЕНИЕ ЧАСТОТЫ: $frequencyCtcss")
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
        if (getFrequencyCtcss() != 0.0 && (callState == 2 || callState == 1 || callState == 4)) {
            // Генерируем субтон в поток вызова если входящий, исходящий или трубку подняли
            utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss() - 0.02) // Коррекция уровня для устранения опасности зависания на передаче
        }
        if (getFrequencyCtcss() != 0.0 && callState == 7) {
            utils.stopPlayback() // Прекращаем генерацию если вызов завершен
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

    override fun getIsRecordingFlow(): Flow<Boolean> = flow {
        if (_isRecording.value == null) {
            try {
                getPower()
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
                        utils.voxActivation(1500) { utils.playDtmfTone(ton, 1000, 1000) }
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
                                8000
                            }

                            // if (getSim() == 5) setSim(0) сразу звонок с той сим с которой звонили последний раз
                            setSim(5) // Заставляем выбрать с какой сим карты выполнить вызов
                            delay(delayTime.toDuration(DurationUnit.MILLISECONDS)) // Задержка перед началом вызова
                            DtmfService.callStart(context)
                            setInput1(getInput().toString())  // Закоментировать если надо сразу звонок с той сим с которой звонили последний раз

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

                // Очистка номеров быстрого набора по команде 3*
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
                    speakText(smsText, false)
                    setInput("")
                }

                // Проверка свободной памяти 9999*
                else if (input == "9999" && getCall() == null) {
                    val mb = utils.getAvailableMemoryInMB()
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
                    utils.startRecording(isTorchOnIs, subscribers)
                    setInput("")
                }

                // Удаление голосовой заметки
                else if (input == "8" && getCall() == null) {
                    utils.deleteRecordedFile(isTorchOnIs, subscribers)
                    setInput("")

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
                            utils.playDtmfTones(periodVox - 100, durationVox)
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
                        utils.playDtmfTones(periodVox + 100, durationVox)
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
                                utils.playDtmfTone(0, 1000, durationVox)
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
                                setFrequencyCtcss(finalValue.toDouble())
                                speakText("Частота субтона репитера установлена на ${formatVolumeLevel(getFrequencyCtcss())} герца. Громкость ${formatVolumeLevel(getVolumeLevelCtcss() * 1000)} процентов", false)
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
                        playSoundJob.launch {
                            utils.voxActivation(1500, voxActivation) { utils.playDtmfTones(600, 300) }
                            setInput("")
                            delay(12000)
                            if (getInput() == "") speakText("Гальваническая связь полностью отсутствует. Отличный показатель для неселективного вызова", false)
                            if (getInput() == "0123456789") speakText("Гальваническая связь присутсвует, все тона восприняты без ошибок", false)
                            if (getInput() != "" && getInput() != "0123456789") { speakText("Гальваническая связь присутствует, тона прошли с ошибками", false) }
                            setInput("")
                        }
                    } else  {
                        speakText("Команда заблокирована", false)
                        setInput("")
                    }
                }

                // Установка громкости субтона
                else if (input == "66") {
                    if (getCall() == null && block) {
                        setInput("")
                        playSoundJob.launch {
                            speakText("Введите громкость субтона", false)
                            delay(14000)
                            val volumeCtcsInput = getInput()
                            val volumeInputValue = volumeCtcsInput?.toIntOrNull()

                            if (volumeInputValue != null && volumeInputValue in 0..1000) {
                                setVolumeLevelCtcss(volumeInputValue / 1000.0) // Преобразуем значение от 0-1000 в диапазон от 0.01 до 1
                                speakText(
                                    "Громкость субтона установлена на $volumeInputValue процентов. его частота ${formatVolumeLevel(getFrequencyCtcss())} герц",
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
                            setFrequencyCtcss(0.0)
                            speakText("Внимание! Открытый канал, Все субтона репитера отключены", false)
                            utils.stopPlayback()
                            subscribers.clear() // Очищаем счетчик количества абонентов
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
                        if (getIsRecording()) {
                            utils.stopRecording(isTorchOnIs, subscribers)
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
                                    subscribers.add('1') // Добавляем абонента, по тону 1000гц
                                    setSelectedSubscriberNumber(1)
                                    if (getFrequencyCtcss() == 203.5) {
                                            utils.playRecordedFile(isTorchOnIs, subscribers, 1)
                                    } else {
                                        setFrequencyCtcss(203.5) // Субтон для первой радиостанции
                                        speakText("Первый, репитер переключен на тебя", false)
                                    }
                                }
                                'S' -> {
                                    subscribers.add('2') // Добавляем абонента, по тону 1450гц
                                    setSelectedSubscriberNumber(2)
                                    if (getFrequencyCtcss() == 218.1) {
                                            utils.playRecordedFile(isTorchOnIs, subscribers, 2)
                                    } else {
                                        setFrequencyCtcss(218.1) // Субтон для первой радиостанции
                                        speakText("Второй, репитер переключен на тебя", false)
                                    }
                                }
                                'T' -> {
                                    subscribers.add('3') // Добавляем абонента, по тону 1750гц
                                    setSelectedSubscriberNumber(3)
                                    if (getFrequencyCtcss() == 233.6) {
                                            utils.playRecordedFile(isTorchOnIs, subscribers, 3)
                                    } else {
                                        setFrequencyCtcss(233.6) // Субтон для первой радиостанции
                                        speakText("Третий, репитер переключен на тебя", false)
                                    }
                                }
                                'V' -> {
                                    subscribers.add('4') // Добавляем абонента, по тону 2100гц
                                    setSelectedSubscriberNumber(4)
                                    if (getFrequencyCtcss() == 250.3) {
                                            utils.playRecordedFile(isTorchOnIs, subscribers, 4)
                                    } else {
                                        setFrequencyCtcss(250.3) // Субтон для первой радиостанции
                                        speakText("Четвертый, репитер переключен на тебя", false)
                                    }
                                }
                            }
                        }

                        if (isTorchOnIs == 0) {
                           setFrequencyCtcss(0.0)
                           subscribersNumber = 0
                           subscribers.clear()
                           speakText("Внимание открытый канал. Селективный вызов и звуковой отклик отключены", false)
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

        if (getFrequencyCtcss() != 0.0) { utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss()) } // Воспроизводим соответствующий CTCSS (если значение = 0.0 то тон не воспроизводится)

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
                    utils.stopPlayback()
                }

                @Deprecated("This method overrides a deprecated member", ReplaceWith("..."))
                override fun onError(utteranceId: String?) {
                    utils.stopPlayback()
                }
            })

            val utteranceId = System.currentTimeMillis().toString()

            CoroutineScope(Dispatchers.Main).launch {
                if (text != "Один. Два. Три. Четыре. Пять. Поверка работоспособности вокс системы. Шесть. Семь. Восемь. Девять. Десять") {
                    utils.voxActivation(1500, voxActivation) { textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
                } else {
                    delay(1500)
                    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
        }
    }
}

//  Log.d("Контрольный лог", "ЗНАЧЕНИЕ: $conType")



