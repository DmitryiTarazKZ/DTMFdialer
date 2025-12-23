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
import com.mcal.dtmf.recognizer.DataBlock
import com.mcal.dtmf.recognizer.Recognizer
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.recognizer.StatelessRecognizer
import com.mcal.dtmf.scheduler.AlarmScheduler
import com.mcal.dtmf.service.AlarmSoundService
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
import java.util.Calendar
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
    private var flashlightJob = scope
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
    private val _periodCtcss: MutableStateFlow<Int> = MutableStateFlow(0)
    private val _durationCtcss: MutableStateFlow<Int> = MutableStateFlow(0)
    private val _micKeyClick: MutableStateFlow<Int?> = MutableStateFlow(null) // Значение нажатой кнопки гарнитуры
    private val _timer: MutableStateFlow<Long> = MutableStateFlow(0) // Значение основного таймера
    private val _isDTMFStarted: MutableStateFlow<Boolean> = MutableStateFlow(false) // включение DTMF
    private val _magneticField: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _magneticFieldFlag: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _flashlight: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    private val _statusDtmf: MutableStateFlow<Boolean?> = MutableStateFlow(null)

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
    private var currentTorchState: Boolean? = null  // Текущее состояние вспышки для оптимизации (централизованное управление)
    private var monitorNumber: String = "87057895564" // Номер по умолчанию для тестовых звонков для проверки сети
    private var voxActivation = 500L
    private var isBeaconActiveManually = false // Флаг работы маяка

    // Ключи для SharedPreferences.
    private val KEY_A = "QUICK_DIAL_A"
    private val KEY_B = "QUICK_DIAL_B"
    private val KEY_C = "QUICK_DIAL_C"
    private val KEY_D = "QUICK_DIAL_D"

    /**
     * Загружает номер быстрого набора (строка) из SharedPreferences.
     */
    private fun loadQuickDialNumber(context: Context, key: String): String {
        // Используем SharedPreferences с именем "QUICK_DIAL_PREFS"
        val prefs = context.getSharedPreferences("QUICK_DIAL_PREFS", Context.MODE_PRIVATE)
        return prefs.getString(key, "") ?: "" // Возвращает сохраненный строковый номер или ""
    }

    /**
     * Сохраняет номер быстрого набора (строка) в SharedPreferences.
     */
    private fun saveQuickDialNumber(context: Context, key: String, number: String) {
        val prefs = context.getSharedPreferences("QUICK_DIAL_PREFS", Context.MODE_PRIVATE)
        prefs.edit().putString(key, number).apply()
    }

    private var numberA: String = ""
    private var numberB: String = ""
    private var numberC: String = ""
    private var numberD: String = ""

    private var isTorchOn = false
    private var flagVox = false
    private var flagAmplitude = false
    private var flagSimMonitor = false
    private var flagFrequency = false
    private var flagDtmf = false
    private var flagFlashLight = false
    private var flagSelective = false
    private var dtmfPlaying = false
    private var isProgrammingMode = false
    private var lastKeyPressTime: Long = 0
    private var flagDoobleClic = 0
    private var isCommandProcessed = false // Флаг для предотвращения двойного срабатывания по книге контактов и помощи
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

    private var isContactMode = false // Флаг, который показывает, находимся ли мы в режиме просмотра контактов
    private var contacts: List<Pair<String, String>> = emptyList() // Список контактов (имя и номер)
    private var currentContactIndex = -1 // Индекс текущего просматриваемого контакта
    private var previousContactsSize = 0 // Предыдущий размер списка контактов для отслеживания изменений

    private var isHelpMode = false // Флаг, который показывает, находимся ли мы в режиме просмотра помощи
    private var sentences = emptyList<String>() // Список всех предложений помощи
    private var currentSentenceIndex = 0 // Индекс текущего просматриваемого предложения

    private var smsNavigationIndex: Int = -1 // -1 означает, что навигация не активна, 0 — самое новое SMS.
    private var totalSmsCount: Int = 0      // Общее количество SMS, для проверки границ.
    private var prefix = ""
    
    // Локальный список предложений помощи (команды репитера)
    private fun getHelpSentences(): List<String> {
        return listOf(
            "PTT плюс ноль звездочка, Последний входящий вызов.",
            "PTT плюс ноль решетка, Последний пропущенный вызов.",
            "PTT плюс пять нулей решетка, Очистить весь журнал вызовов.",
            "PTT плюс один звездочка, Текущие время и дата.",
            "PTT плюс один решетка, Будильник, отключение PTT плюс два нуля решетка.",
            "PTT плюс два звездочка, Состояние аккумулятора.",
            "PTT плюс два решетка, Маяк, отключение PTT плюс два нуля решетка.",
            "PTT плюс три звездочка, Уровни сети обеих сим карт.",
            "PTT плюс три решетка, Запуск мониторинга есть ли сеть, работает со сбоями.",
            "PTT плюс четыре звездочка, пролистывание вверх по списку СМС.",
            "PTT плюс четыре решетка, пролистывание вниз по списку СМС.",
            "PTT плюс пять звездочка, Голосовой поиск абонента в контактах с последующим вызовом, при наличии интернета.",
            "PTT плюс пять решетка, Голосовой поиск абонента в контактах с последующим удалением, при наличии интернета.",
            "PTT плюс шесть звездочка, Вход в книгу контактов с пролистыванием с выбранным шагом. В этом режиме PTT плюс четыре, прослушать номер, PTT плюс пять, удаление выбранного контакта, PTT плюс звездочка, позвонить, PTT плюс решетка, выход из режима.",
            "PTT плюс шесть решетка, Команда не назначена.",
            "PTT плюс семь звездочка, Создание голосовых заметок. Настройте точку амплитуды командой PTT плюс сорок шесть звездочка для автоматической остановки или после окончания произнесения не отпуская PTT надо нажать решетку для остановки вручную.",
            "В первую очередь предназначена для проверки качества прохождения сигнала: абонент к репитеру и наоборот, так как по команде девять звездочка можно прослушивать самого себя. Если при воспроизведении ВОКС отключается можно поднять громкость радиостанции репитера.",
            "А также проверке на наличии искажений и шумов. Можно записать контрольную заметку при отключенном аудиокабеле звук запишется максимально чистым и потом записать вторую заметку уже все подключив и говоря в рацию абонента а затем сравнить записи.",
            "Если отличий практически нету это указывает на правильные уровни громкости и то что качество выбранной радиостанции репитера высокое. Если вторая запись прослушивается намного хуже чем первая, например при прослушивании присутствуют шум, свист, искажения или посторонние звуки это может говорить о том что радиостанцию репитера желательно заменить.",
            "Можно использовать для определения точек где связь не стабильна, называя место и номер записи. Или как голосовая записная книжка.",
            "Также если в радиусе действия репитера находится несколько радиостанций то можно использовать эту команду для отправки голосовых сообщений называя адресата а затем он на своей рации сможет прослушать адресованное ему сообщение и наоборот.",
            "Либо включив селективную отправку сообщений командой сто одиннадцать PTT плюс F и настроив субтона и вызывные тона на каждой из четырех радиостанций.",
            "Также при подключенном аудиокабеле режим позволяет проверить отключается ли микрофон смартфона, для этого достаточно что то сказать рядом со смартфоном не через рацию абонента и затем прослушать запись командой девять звездочка.",
            "Если запись слышна это может в дальнейшем создавать случайные срабатывания от посторонних звуков. Для проверки ситуации можно воспользоваться оригинальными наушниками и подключив их проверить отключение микрофона.",
            "Если отключение происходит это указывает на неправильную работу аудиокабеля. Если нет то это конструктивная особенность смартфона.",
            "PTT плюс семь решетка, Включение управления вспышкой нажатием PTT, Отключение PTT плюс решетка.",
            "PTT плюс восемь звездочка, Вход в раздел помощи к описанию команд с прослушиванием предложений с выбранным шагом, PTT плюс решетка выход из режима.",
            "PTT плюс восемь решетка, Вход в основной раздел помощи с прослушиванием предложений с выбранным шагом, PTT плюс решетка выход из режима.",
            "PTT плюс девять звездочка, Воспроизведение голосовых заметок.",
            "PTT плюс девять решетка, Удаление голосовых заметок, по одной или все сразу.",
            "Команды после набора номера и одиночного нажатия звездочки.",
            "Один, звонок с первой сим карты.",
            "Два, звонок с второй сим карты.",
            "Три, назначение тестового номера для мониторинга сети.",
            "Четыре, отправить на набранный номер надиктованную СМС, (при наличии интернета).",
            "Пять, добавить набранный номер в книгу контактов, (при наличии интернета).",
            "PTT плюс два раза звездочка, сразу звонок по последнему набранному номеру с сим карты с котороц звонили последний раз.",
            "PTT плюс три раза звездочка, вход в режим назначения номеров быстрого набора, наберите номер а затем букву за которой он должен быть закреплен.",
            "Это были основные, двадцать девять команд, назначение служебных команд, доступно в основном разделе помощи."
        )
    }



    private var callStartTime: Long = 0 // Время начала исходящего вызова
    private var frequencyCount: Int = 0 // Счетчик частот в диапазоне
    private var flagFrequencyCount = false
    private val alarmScheduler: AlarmScheduler = AlarmScheduler(context = context) // Будильник

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

        // --- ДОБАВЛЕНО: ЗАГРУЗКА СОХРАНЕННЫХ НОМЕРОВ БЫСТРОГО НАБОРА ---
        numberA = loadQuickDialNumber(context, KEY_A)
        numberB = loadQuickDialNumber(context, KEY_B)
        numberC = loadQuickDialNumber(context, KEY_C)
        numberD = loadQuickDialNumber(context, KEY_D)
        // -----------------------------------------------------------------
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

    override fun getPeriodCtcssFlow(): Flow<Int> = flow {
        emitAll(_periodCtcss)
    }

    override fun getPeriodCtcss(): Int {
        return _periodCtcss.value
    }

    override fun setPeriodCtcss(periodCtcss: Int) {
        _periodCtcss.update { periodCtcss }
    }

    override fun getDurationCtcssFlow(): Flow<Int> = flow {
        emitAll(_durationCtcss)
    }

    override fun getDurationCtcss(): Int {
        return _durationCtcss.value
    }

    override fun setDurationCtcss(durationCtcss: Int) {
        _durationCtcss.update { durationCtcss }
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
            utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss(), getPeriodCtcss(),
                getDurationCtcss())
        }

        if (getFrequencyCtcss() != 0.0 && callState == 4) {
            // Поднимаем уровень субтона если абонент поднял трубку на корректирующее значение
            setVolumeLevelCtcss(getVolumeLevelCtcss() + amplitudeCtcssCorrect)
            utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss(), getPeriodCtcss(),
                getDurationCtcss())
        }

        if (getFrequencyCtcss() != 0.0 && callState == 7) {
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

    override fun setInput(value: String, withoutTimer: Boolean) {
        if (flagDtmf) {
            _input.update { "${durationVox}ms ${getVolumeLevelTts().toInt()}%" }
        } else {
                _input.update { value }
                if (getMagneticFieldFlag()) setTimer(30000) // продление таймера от любой команды
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

    override fun getMagneticFieldFlow(): Flow<Boolean> = flow {
        if (_magneticField.value == null) {
            try {
                getMagneticField()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_magneticField.filterNotNull())
    }

    override fun getMagneticField(): Boolean {
        return _magneticField.value ?: false
    }

    override fun setMagneticField(value: Boolean) {
        // Централизованное управление вспышкой через setFlashlight
        if (flagFlashLight) {
            setFlashlight(value)
        }
        _magneticField.update { value }
    }

    override fun getMagneticFieldFlagFlow(): Flow<Boolean> = flow {
        if (_magneticFieldFlag.value == null) {
            try {
                getMagneticFieldFlag()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_magneticFieldFlag.filterNotNull())
    }

    override fun getMagneticFieldFlag(): Boolean {
        return _magneticFieldFlag.value ?: false
    }

    override fun setMagneticFieldFlag(value: Boolean) {
        _magneticFieldFlag.update { value }
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
        // Оптимизация: включаем/выключаем вспышку только при изменении состояния
        if (currentTorchState != value) {
            try {
                val cameraId = cameraManager.cameraIdList[0]
                cameraManager.setTorchMode(cameraId, value)
                currentTorchState = value
            } catch (e: Exception) {
                // Обработка ошибок (например, если камера недоступна)
                e.printStackTrace()
            }
        }
        _flashlight.update { value }
    }

    override fun getStatusDtmfFlow(): Flow<Boolean> = flow {
        if (_statusDtmf.value == null) {
            try {
                getStatusDtmf()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_statusDtmf.filterNotNull())
    }

    override fun getStatusDtmf(): Boolean {
        return _statusDtmf.value ?: false
    }

    override fun setStatusDtmf(value: Boolean) {
        _statusDtmf.update { value }
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

    // Значение основного таймера
    override fun getTimerFlow(): Flow<Long> = flow {
        emitAll(_timer)
    }

    override fun getTimer(): Long {
        return _timer.value
    }

    override fun setTimer(duration: Long) {
        _timer.update { duration }
    }

    // Основная логика работы таймера он запускается от изменения магнитного поля

    override fun getStartDtmfFlow(): Flow<Boolean> = flow {
        emitAll(_isDTMFStarted)
    }

    override fun getStartDtmf(): Boolean {
        return _isDTMFStarted.value
    }

    override fun setStartDtmf(enabled: Boolean) {
      //  Log.d("Контрольный лог", "setStartDtmf $enabled")
        if (enabled) {
            startDtmf()
            flashlightJob.launch {
                var timer: Long
                do {
                    timer = getTimer()
                    if (timer != 0L) {
                        if (getCallState() == 7) {
                            setTimer(timer - 1000)
                        }
                        delay(1000)
                    }
                } while (timer > 0)
                  stopDtmf()
            }
        } else {
           stopDtmf()
        }
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
        if (getCallState() == 1 && block) {
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

    //Получение данных с блока распознавания Фурье
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
            if (isTorchOn) {
                setFlashlight(false)
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

        isCommandProcessed = false

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

            // Блок обработки шага листания книги контактов или предложений раздела помощи
            if (isContactMode || isHelpMode) {
                val delta: Int = when (key) {
                    '1' -> -1    // Листание на 1 контакт или предложение назад
                    '7' -> 1     // Листание на 1 контакт или предложение вперед
                    '2' -> -10   // Листание на 10 контактов или предложений назад
                    '8' -> 10    // Листание на 10 контактов или предложений вперед
                    '3' -> -100  // Листание на 100 контактов или предложений назад
                    '9' -> 100   // Листание на 100 контактов или предложений вперед
                    '4' -> 40    // Произнесение номера выбранного контакта
                    '5' -> 30    // Удаление контакта
                    else -> 0
                }
                navigateContactsHelp(delta)
            }

            // Обработка нажатий клавиш быстрого набора
            if ((key in 'A'..'D' && getCall() == null) && (!getFlagFrequencyLowHigt() && !flagDtmf)) {

                playSoundJob.launch {

                    delay(300) // Увеличенная задержка для стабилизации isProgrammingMode

                    // --- ВЕТКА 1: РЕЖИМ ПРОГРАММИРОВАНИЯ (Активирован через ***) ---
                    if (isProgrammingMode) {

                        // 1. Проверяем, что введен номер для сохранения
                        if (input.length in 3..11) {
                            val numberToSave = input

                            // 2. Проверяем, не закреплен ли этот номер уже за другой клавишей
                            val currentNumberA = numberA
                            val currentNumberB = numberB
                            val currentNumberC = numberC
                            val currentNumberD = numberD

                            if (
                                (key != 'A' && numberToSave == currentNumberA && currentNumberA.isNotEmpty()) ||
                                (key != 'B' && numberToSave == currentNumberB && currentNumberB.isNotEmpty()) ||
                                (key != 'C' && numberToSave == currentNumberC && currentNumberC.isNotEmpty()) ||
                                (key != 'D' && numberToSave == currentNumberD && currentNumberD.isNotEmpty())
                            ) {
                                flagDoobleClic = 0 // прекращаем ожидание выполнения кода тройного клика
                                speakText("Этот номер уже закреплен за другой клавишей")
                                setInput("")
                                isProgrammingMode = false
                                return@launch
                            }

                            // 3. Проверка, не закреплен ли номер уже за ЭТОЙ клавишей
                            val currentTargetNumber = when (key) {
                                'A' -> numberA
                                'B' -> numberB
                                'C' -> numberC
                                'D' -> numberD
                                else -> ""
                            }

                            if (numberToSave == currentTargetNumber) {
                                flagDoobleClic = 0 // прекращаем ожидание выполнения кода тройного клика
                                speakText("Этот номер ранее был закреплен за этой клавишей")
                                setInput("")
                                isProgrammingMode = false
                            } else {
                                // 4. Сохранение номера и озвучивание успеха

                                when (key) {
                                    'A' -> {
                                        numberA = numberToSave
                                        saveQuickDialNumber(context, KEY_A, numberToSave)
                                    }
                                    'B' -> {
                                        numberB = numberToSave
                                        saveQuickDialNumber(context, KEY_B, numberToSave)
                                    }
                                    'C' -> {
                                        numberC = numberToSave
                                        saveQuickDialNumber(context, KEY_C, numberToSave)
                                    }
                                    'D' -> {
                                        numberD = numberToSave
                                        saveQuickDialNumber(context, KEY_D, numberToSave)
                                    }
                                }

                                val num = utils.numberToText(numberToSave)
                                val callerName = utils.getContactNameByNumber(numberToSave, context)
                                flagDoobleClic = 0 // прекращаем ожидание выполнения кода тройного клика
                                val confirmationMessage = if (callerName.isNullOrEmpty()) {
                                    "Номер $num был закреплен за этой клавишей"
                                } else {
                                    "Номер абонента $callerName был закреплен за этой клавишей"
                                }

                                speakText(confirmationMessage)

                                // 5. Выход из режима программирования
                                setInput("")
                                isProgrammingMode = false
                            }

                        } else {
                            speakText("Для закрепления, заново введите номер от 3 до 11 цифр, а затем нужную букву")
                            setInput("")
                            isProgrammingMode = false // Выходим из режима при неудачном вводе
                        }

                    }

                    // --- ВЕТКА 2: ОБЫЧНЫЙ РЕЖИМ (ТОЛЬКО ВЫЗОВ) ---
                    else {

                        setInput("")

                        // 1. Получаем закрепленный номер (если есть)
                        val number = when (key) {
                            'A' -> numberA
                            'B' -> numberB
                            'C' -> numberC
                            'D' -> numberD
                            else -> ""
                        }

                        // 2. Проверяем, есть ли номер для вызова
                        if (number.length in 3..11) {
                            // Если номер закреплен, мы восстанавливаем его в input для звонка:
                            setInput(number)
                            val num = utils.numberToText(number)
                            val callerName = utils.getContactNameByNumber(number, context)

                            val message = if (callerName.isNullOrEmpty()) {
                                "Будет выполнен вызов абонента $num"
                            } else {
                                "Номер абонента $callerName подготовлен к вызову"
                            }

                            speakText(message)

                            // Установка времени задержки в зависимости от сообщения
                            val delayTime = if (callerName.isNullOrEmpty()) {
                                12000
                            } else {
                                8000
                            }

                            setSim(5)
                            delay(delayTime.toDuration(DurationUnit.MILLISECONDS))
                            DtmfService.callStart(context)
                            setInput1(getInput().toString())

                        } else {
                            // Если номер не закреплен
                            speakText("Клавиша свободна, вы можете закрепить за ней любой номер быстрого набора")
                        }
                    }
                }
            }

            else if (key == '*') {

                isProgrammingMode = false // Выход из режима програмирования номеров быстрого набора
                isContactMode = false // Выход из режима листания контактов

                // Функция, где обрабатываются одиночные/двойные клики:
                if (flagDoobleClic in 0..2) {

                    playSoundJob.launch {
                        flagDoobleClic++
                        delay(1500) // время отведенное на определение сколько было выполнено кликов

                        when {
                            // Тройной клик
                            input.isEmpty() && flagDoobleClic == 3 -> {
                                isProgrammingMode = true // Активируем режим
                                speakText("Вы зашли в режим назначения номеров быстрого набора. Наберите номер и букву за которой он будет закреплен")
                            }

                            // Двойной клик
                            input.isEmpty() && flagDoobleClic == 2 -> {
                                val secondInput = getInput2()
                                if (!secondInput.isNullOrBlank()) {
                                    setInput(secondInput.toString())
                                    if (getSim() == 5) setSim(0) // звонок с последней использованной SIM
                                    DtmfService.callStart(context)
                                } else {
                                    speakText("Вначале выполните звонок")
                                }
                            }

                            // Одинарный клик (это условие всегда должно быть последним в этой логике)
                            input.isEmpty() && flagDoobleClic == 1 -> {
                                speakText("Наберите номер")
                            }
                        }
                        // Сброс флага
                        flagDoobleClic = 0
                    }
                }

                // Прием входящего вызова по нажатию звездочки
                if (getCall() != null) {
                    if (getCallState() == 2) {
                        textToSpeech.stop() // Остановка ТТС чтобы не слышать вам звонит такой-то
                        DtmfService.callAnswer(context)
                        setInput("")
                    }
                }

                // Удаленная проверка входящий вызова по команде 0*
                else if (input == "0" && getCall() == null) {
                    utils.lastMissed(context, true)
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

                // Команда 4* (Прослушать SMS / Перейти к более новым)
                else if (input == "4" && getCall() == null) {
                    if (smsNavigationIndex <= 0) {
                        // Если индекс -1 (еще не начинали) или 0 (уже на самой новой)
                        smsNavigationIndex = 0
                        prefix = "Это самая новая смс. "
                    } else {
                        smsNavigationIndex-- // Перемещаемся к более новой
                        prefix = ""
                    }

                    // Получаем и озвучиваем
                    val (smsText, count) = utils.getIncomingSmsByIndex(context, smsNavigationIndex)
                    totalSmsCount = count

                    if (totalSmsCount == 0) {
                        speakText("Сообщения отсутствуют.")
                    } else {
                        speakText(prefix + smsText)
                    }
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

                else if (input == "6") {

                    if (!isContactMode) {
                        contacts = utils.loadContacts(context)
                        if (contacts.isEmpty()) {
                            speakText("В телефонной книге нет контактов.")
                            setInput("")
                        } else {
                            isContactMode = true
                            isHelpMode = false // Запрет на пролистывание помощи
                            currentContactIndex = -1
                            speakText("Вы зашли в книгу контактов. Для перемещения по списку контактов, по одному, используйте 7 и 1. по 10 контактов 8 и 2. по 100 контактов 9 и 3")
                            setInput("")
                        }
                    }
                }

                // Запись голосовой заметки
                else if (input == "7" && getCall() == null) {
                    isStopRecordingTriggered = false
                    utils.startRecording(isTorchOnIs, subscribers)
                    setInput("")
                }

                // Пролиставание и прослушивание помощи
                else if (input == "8" && getCall() == null) {
                    if (!isHelpMode) {
                        // Загружаем предложения из локального списка
                        sentences = getHelpSentences()
                        if (sentences.isEmpty()) {
                            speakText("Список помощи пуст.")
                            setInput("")
                        } else {
                            isHelpMode = true // Разрешение на пролистывание помоши
                            currentSentenceIndex = -1  // Начинаем с начала списка команд
                            speakText("Вы перешли к описанию команд репитера. Для перемещения по описанию, по одному предложению, используйте 7 и 1. по 10 предложений, 8 и 2. по 100 предложений, 9 и 3.")
                            setInput("")
                        }
                    }
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
                            periodVox = getInput()?.toLongOrNull() ?: 1600 // Оптимальное значение при увелечении до 2500 в режиме с одной рацией невозможно принять или прервать вызов
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

                // Переключение в режим экономии батареи
                else if (input == "100") {
                    if (getCall() == null && block) {
                        if (getStatusDtmf()) stopDtmf()
                        setMagneticFieldFlag(true)
                        speakText("Включена экономия заряда батареи, требуется кабель с магнитной катушкой, ДЭ ТЭ МЭ ЭФ распознавание будет запускаться от изменения магнитного поля")
                        setInput("")
                    } else {
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
                        setPeriodCtcss(1000) // Устанавливаем период субтона для удержания VOX
                        setDurationCtcss(200) // Устанавливаем длительность субтона достаточную для поднятия Vox
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

                // Назначение нового номера на который будет идти дозвон во время проверки наличия сети
                else if (key == '3' && getCall() == null) {
                    setInput(getInput1().toString())
                    val phoneNumberMonitor = getInput()
                    setInput("")
                    setInput1("")
                    if (phoneNumberMonitor?.length == 11) {
                        val numberMoni = utils.numberToText(phoneNumberMonitor)
                        monitorNumber = phoneNumberMonitor
                        speakText("Номер $numberMoni установлен для попыток дозвонится при тестировании есть ли сеть")
                        setInput("")
                        setInput1("")
                    } else {
                        speakText("В номере должно быть 11 цифр, укажите стандартный сотовый номер")
                        setInput("")
                        setInput1("")
                    }
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
                isHelpMode = false // Выход из режима листания помощи
                flagAmplitude = false // Выход из режима измерения амплитуды
                flagFrequency = false // Выход из режима измерения частоты
                flagSelective = false // Блокировка управления режимом селективного вызова
                flagSimMonitor = false // Выход из режима попыток дозвониться при поиске точки расположения репитера
                isContactMode = false // Выход из режима листания контактов
                setFlagFrequencyLowHigt(false) // Отключение отображения верхней и нижней частоты DTMF
                flagDtmf = false // Отключение генератора двухтональных команд
                textToSpeech.stop() // Остановка ТТС
                isSpeaking = false
                flagFlashLight = false // Сброс флага управления вспышкой по нажатию PTT
                isProgrammingMode = false // Выход из режима программирования номеров быстрого набора
                setFlashlight(false) // Отключаем вспышку если она включена

                if (getCall() == null) {
                    setInput("")

                    // Команда для установки будильника
                    if (input == "1") {
                        setInput("")
                        playSoundJob.launch {
                            speakText("Установка будильника, введите время срабатывания в 24-часовом формате")
                            delay(17000)
                            val timeInput = getInput()
                            if (timeInput != null && timeInput.length == 4 && timeInput.all { it.isDigit() }) {
                                val hours = timeInput.substring(0, 2).toIntOrNull()
                                val minutes = timeInput.substring(2, 4).toIntOrNull()

                                if (hours != null && minutes != null && hours in 0..23 && minutes in 0..59) {
                                    alarmScheduler.setAlarm(hours, minutes, 60000L, 5L)
                                    val formattedTime = utils.formatRussianTime(hours, minutes)
                                    speakText("Будильник сработает в $formattedTime. Для отключения будильника наберите два нуля решетку")
                                } else {
                                    speakText("Некорректное время. Часы должны быть от 0 до 23, минуты от 0 до 59")
                                }
                            } else {
                                speakText("Нужно ввести только 4 цифры")
                            }
                            setInput("")
                        }
                    }

                    // Удаленная проверка последнего пропущенный вызова по команде 0#
                    else if (input == "0" && getCall() == null) {
                        utils.lastMissed(context, false)
                    }

                    // Остановка будильника и маяка по команде 00#
                    else if (input == "00" && getCall() == null) {
                        isBeaconActiveManually = false
                        alarmScheduler.stopAlarm()
                        speakText("Будильник и маяк отключены")
                    }

                    // Маяк для определения зоны покрытия 2#
                    else if (input == "2" && getCall() == null) {
                        isBeaconActiveManually = true
                        playSoundJob.launch {
                            speakText("Вы запускаете маяк для определения зоны действия репитера. Введите время его работы в двадцати четырех часовом формате")
                            setInput("")
                            delay(20000)
                            val timeInput = getInput()
                            setInput("")

                            var totalSeconds = 600 // По умолчанию 10 минут

                            if (timeInput?.length == 4 && timeInput.all { it.isDigit() }) {
                                val h = timeInput.substring(0, 2).toIntOrNull() ?: 0
                                val m = timeInput.substring(2, 4).toIntOrNull() ?: 0
                                totalSeconds = (h * 3600) + (m * 60)
                                speakText("Маяк запущен на ${utils.formatRussianTime(h, m)}")
                            } else {
                                speakText("Маяк запущен на 10 минут по умолчанию")
                            }

                            if (totalSeconds > 0) {
                                val startTime = System.currentTimeMillis()
                                // Добавляем небольшую погрешность (500мс), чтобы пограничное время (ровно 2 мин) не обрывало цикл
                                val endTime = startTime + (totalSeconds * 1000L) + 500L

                                // ЦИКЛ МАЯКА
                                while (isBeaconActiveManually) {
                                    // Проверяем, есть ли смысл ждать следующие 30 секунд
                                    if (System.currentTimeMillis() + 30000L > endTime) break

                                    delay(30000)

                                    if (!isBeaconActiveManually) break

                                    // ЗАПУСКАЕМ ОДИН СИГНАЛ
                                    val intent = Intent(context, AlarmSoundService::class.java).apply {
                                        putExtra("ALARM_PERIOD", 30000L)
                                        putExtra("ALARM_PART", 1L)
                                    }
                                    context.startService(intent)
                                }

                                // ФИНАЛ
                                if (isBeaconActiveManually) {
                                    // После последнего сигнала (например, на 02:00)
                                    // даем звуку проиграть (например, 2-3 секунды) перед фразой об отключении
                                    delay(3000)

                                    alarmScheduler.stopAlarm()
                                    speakText("Время действия маяка истекло. Маяк отключен")
                                    isBeaconActiveManually = false
                                }
                            }
                            setInput("")
                        }
                    }

                    // Мониторинг сигнала SIM-карт по команде 3#
                    else if (input == "3" && getCall() == null) {
                        checkCallStateSequence()
                    }

                    // Команда 4# (Перейти к более старым)
                    else if (input == "4" && getCall() == null) {
                        if (smsNavigationIndex == -1) {
                            smsNavigationIndex = 0
                            prefix = ""
                        } else {
                            // Проверяем, не достигли ли мы конца
                            if (smsNavigationIndex >= totalSmsCount - 1 && totalSmsCount > 0) {
                                smsNavigationIndex = totalSmsCount - 1 // Фиксируем на последнем
                                prefix = "Это самая старая смс. "
                            } else {
                                smsNavigationIndex++ // Листаем дальше
                                prefix = ""
                            }
                        }

                        // Получаем и озвучиваем
                        val (smsText, count) = utils.getIncomingSmsByIndex(context, smsNavigationIndex)
                        totalSmsCount = count

                        if (totalSmsCount == 0) {
                            speakText("Сообщения отсутствуют.")
                        } else {
                            speakText(prefix + smsText)
                        }
                        setInput("")
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
                        speakText("Команда не назначена")
                        setInput("")
                    }

                    // Включение разрешения управления вспышкой по нажатию PTT по команде 7*
                    else if (input == "7" && getCall() == null) {
                        flagFlashLight = true
                        speakText("Управление вспышкой по нажатию PTT Включено, Отключение PTT + решетка, не работает")
                        setInput("")
                    }

                    // Пролиставание и прослушивание помощи
                    else if (input.equals("8") && getCall() == null) {
                        if (block) {
                            if (!isHelpMode) {
                                // Загружаем предложения из локального списка
                                sentences = getHelpSentences();
                                if (sentences.isEmpty()) {
                                    speakText("Список помощи пуст.");
                                    setInput("");
                                } else {
                                    isHelpMode = true; // Разрешение на пролистывание помоши
                                    currentSentenceIndex = 0;  // Начинаем с начала списка команд
                                    speakText("Вы зашли в полный раздел описания функциональности репитера. Для перемещения по описанию, по одному предложению, используйте 7 и 1. по 10 предложений, 8 и 2. по 100 предложений, 9 и 3.");
                                    setInput("");
                                }
                            }
                        } else {
                            speakText("Команда заблокирована");
                            setInput("");
                        }
                    }

                    // Удаление голосовой заметки 9#
                    else if (input == "9" && getCall() == null) {
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

                    // Отключение режима экономии батареи
                    else if (input == "100") {
                        if (getCall() == null && block) {
                            if (!getStatusDtmf()) startDtmf()
                            setMagneticFieldFlag(false)
                            speakText("Отключена экономия заряда батареи, ДЭ ТЭ МЭ ЭФ распознавание работает в непрерывном цикле")
                            setInput("")
                        } else {
                            speakText("Команда заблокирована")
                            setInput("")
                        }
                    }

                    // Откат двухканального режима (возврат к одноканальному)
                    else if (input == "123" && getCall() == null) {
                        if (getCall() == null && block) {
                            voxActivation = 500
                            setFrequencyCtcss(0.0)
                            setVolumeLevelCtcss(0.08)
                            setPeriodCtcss(0) // Отключаем прерывистую генерацию субтона
                            setDurationCtcss(0) // Отключаем прерывистую генерацию субтона
                            speakText("Выполнен возврат к одноканальному режиму, достаточно одной радиостанции")
                            setInput("")
                        } else {
                            speakText("Команда заблокирована")
                            setInput("")
                        }
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
                    if (!isContactMode && !isHelpMode) { // Блокировка печати в режимах пролистывания контактов и помощи
                        setInput(input + key)}
                } else {
                    if (!isTorchOn) {
                        setFlashlight(true)
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
        setStatusDtmf(true)
        DtmfService.start(context)
        initJob()
        setInput("")
        recorderJob.launch { record() }
        recognizerJob.launch { recognize() }
        if (getMagneticFieldFlag()) { setTimer(30000) }
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
        setStatusDtmf(false)
        setInput("")
        isSpeaking = false
        job.cancel()
        audioRecord?.stop()
        audioRecord = null
        blockingQueue.clear()
        recognizer.clear()
        DtmfService.stop(context)
        if (getMagneticFieldFlag()) { setTimer(0) }
        setInput("")
    }

    // Функция мониторинга возможности выполнить вызов для нахождения точки установки репитера
    private fun checkCallStateSequence() {
        scope.launch {
            speakText("Поиск точки расположения репитера. Попытки выполнить вызов будут выполняться непрерывно")
            delay(11000)

            flagSimMonitor = true
            var currentSim = 0

            while (flagSimMonitor) {
                setInput(monitorNumber) // Тестовый звонок будет выполняться на этот номер (можно указать любой)
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


    private fun navigateContactsHelp(delta: Int) {
        // Логика для пролистывания контактов
        if (delta != 0 && delta != 30 && delta != 40 && !isHelpMode) {
            val currentContactsSize = contacts.size
            val wasContactDeleted = (previousContactsSize > 0 && currentContactsSize == previousContactsSize - 1)
            currentContactIndex = (currentContactIndex + delta) % contacts.size

            if (currentContactIndex < 0) {
                currentContactIndex += contacts.size
            }

            val actualIndex = if (wasContactDeleted && delta > 0) {
                val correctedIndex = if (currentContactIndex > 0) currentContactIndex - 1 else 0
                correctedIndex
            } else {
                currentContactIndex
            }

            val contact = contacts[actualIndex]
            val contactName = contact.first
            val contactNumber = contact.second
            speakText(contactName)
            isCommandProcessed = true
            if (contactNumber.length > 11) {
                speakText("Выбранный номер не помещается в стандартное поле")
            } else {
                setInput(contactNumber)
            }

            
            // Обновляем предыдущий размер списка
            previousContactsSize = currentContactsSize

            // Логика для произнесения номера выбранного контакта
        } else if (delta == 40 && !isHelpMode) {
            if (getInput() != "") {
                speakText(utils.numberToText(getInput().toString()))
            } else {
                speakText("Нет выбранного контакта")
            }
            isCommandProcessed = true

            // Логика для удаления контактов
        } else if (delta == 30 && !isHelpMode) {
            val currentInput = getInput()

            if (currentInput.isNullOrEmpty()) {
                speakText("Для удаления контакта сначала выберите его из списка")
                isCommandProcessed = true
                return
            }

            if (currentContactIndex >= 0 && currentContactIndex < contacts.size) {
                val contactName = contacts[currentContactIndex].first
                val contactNumber = contacts[currentContactIndex].second

                val isDeleted = utils.deleteContactByName(contactName, context)

                if (isDeleted) {
                    speakText("Контакт $contactName успешно удален из телефонной книги")
                    previousContactsSize = contacts.size // Сохраняем размер до удаления
                    contacts = contacts.filterNot { it.first == contactName && it.second == contactNumber }

                    if (contacts.isNotEmpty()) {
                        if (currentContactIndex >= contacts.size) {
                            currentContactIndex = contacts.size - 1
                        }
                        setInput("")
                    } else {
                        speakText("Книга контактов пуста. Удалять больше нечего.")
                        isContactMode = false
                        setInput("")
                    }
                } else {
                    speakText("Не удалось удалить контакт")
                    setInput("")
                }
            } else {
                speakText("Невозможно удалить номер, которого уже нет.")
            }
            isCommandProcessed = true

            // Логика для пролистывания помощи/сообщений
        } else if (isHelpMode) {
            if (delta != 0) {
                currentSentenceIndex = (currentSentenceIndex + delta) % sentences.size
                if (currentSentenceIndex < 0) {
                    currentSentenceIndex += sentences.size
                }

                if (currentSentenceIndex >= 0 && currentSentenceIndex < sentences.size) {
                    val currentSentence = sentences[currentSentenceIndex]
                    speakText(currentSentence)
                    isCommandProcessed = true
                    setInput("")
                }
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
        if (getFrequencyCtcss() != 0.0) { utils.playCTCSS(getFrequencyCtcss(), getVolumeLevelCtcss(), getPeriodCtcss(),
            getDurationCtcss()) }

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



