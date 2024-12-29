package com.mcal.dtmf.data.repositories.main

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.mcal.dtmf.R
import com.mcal.dtmf.data.repositories.preferences.PreferencesRepository
import com.mcal.dtmf.recognizer.DataBlock
import com.mcal.dtmf.recognizer.Recognizer
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.recognizer.StatelessRecognizer
import com.mcal.dtmf.service.DtmfService
import com.mcal.dtmf.utils.LogManager
import com.mcal.dtmf.utils.CallDirection
import com.mcal.dtmf.utils.Utils
import com.mcal.dtmf.utils.Utils.Companion.headphoneReceiver
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
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.cancellation.CancellationException

// Определение уровня логирования
enum class LogLevel {
    INFO,
    WARNING,
    ERROR
}

class MainRepositoryImpl(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val textToSpeech: TextToSpeech,

) : MainRepository {
    private var utils = Utils(this, false, CoroutineScope(Dispatchers.IO))
    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)
    private var recorderJob = scope
    private var recognizerJob = scope
    private var flashlightJob = scope
    private var playSoundJob = scope

    private val _spectrum: MutableStateFlow<Spectrum?> = MutableStateFlow(null)
    private val _key: MutableStateFlow<Char?> = MutableStateFlow(null)
    private val _input: MutableStateFlow<String?> = MutableStateFlow(null) // Значение переменной выводимой в поле ввода
    private val _call: MutableStateFlow<Call?> = MutableStateFlow(null)
    private val _callService: MutableStateFlow<InCallService?> = MutableStateFlow(null)
    private val _callState: MutableStateFlow<Int> = MutableStateFlow(Call.STATE_DISCONNECTED)
    private val _callDirection: MutableStateFlow<Int> =
        MutableStateFlow(CallDirection.DIRECTION_UNKNOWN)
    private val _callAudioRoute: MutableStateFlow<Int> =
        MutableStateFlow(CallAudioState.ROUTE_EARPIECE)
    private val _timer: MutableStateFlow<Long> = MutableStateFlow(0) // Значение основного таймера
    private val _outputFrequency1: MutableStateFlow<Float?> = MutableStateFlow(0f) // Значение истинной частоты с блока преобразования Фурье
    private val _flashlight: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _micKeyClick: MutableStateFlow<Int?> = MutableStateFlow(null)
    private val _powerState: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _isConnect: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _isDTMFStarted: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val blockingQueue = LinkedBlockingQueue<DataBlock>()
    private val recognizer = Recognizer()
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sampleRate = 16000 // Частота дискретизации запись звука
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // Конфигурация канала моно
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // Формат записи
    private val blockSize = 1024 // Размер буфера записи
    private var audioRecord: AudioRecord? = null
    private var isFlashlightOn = false // Флаг для устранения мерцания сигнальной вспышки
    private var isButtonPressed = false // Флаг подтверждающий факт выбора одной из сим карт
    private var flagSim = false
    private var sim = 0
    private var volumeLevel = 70 // Начальная установка громкости с последующей регулировкой в режиме Супертелефон
    private var flagVoise: Boolean = false// Флаг обеспечивающий быстрое отключение вспышки после завершения речевых сообщений
    private var slotSim1: String? = null
    private var slotSim2: String? = null
    private var isSpeaking = false // Флаг для отслеживания состояния произнесения текста

    init {

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Проверяем, есть ли доступ к режиму "Не беспокоить"
        if (notificationManager.isNotificationPolicyAccessGranted()) {
            // Устанавливаем фильтр прерываний, если он не установлен
            if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                try {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                } catch (e: SecurityException) {
                    // Обработка исключения, если доступ к изменению фильтра прерываний был потерян
                    e.printStackTrace()
                }
            }
        } else {
            // Запрашиваем доступ к режиму "Не беспокоить"
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        // Производим начальную уcтановку громкости всех потоков на 70%
        val volumeStreams = listOf(
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_VOICE_CALL
        )
        volumeStreams.forEach { stream ->
            audioManager.setStreamVolume(
                stream,
                (audioManager.getStreamMaxVolume(stream) * 0.7).toInt(),
                0
            )
        }
    }

    // Функция настройки громкости
    private fun setVolume(level: Int) {
        LogManager.logOnMain(LogLevel.INFO, "fun setVolume() Установка громкости на $level %", getErrorControl())
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * level / 100),
            0
        )
    }

    // Включение режима не беспокоить
    private fun enableDoNotDisturb() {
        LogManager.logOnMain(LogLevel.INFO, "fun enableDoNotDisturb() Режим НЕ БЕСПОКОИТЬ включен", getErrorControl())
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    // Отключение режима не беспокоить
    private fun disableDoNotDisturb() {
        LogManager.logOnMain(LogLevel.INFO, "fun disableDoNotDisturb() Режим НЕ БЕСПОКОИТЬ отключен", getErrorControl())
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    // Код включения громкоговорителя
    override fun enableSpeaker() { LogManager.logOnMain(LogLevel.INFO, "fun enableSpeaker() ГРОМКОГОВОРИТЕЛЬ включен", getErrorControl())
        // Перед включением громкоговорителя отключить режим не беспокоить
        disableDoNotDisturb()
        if (!getIsConnect()) {
            setCallAudioRoute(CallAudioState.ROUTE_SPEAKER)
        }
    }

    // Код отключения громкоговорителя
    override fun disableSpeaker() {
        LogManager.logOnMain(LogLevel.INFO, "fun disableSpeaker() ГРОМКОГОВОРИТЕЛЬ отключен", getErrorControl())
        // Перед отключением громкоговорителя включить режим не беспокоить
        enableDoNotDisturb()
        setCallAudioRoute(CallAudioState.ROUTE_EARPIECE)
    }

    // Тон для активации VOX радиостанции
    private fun delayTon1000hz(onComplete: suspend () -> Unit) {
        val timeTon1000 = preferencesRepository.getVoxActivation()
        val mediaPlayer = MediaPlayer.create(context, R.raw.beep1000hz)
        playSoundJob.launch {
            try {
                delay(2000)
                if (isActive && getModeSelection() == "Супертелефон") {
                    LogManager.logOnMain(LogLevel.INFO, "Тон активации VOX запущен на $timeTon1000 мс", getErrorControl())
                    mediaPlayer.start()
                    delay(timeTon1000)
                    mediaPlayer.stop()
                    mediaPlayer.release()
                }
                onComplete()
            } catch (e: CancellationException) {
                mediaPlayer.release()
            }
        }
    }

    // Основная логика работы таймера вспышки и дтмф анализа

    override fun getStartDtmfFlow(): Flow<Boolean> = flow {
        emitAll(_isDTMFStarted)
    }

    override fun getStartDtmf(): Boolean {
        return _isDTMFStarted.value
    }

    override fun setStartDtmf(enabled: Boolean, hasProblem: Boolean) {
        if (enabled) {
            LogManager.logOnMain(LogLevel.INFO, "fun setStartDtmf() Запуск DTMF системы${if (hasProblem) " (обнаружено повышенное сопротивление)" else ""}", getErrorControl())
            startDtmf()
            scope.launch {
                if (getIsConnect() || getModeSelection() == "Супертелефон") {
                    enableDoNotDisturb()
                } else disableDoNotDisturb()

                if (getCallDirection() != CallDirection.DIRECTION_INCOMING) {
                    var mediaId: Int = R.raw.start_timer
                    if (hasProblem) {
                        mediaId = R.raw.headset_malfunction
                    }

                    val mediaPlayer = MediaPlayer.create(context, mediaId)
                    delay(2000)
                    if (!mediaPlayer.isPlaying) {
                        if (getModeSelection() == "Репитер (2 Канала)") {
                            LogManager.logOnMain(LogLevel.INFO, "fun setStartDtmf() Воспроизведение ${if (hasProblem) "предупреждения о неисправности" else "сигнала старта"}", getErrorControl())
                            mediaPlayer.start()
                        }
                    }
                    mediaPlayer.setOnCompletionListener {
                        mediaPlayer.release()
                    }
                }
                if (getCallDirection() == CallDirection.DIRECTION_INCOMING) {
                    speakSuperTelephone()
                }
            }

            flashlightJob.launch {
                val timerCheckpoints = (0..90 step 5).map { it * 1000L }.reversed()
                var timer: Long
                do {
                    timer = getTimer()
                    if ((getModeSelection() != "Репитер (2 Канала)") && timer < 5) {
                        timer = 30000
                        isSpeaking = false
                        flagSim = false
                        setInput("")
                    }
                    if (timer != 0L) {
                        if (getCallDirection() == CallDirection.DIRECTION_UNKNOWN) {
                            setTimer(timer - 1000)
                        }
                        if (getCallDirection() == CallDirection.DIRECTION_OUTGOING) {
                            setTimer(timer - 1000)
                            playSoundJob.launch {
                                if (timerCheckpoints.contains(timer) && getModeSelection() != "Супертелефон") {
                                    speakText("Идет вызов")
                                    if (timer == 10000L) {
                                        LogManager.logOnMain(LogLevel.ERROR, "fun setStartDtmf() Превышено время ожидания ответа", getErrorControl())
                                        speakText("Не удачное расположение репитера, выберите другое место")
                                        DtmfService.callEnd(context)
                                    }
                                }
                            }
                        }
                        delay(1000)
                    }
                } while (timer > 0)
                if (getModeSelection() == "Репитер (2 Канала)") {
                    LogManager.logOnMain(LogLevel.INFO, "fun setStartDtmf() Таймер истек, остановка DTMF системы", getErrorControl())
                    stopDtmf()
                }
            }
        } else {
            if (getModeSelection() == "Репитер (2 Канала)") {
                LogManager.logOnMain(LogLevel.INFO, "fun setStartDtmf() Остановка DTMF системы", getErrorControl())
                stopDtmf()
            }
        }
    }

    override fun getServiceNumberFlow(): Flow<String> = preferencesRepository.getServiceNumberFlow()

    override fun getServiceNumber(): String = preferencesRepository.getServiceNumber()

    override fun setServiceNumber(number: String) = preferencesRepository.setServiceNumber(number)

    override fun getFlashSignalFlow(): Flow<Boolean> = preferencesRepository.getFlashSignalFlow()

    override fun getFlashSignal(): Boolean = preferencesRepository.getFlashSignal()

    override fun setFlashSignal(enabled: Boolean) = preferencesRepository.setFlashSignal(enabled)

    override fun getErrorControlFlow(): Flow<Boolean> = preferencesRepository.getErrorControlFlow()

    override fun getErrorControl(): Boolean = preferencesRepository.getErrorControl()

    override fun setErrorControl(enabled: Boolean) = preferencesRepository.setErrorControl(enabled)

    override fun getNumberAFlow(): Flow<String> = preferencesRepository.getNumberAFlow()

    override fun getNumberA(): String = preferencesRepository.getNumberA()

    override fun setNumberA(number: String) = preferencesRepository.setNumberA(number)

    override fun getNumberBFlow(): Flow<String> = preferencesRepository.getNumberBFlow()

    override fun getNumberB(): String = preferencesRepository.getNumberB()

    override fun setNumberB(number: String) = preferencesRepository.setNumberB(number)

    override fun getNumberCFlow(): Flow<String> = preferencesRepository.getNumberCFlow()

    override fun getNumberC(): String = preferencesRepository.getNumberC()

    override fun setNumberC(number: String) = preferencesRepository.setNumberC(number)

    override fun getNumberDFlow(): Flow<String> = preferencesRepository.getNumberDFlow()

    override fun getNumberD(): String = preferencesRepository.getNumberD()

    override fun setNumberD(number: String) = preferencesRepository.setNumberD(number)

    override fun getModeSelectionFlow() = preferencesRepository.getModeSelectionFlow()

    override fun getModeSelection() = preferencesRepository.getModeSelection()


    override fun setModeSelection(value: String) = preferencesRepository.setModeSelection(value)


    override fun getOutput1Flow(): Flow<Float> = flow {
        if (_outputFrequency1.value == null) {
            try {
                getOutput1()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_outputFrequency1.filterNotNull())
    }

    override fun getOutput1(): Float {
        return _outputFrequency1.value ?: 0f
    }

    override fun setOutput1(outputFrequency1: Float) {
        _outputFrequency1.update { outputFrequency1 }
        scope.launch {
            if ((outputFrequency1 == 1750f && getFlashSignal()) && getModeSelection() == "Супертелефон") {
                if (!isFlashlightOn) {
                    delay(100)
                    setFlashlight(true)
                    isFlashlightOn = true
                }
            } else {
                if (isFlashlightOn) {
                    delay(100)
                    setFlashlight(false)
                    isFlashlightOn = false
                }
            }
        }
    }

    // Значение основного таймера

    override fun getTimerFlow(): Flow<Long> = flow {
        emitAll(_timer)
    }

    override fun getTimer(): Long {
        return _timer.value
    }

    override fun setTimer(duration: Long) {
        // VOX СИСТЕМА отключение вспышки по завершению вызова и также через каждые 30 секунд
        if ((getModeSelection() == "Репитер (1 Канал)" || getModeSelection() == "Репитер (2 Канала+)") && duration == 0L) {
            // Проверяем, если вспышка включена, только тогда отключаем
            if (getFlashlight() == true) {
                setFlashlight(false)
            }
        }

        // Отключение громкоговорителя по истечении таймера
        if (getCallDirection() == CallDirection.DIRECTION_UNKNOWN && duration == 0L) {
            val audioRoute = getCallAudioRoute()
            if (audioRoute == CallAudioState.ROUTE_SPEAKER) {
                disableSpeaker()
            }
        }
        _timer.update { duration }
    }

    override fun getCallServiceFlow(): Flow<InCallService?> = flow {
        emitAll(_callService)
    }

    override fun getCallService(): InCallService? {
        return _callService.value
    }

    override fun setCallService(callService: InCallService?) {
        _callService.update { callService }
    }

    override fun getCallAudioRouteFlow(): Flow<Int> = flow {
        emitAll(_callAudioRoute)
    }

    override fun getCallAudioRoute(): Int {
        return _callAudioRoute.value
    }

    override fun setCallAudioRoute(audioRoute: Int) {
        getCallService()?.setAudioRoute(audioRoute)
        _callAudioRoute.update { audioRoute }
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

    override fun getCallDirectionFlow(): Flow<Int> = flow {
        emitAll(_callDirection)
    }

    override fun getCallDirection(): Int {
        return _callDirection.value
    }

    override fun setCallDirection(callDirection: Int) {
        // VOX СИСТЕМА если состояние вызова сменилась на активное отключаем вспышку
        if (getModeSelection() == "Репитер (1 Канал)" && callDirection == 2) {
            if (getFlashlight() == true) {
                setFlashlight(false)
            }
        }

        _callDirection.update { callDirection }
    }

    // Значение состояния вызова входящий/исходящий/активный/нет вызова
    override fun getCallStateFlow(): Flow<Int> = flow {
        emitAll(_callState)
    }

    override fun getCallState(): Int {
        return _callState.value
    }

    override fun setCallState(callState: Int) {
        LogManager.logOnMain(LogLevel.INFO, "setCallState() Состояние вызова: ${when (callState) {
            Call.STATE_DIALING -> "ИСХОДЯЩИЙ ВЫЗОВ (код состояния $callState)"
            Call.STATE_RINGING -> "ВХОДЯЩИЙ ВЫЗОВ (код состояния $callState)"
            Call.STATE_HOLDING-> "УДЕРЖАНИЕ ВЫЗОВА (код состояния $callState)"
            Call.STATE_ACTIVE -> "ВЫЗОВ АКТИВЕН (код состояния $callState)"
            Call.STATE_DISCONNECTED -> "ВЫЗОВ ЗАВЕРШЕН (код состояния $callState)"
            else -> "ПРОМЕЖУТОЧНОЕ СОСТОЯНИЕ (код состояния $callState)"
        }}", getErrorControl())

        // если режим супертелефон и не отсутствие звонка включаем громкоговоритель
        if (callState != 7 && getModeSelection() == "Супертелефон") enableSpeaker()
        _callState.update { callState }
    }

    override fun getSpectrumFlow(): Flow<Spectrum> = flow {
        if (_spectrum.value == null) {
            try {
                getSpectrum()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_spectrum.filterNotNull())
    }

    // Значения для отрисовки спектра

    override fun getSpectrum(): Spectrum? {
        return _spectrum.value
    }

    override fun setSpectrum(spectrum: Spectrum) {
        _spectrum.update { spectrum }
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

    // Значение инпут которое выводится в основное поле ввода

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

    // Продление времени таймера от нажатия любой кнопки
    override fun setInput(value: String, withoutTimer: Boolean) {
        if (getStartDtmf()) {
            _input.update { value }
            if (withoutTimer) return
            setTimer(30000)
        }
    }

    // Значение клавиш гарнитуры 79(центральная),25(вверх),24(вниз)
    override fun getMicKeyClickFlow(): Flow<Int> = flow {
        if (_micKeyClick.value == null) {
            try {
                getMicKeyClick()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_micKeyClick.filterNotNull())
    }

    override fun getMicKeyClick(): Int? {
        return _micKeyClick.value
    }

    override fun setMicKeyClick(value: Int) {
        _micKeyClick.update { value }
    }

    override fun getFlashlightFlow(): Flow<Boolean> = flow {
        if (_flashlight.value == null) {
            try {
                getFlashlight()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_flashlight.filterNotNull())
    }

    override fun getFlashlight(): Boolean? {
        return _flashlight.value
    }

    override fun setFlashlight(value: Boolean) {
        _flashlight.update { value }
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, value)
            LogManager.logOnMain(LogLevel.INFO, if (value) "setFlashlight() Вспышка включена" else "setFlashlight() Вспышка отключена", getErrorControl())
        } catch (e: Exception) {
            LogManager.logOnMain(LogLevel.ERROR, "setFlashlight() Ошибка при управлении вспышкой: ${e.message}", getErrorControl())
            e.printStackTrace()
        }
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
        _powerState.update { value }
    }

    //Значение подключены ли наушники

    override fun getIsConnectFlow(): Flow<Boolean> = flow {
        if (_isConnect.value == null) {
            try {
                getIsConnect()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        headphoneReceiver(context) { isConnected ->
            LogManager.logOnMain(LogLevel.INFO, "fun getIsConnect() Наушники${if (isConnected) " подключены" else " отключены"}", getErrorControl())
            _isConnect.update { isConnected }
        }
        emitAll(_isConnect.filterNotNull())
    }

    override fun getIsConnect(): Boolean {
        return _isConnect.value ?: false
    }

    override fun setIsConnect(value: Boolean) {
        _isConnect.update { value }
    }

    // Запись с микрофона в массив для анализа DTMF и работы электронной VOX системы.
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
                var flashlightOn = false
                var flashlightStartTime: Long = 0
                val buffer = ShortArray(blockSize)
                audio.startRecording()
                coroutineScope {
                    LogManager.logOnMain(LogLevel.INFO, "fun record() Запись звука начата", getErrorControl())
                    while (isActive) {
                        val bufferReadSize = audio.read(buffer, 0, blockSize)
                        val dataBlock = DataBlock(buffer, blockSize, bufferReadSize)
                        blockingQueue.put(dataBlock)

                        val maxAmplitude = buffer.take(bufferReadSize).maxOrNull()?.toFloat() ?: 0f
                        val holdTime = preferencesRepository.getVoxHold()
                        val threshold = preferencesRepository.getVoxThreshold()
                        val isSoundDetected = maxAmplitude > threshold

                        if ((isSoundDetected) &&
                            (getCallDirection() == CallDirection.DIRECTION_ACTIVE || preferencesRepository.getVoxSetting())) {

                            if (!flashlightOn && getModeSelection() == "Репитер (1 Канал)") {
                                flashlightOn = true
                                setFlashlight(true)
                                flashlightStartTime = System.currentTimeMillis()
                            } else {
                                flashlightStartTime = System.currentTimeMillis()
                            }
                        } else {
                            if (flashlightOn && (System.currentTimeMillis() - flashlightStartTime >= holdTime)) {
                                flashlightOn = false
                                setFlashlight(false)
                            }
                        }
                    }
                   LogManager.logOnMain(LogLevel.INFO, "fun record() Запись звука остановлена", getErrorControl())
                }
            }
            audio.stop()
        }
    }


    //Получение данных с блока распознавания
    override suspend fun recognize() {
        coroutineScope {
            LogManager.logOnMain(LogLevel.INFO, "fun recognize() Алгоритм Фурье запущен", getErrorControl())
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
            LogManager.logOnMain(LogLevel.INFO, "fun recognize() Алгоритм Фурье остановлен", getErrorControl())
        }
    }

    // Включение экрана из выключеного положения на 10 секунд
    override fun wakeUpScreen(context: Context) {
        LogManager.logOnMain(LogLevel.INFO, "fun wakeUpScreen() Пробуждение ЭКРАНА", getErrorControl())
        disableDoNotDisturb()
        setTimer(90000)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MyApp:ScreenWakeLock"
        )
        screenWakeLock.acquire(10000)
        screenWakeLock.release()
        enableDoNotDisturb()
    }

    //Основной блок обработки нажатий клавиш
    override fun clickKey(input: String, key: Char?) {
        // Регулировка громкости в режиме супертелефон
        if ((input == "A" || input == "B") && getModeSelection() == "Супертелефон") {
            scope.launch {
                var adjustment = 0
                var message = ""

                if (input == "A" && volumeLevel <= 90) {
                    adjustment = 10
                    message = "Громкость добавлена и теперь составляет "
                } else if (input == "B" && volumeLevel >= 50) {
                    adjustment = -10
                    message = "Громкость убавлена и теперь составляет "
                }

                if (adjustment != 0) {
                    volumeLevel += adjustment
                    setVolume(volumeLevel)
                    speakText("$message$volumeLevel процентов")
                } else {
                    if (input == "A") {
                        speakText("Достигнут максимальный уровень громкости")
                    } else if (input == "B") {
                        speakText("Достигнут минимальный уровень громкости")
                    }
                }
                setInput("")
            }
        }

        val previousKey = _key.value
        // Проверяем, изменился ли ключ
        if (key != previousKey && !(input.isEmpty() && key == ' ')) {
            LogManager.logOnMain(LogLevel.INFO, "fun clickKey() input=$input key=$key", getErrorControl())
        }

        if (input.length > 11 && getCallDirection() != CallDirection.DIRECTION_INCOMING) {
            speakText("Поле ввода переполнилось, и поэтому было очищенно")
            flagVoise = getModeSelection() != "Репитер (1 Канал)"
            setInput("")
        }

        if (key != ' ' && key != previousKey) {
            if (getModeSelection() == "Репитер (2 Канала+)" && getStartDtmf()) {
                setFlashlight(true)
                setTimer(30000)
            }

            // Обработка нажатий клавиш быстрого набора
            if (key in 'A'..'D' && getModeSelection() != "Супертелефон") {

                val currentNumber = when (key) {
                    'A' -> getNumberA()
                    'B' -> getNumberB()
                    'C' -> getNumberC()
                    'D' -> getNumberD()
                    else -> ""
                }

                if (input.length in 3..11) {
                    if (currentNumber != input) {
                        when (input) {
                            getNumberA() -> {
                                speakText("Этот номер уже закреплен за клавишей А")
                                setInput("")
                            }
                            getNumberB() -> {
                                speakText("Этот номер уже закреплен за клавишей Б")
                                setInput("")
                            }
                            getNumberC() -> {
                                speakText("Этот номер уже закреплен за клавишей С")
                                setInput("")
                            }
                            getNumberD() -> {
                                speakText("Этот номер уже закреплен за клавишей Д")
                                setInput("")
                            }
                            else -> {
                                scope.launch {
                                    val callerName = utils.getContactNameByNumber(input, context)
                                    LogManager.logOnMain(LogLevel.INFO, "clickKey()  Закрепление номера $input за клавишей быстрого набора $key", getErrorControl())
                                    speakText(if (callerName.isNullOrEmpty()) {
                                        "Неизвестный номер был закреплен за этой клавишей"
                                    } else {
                                        "Номер абонента $callerName был закреплен за этой клавишей"
                                    })
                                }
                                when (key) {
                                    'A' -> setNumberA(input)
                                    'B' -> setNumberB(input)
                                    'C' -> setNumberC(input)
                                    'D' -> setNumberD(input)
                                }
                                setInput("")
                            }
                        }
                    } else {
                        speakText("Этот номер ранее был закреплен за этой клавишей")
                    }
                } else if (getStartDtmf()) {
                    val number = when (key) {
                        'A' -> getNumberA()
                        'B' -> getNumberB()
                        'C' -> getNumberC()
                        'D' -> getNumberD()
                        else -> ""
                    }

                    if (number.length in 3..11) {
                        LogManager.logOnMain(LogLevel.INFO, "clickKey()  Подготовка номера $number от клавиши $key к вызову", getErrorControl())
                        scope.launch {
                            setInput(number)
                            val callerName = utils.getContactNameByNumber(number, context)
                            speakText(if (callerName.isNullOrEmpty()) {
                                "Будет выполнен вызов абонента с неизвестным номером"
                            } else {
                                "Номер абонента $callerName набран"
                            })
                            flagSim = false
                            flagVoise = getModeSelection() != "Репитер (1 Канал)"
                            delay(7000) // Если уменьшить это время то возникает ошибка в функции ттс и не слышно сообщения выберите с какой сим карты выполнить вызов
                            DtmfService.callStart(context)
                        }
                    } else {
                        LogManager.logOnMain(LogLevel.INFO, "clickKey() Нажатие клавиши быстрого набора $key без закрепления номера", getErrorControl())
                        speakText("Клавиша свободна, вы можете закрепить за ней любой номер быстрого набора")
                    }
                }
            } else if (key == '*') {

                if (input == "") {
                    speakText("Наберите номер")
                    flagVoise = !(getModeSelection() == "Репитер (1 Канал)" || getModeSelection() == "Репитер (2 Канала+)")
                }

                if (getCall() != null) {
                    if (getCallDirection() == CallDirection.DIRECTION_INCOMING) {
                        LogManager.logOnMain(LogLevel.INFO, "Вызов функции DtmfService.callAnswer(context) ДЛЯ ПРИЕМА ВХОДЯЩЕГО ВЫЗОВА", getErrorControl())
                        DtmfService.callAnswer(context)
                        setInput("")
                        if (textToSpeech.isSpeaking) {
                            LogManager.logOnMain(LogLevel.INFO, "textToSpeech Принудительная остановка ТТС по нажатию (*)", getErrorControl())
                            textToSpeech.stop()
                        }
                    }
                }

                // Удаленная проверка пропущенного вызова по команде 0*
                else if (input == "0") {
                    utils.lastMissed(context)
                    setInput("")
                    flagVoise = false
                }

                // Удаленный контроль температуры и заряда аккумулятора по команде 1*
                else if (input == "1") {
                    utils.batteryStatus(context)
                    setInput("")
                    flagVoise = false
                }

                // Удаленное сообщение о текущем времени по команде 2*
                else if (input == "2") {
                    utils.speakCurrentTime()
                    setInput("")
                    flagVoise = false
                }

                // Удаленный контроль уровня сети по команде 3*
                else if (input == "3") {
                    utils.getCurentCellLevel(context)
                    setInput("")
                    flagVoise = false


                // Очистка номеров быстрого набора по команде 4*
                } else if (input == "4") {
                    listOf(::setNumberA, ::setNumberB, ::setNumberC, ::setNumberD).forEach { it("") }
                    setInput("")
                    speakText("Все номера быстрого набора, были очищены")
                    flagVoise = false

                // Голосовой поиск абонента в телефонной книге по команде 5*
                } else if (input == "5") {
                    LogManager.logOnMain(LogLevel.INFO, "ЗАПУСК ГОЛОСОВОГО ПОИСК", getErrorControl())
                    scope.launch {
                        if (utils.isOnline(context)) {
                            speakText("Назовите имя абонента которому вы хотите позвонить")
                            setInput("")
                            flagVoise = true // Вспышка останется включенной после произнесения
                            delay(7000)
                            // Переключаемся на основной поток
                            withContext(Dispatchers.Main) {
                                if (getStartDtmf()) {
                                    LogManager.logOnMain(LogLevel.INFO, "fun setStartDtmf() Остановка DTMF системы", getErrorControl())
                                    stopDtmf()
                                }
                                setFlashlight(true)
                                utils.startSpeechRecognition(context)
                                flagVoise = false
                            }
                        } else {
                            speakText("Отсутствует интернет. Голосовой поиск не доступен")
                            setInput("")
                            flagVoise = false
                        }
                    }
                }


                if (getCall() == null && input.length in 3..11) {
                    LogManager.logOnMain(LogLevel.INFO, "Вызов функции DtmfService.callStart(context) ДЛЯ ОСУЩЕСТВЛЕНИЯ ИСХОДЯЩЕГО ВЫЗОВА", getErrorControl())
                    setInput(input)
                    DtmfService.callStart(context)
                }

                } else if ((input.length in 3..11 && (key == '1' || key == '2')) && flagSim) {
                setInput(input)
                val operatorName = when (key) {
                    '1' -> slotSim1
                    '2' -> slotSim2
                    else -> return
                }

                val operatorNameResolved = resolveOperatorName(operatorName)

                scope.launch {
                    isButtonPressed = true
                    sim = if (key == '1') 0 else 1
                    speakText("Звоню с $operatorNameResolved")
                    flagVoise = true
                    delay(5000)
                    if (getModeSelection() == "Репитер (1 Канал)") {
                        setFlashlight(true)
                    }
                    DtmfService.callStartSim(context, isButtonPressed, sim)
                    flagSim = false
                }
            }

            // Остановка вызова если он есть а если нету то очистка поля ввода
            else if (key == '#') {
                isSpeaking = false
                flagSim = false

                // Проверяем, работает ли TTS перед остановкой
                if (textToSpeech.isSpeaking) {
                    LogManager.logOnMain(LogLevel.INFO, "textToSpeech Принудительная остановка ТТС по нажатию (#)", getErrorControl())
                    textToSpeech.stop()
                }

                if (getCall() == null) {
                    setInput("")
                    speakText("Номеронабиратель, очищен")
                    flagVoise = !(getModeSelection() == "Репитер (1 Канал)" || getModeSelection() == "Репитер (2 Канала+)")
                } else {
                    DtmfService.callEnd(context)
                }
            } else {
                setInput(input + key)
            }
        }
    }

    // Запуск дтмф анализа
    override fun startDtmf() {
        setVolume(70)
        if (getModeSelection() == "Репитер (2 Канала)") {
            if (getFlashlight() == false) setFlashlight(true)
        }
        _isDTMFStarted.update { true }
        DtmfService.start(context)
        initJob()
        setInput("")
        setTimer(30000)
        recorderJob.launch { record() }
        recognizerJob.launch { recognize() }
    }

    private fun initJob() {
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + job)
        recorderJob = scope
        recognizerJob = scope
        flashlightJob = scope
        playSoundJob = scope
    }

    // Остановка дтмф анализа
    override fun stopDtmf() {
        setInput("")
        isSpeaking = false
        flagSim = false
        _isDTMFStarted.update { false }
        job.cancel()
        setFlashlight(false)
        audioRecord?.stop()
        audioRecord = null
        textToSpeech.stop()
        blockingQueue.clear()
        recognizer.clear()
        DtmfService.stop(context)
        setTimer(0)
    }

    // Конвертация названий операторов
    private fun resolveOperatorName(operatorName: String?): String {
        return when (operatorName) {
            "ACTIV" -> "Актив"
            "ALTEL" -> "Алтэл"
            "MTS" -> "МТС"
            "Beeline KZ" -> "Билайн"
            "Megafon" -> "Мегафон"
            "Tele2" -> "Теле2"
            else -> operatorName ?: "Неизвестный оператор"
        }
    }

    // Диалоги выбора и озвучивания сим карт
    override fun selectSimCard(slot1: String?, slot2: String?, phoneAccount: Int) {
        LogManager.logOnMain(LogLevel.INFO, "selectSimCard() Слот 1 $slot1 Слот 2 $slot2 Количество активных аккаунтов $phoneAccount", getErrorControl())
        scope.launch {
            if (getCallDirection() != CallDirection.DIRECTION_INCOMING && phoneAccount > 1) {
                if (!flagSim) {
                    setFlashlight(true)
                    speakText("Выберите с какой сим карты выполнить вызов")
                    flagVoise = false // Вспышка отключится после произнесения
                } else LogManager.logOnMain(LogLevel.ERROR, "СБОЙ ПРОИЗНЕСЕНИЯ Выберите с какой сим карты выполнить вызов. Значение флага flagSim=$flagSim", getErrorControl())
                flagSim = true
            } else {
                // Обработка случаев, когда в телефоне активна только одна сим-карта
                val operatorName = when {
                    slot1 == "Нет сигнала" && getCallDirection() == CallDirection.DIRECTION_UNKNOWN -> slot2
                    slot2 == "Нет сигнала" && getCallDirection() == CallDirection.DIRECTION_UNKNOWN -> slot1
                    else -> null
                }

                operatorName?.let {
                    isButtonPressed = true
                    val operatorDisplayName = resolveOperatorName(it)
                    speakText("Звоню с $operatorDisplayName")
                    flagVoise = true // Вспышка останется включенной после произнесения
                    delay(5000)
                    if (getModeSelection() == "Репитер (1 Канал)") {
                        setFlashlight(true)
                    }
                    DtmfService.callStartSim(context, isButtonPressed, 0)
                    isButtonPressed = false
                    flagSim = false
                }
            }
            slotSim1 = slot1
            slotSim2 = slot2
        }
    }

    // Основная функция озвучивания входящих вызовов
    override suspend fun speakSuperTelephone() {
        isSpeaking = false
        LogManager.logOnMain(LogLevel.INFO, "speakSuperTelephone() Озвучивание входящего вызова", getErrorControl())
        flagVoise = getModeSelection() != "Репитер (1 Канал)"
        delay(500) // без этой задержки не получалось корректное значение callerNumber
        val callerNumber = (_input.value).toString()
        if (callerNumber.isEmpty()) {
            LogManager.logOnMain(LogLevel.ERROR, "fun speakSuperTelephone() Ошибка извлечения имени из телефонной книги", getErrorControl())
                speakText("Ошибка извлечения имени из телефонной книги")
        } else {
            val callerName = utils.getContactNameByNumber(callerNumber, context)
            while (getCallDirection() == CallDirection.DIRECTION_INCOMING) {
                val message = if (callerName.isNullOrEmpty()) {
                    "Внимание! Вам звонит абонент, имени которого нет в телефонной книге. Примите или отклоните вызов"
                } else {
                    "Внимание! Вам звонит абонент $callerName. Примите или отклоните вызов"
                }
                speakText(message)
                delay(if (getModeSelection() == "Супертелефон") 17000 else 13000)
            }
        }
    }

    override fun speakText(text: String) {
        LogManager.logOnMain(LogLevel.INFO, "speakText() ЗНАЧЕНИЕ ФЛАГОВ flagVoise=$flagVoise, isSpeaking=$isSpeaking", getErrorControl())
        if (isSpeaking) { // Если ттс не завершил произношение или не сработали фукции onDone или onError
            LogManager.logOnMain(LogLevel.ERROR, "fun speakText() СБОЙ РАБОТЫ ТТС. Выход по флагу isSpeaking=$isSpeaking последующая команда приходит когда еще не завершена предыдущая, слишком частое поступление запросов на произнесение", getErrorControl())
            return
        }

        isSpeaking = true // Устанавливаем флаг, что TTS запущен
        val weakTextToSpeech = WeakReference(textToSpeech)

        weakTextToSpeech.get()?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                LogManager.logOnMain(LogLevel.INFO, "fun speakText() НАЧАТО Произнесение текста \"$text\"", getErrorControl())
                // VOX СИСТЕМА включаем вспышку при старте сообщения ттс
                if (getModeSelection() == "Репитер (1 Канал)") {
                    setFlashlight(true)
                }
            }

            override fun onDone(utteranceId: String?) {
                LogManager.logOnMain(LogLevel.INFO, "fun speakText() ЗАВЕРШЕНО Произнесение текста \"$text\"", getErrorControl())
                // VOX СИСТЕМА выключаем вспышку при остановке сообщения ттс
                if ((getModeSelection() == "Репитер (1 Канал)" || getModeSelection() == "Репитер (2 Канала+)") && !flagVoise) {
                    setFlashlight(false)
                }
                isSpeaking = false // Сбрасываем флаг, когда TTS завершено
            }

            @Deprecated("Этот метод устарел")
            override fun onError(utteranceId: String?) {
                LogManager.logOnMain(LogLevel.ERROR, "fun speakText() Ошибка при произнесении текста: $utteranceId", getErrorControl())
                isSpeaking = false // Сбрасываем флаг в случае ошибки
            }
        })

        val utteranceId = System.currentTimeMillis().toString()
        delayTon1000hz { weakTextToSpeech.get()?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
    }
}


//  Log.d("Контрольный лог", "setStartFlashlight ВЫЗВАНА: $conType")
// context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))


