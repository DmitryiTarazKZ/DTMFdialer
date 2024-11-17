package com.mcal.dtmf.data.repositories.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telephony.CellSignalStrength
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mcal.dtmf.R
import com.mcal.dtmf.data.repositories.preferences.PreferencesRepository
import com.mcal.dtmf.recognizer.DataBlock
import com.mcal.dtmf.recognizer.Recognizer
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.recognizer.StatelessRecognizer
import com.mcal.dtmf.service.DtmfService
import com.mcal.dtmf.utils.CallDirection
import com.mcal.dtmf.utils.Utils
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.roundToInt

class MainRepositoryImpl(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
) : MainRepository, SensorEventListener { // Добавьте SensorEventListener
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
    private val _outputFrequency: MutableStateFlow<Float?> = MutableStateFlow(0f) // Значение истинной частоты с блока преобразования Фурье
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
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var textToSpeech: TextToSpeech
    private var lastMissedCallNumber: String? = null
    private var lastMissedCallTime: String? = null
    private var isFlashlightOn = false // Флаг для устранения мерцания сигнальной вспышки
    private var isButtonPressed = false // Флаг подтверждающий факт выбора одной из сим карт
    private var flagSim = false
    private var sim = 0
    private var volumeLevel = 70
    private var flagVoise = false // Флаг обеспечивающий быстрое отключение вспышки после завершения речевых сообщений
    private var slotSim1: String? = null
    private var slotSim2: String? = null
    private var barometerSensor: Sensor? = null
    private var sensorManager: SensorManager? = null
    private var currentPressure: Float? = null

    init {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        barometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

        // Выводим окно чтобы пользователь разрешил использовать режим не беспокоить
        if (notificationManager.isNotificationPolicyAccessGranted()) {
            if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } else {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        // Установим громкость для всех необходимых стримов
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

        // Инициализируем TextToSpeech
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.getDefault()
            } else {
                playMediaPlayer(MediaPlayer.create(context, R.raw.no_ttc), true)
            }
        }

        // Проверка оптимизации батареи
        if (isIgnoringBatteryOptimizations(context)) {
            // Логика, если оптимизация отключена
        } else {
            // Перенаправляем пользователя в настройки для отключения оптимизации
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        // Проверка разрешения для отображения поверх всех окон
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
            currentPressure = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Не требуется для данного случая
    }

    private fun startBarometer() {
        barometerSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopBarometer() {
        sensorManager?.unregisterListener(this)
    }

    // сообщение по команде 0* о последнем пропущенном вызова и предложением перезвонить
    private fun getLastMissedCallInfo() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch {
                delayTon1000hz {
                    speakText("Разрешение на чтение лога вызовов не получено")
                }
            }
            return
        }

        val callLogUri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )
        val selection = "${CallLog.Calls.TYPE} = ?"
        val selectionArgs = arrayOf(CallLog.Calls.MISSED_TYPE.toString())
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        context.contentResolver.query(callLogUri, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val dateColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

                    val number = cursor.getString(numberColumn)
                    val date = cursor.getLong(dateColumn)

                    val contactName = getContactNameByNumber(number) ?: "имени которого нет в телефонной книге"
                    lastMissedCallNumber = contactName
                    lastMissedCallTime = formatDate(date)

                    scope.launch {
                        delayTon1000hz {
                            speakText(
                                "Последний пропущенный вызов был от абонента $lastMissedCallNumber... он звонил в $lastMissedCallTime..." +
                                        "Если Вы хотите перезвонить, нажмите звездочку. Для отмены нажмите решетку. Также Вы можете " +
                                        "закрепить этот номер за одной из клавиш быстрого набора"
                            )
                        }
                        setInput(number.replace("+7", "8"))
                    }
                }
            }
    }

    private fun formatDate(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    // Получение имени по номеру с книги контактов
    private fun getContactNameByNumber(number: String): String? {
        // Проверяем, что номер телефона не пустой
        if (number.isEmpty()) {
            return null
        }

        val cr = context.contentResolver
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var contactName: String? = null

        cr.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.count > 0 && cursor.moveToFirst()) {
                contactName =
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
            }
        }

        return contactName
    }

    // Тон для активации VOX радиостанции

    private fun delayTon1000hz(onComplete: () -> Unit) {
        val timeTon1000 = preferencesRepository.getDelayMusic()
        val mediaPlayer = MediaPlayer.create(context, R.raw.beep1000hz)
        playSoundJob.launch {
            delay(2000)
            if (isActive) {
                if (getConnType() == "Супертелефон") {
                    mediaPlayer.start()
                    delay(timeTon1000)
                    mediaPlayer.stop()
                    mediaPlayer.release()
                }
            }
            onComplete()
        }
    }

    // Дата и время по команде 2*

    private fun speakCurrentTime() {
        // Получаем текущее время
        val currentTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = currentTime

        // Извлекаем необходимые компоненты времени и даты
        val hours = calendar.get(Calendar.HOUR_OF_DAY)
        val minutes = calendar.get(Calendar.MINUTE)
        val dayOfWeek =
            calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())

        // Форматируем строку для часов
        val hoursString = when (hours) {
            1, 21 -> "$hours час"
            in 2..4, 22, 23, 24 -> "$hours часа"
            else -> "$hours часов"
        }

        // Форматируем строку для минут
        val minutesString = when (minutes) {
            1, 21, 31, 41, 51 -> "$minutes минута"
            in 2..4, in 22..24, in 32..34, in 42..44, in 52..54 -> "$minutes минуты"
            else -> "$minutes минут"
        }

        // Форматируем строку для дня месяца
        val dayOfMonthString = when (dayOfMonth) {
            1 -> "первое"
            2 -> "второе"
            3 -> "третье"
            4 -> "четвертое"
            5 -> "пятое"
            6 -> "шестое"
            7 -> "седьмое"
            8 -> "восьмое"
            9 -> "девятое"
            10 -> "десятое"
            11 -> "одиннадцатое"
            12 -> "двенадцатое"
            13 -> "тринадцатое"
            14 -> "четырнадцатое"
            15 -> "пятнадцатое"
            16 -> "шестнадцатое"
            17 -> "семнадцатое"
            18 -> "восемнадцатое"
            19 -> "девятнадцатое"
            20 -> "двадцатое"
            21 -> "двадцать первое"
            22 -> "двадцать второе"
            23 -> "двадцать третье"
            24 -> "двадцать четвертое"
            25 -> "двадцать пятое"
            26 -> "двадцать шестое"
            27 -> "двадцать седьмое"
            28 -> "двадцать восьмое"
            29 -> "двадцать девятое"
            30 -> "тридцатое"
            31 -> "тридцать первое"
            else -> "$dayOfMonth"
        }

        // Форматируем окончательную строку
        val timeString =
            "$hoursString $minutesString. Сегодня $dayOfWeek, $dayOfMonthString $month."

        // Говорим текущее время
        scope.launch {
            delayTon1000hz {
                speakText("Текущее время $timeString")
            }
        }
    }

    // Код включения громкоговорителя
    override fun enableSpeaker() {
        // Перед включением громкоговорителя отключить режим не беспокоить
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        if (!getIsConnect()) {
            setCallAudioRoute(CallAudioState.ROUTE_SPEAKER)
        }
    }

    // Код отключения громкоговорителя
    override fun disableSpeaker() {
        // Перед отключением громкоговорителя включить режим не беспокоить
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        setCallAudioRoute(CallAudioState.ROUTE_EARPIECE)
    }

    // Основная логика работы таймера вспышки и дтмф анализа

    override fun getStartFlashlightFlow(): Flow<Boolean> = flow {
        emitAll(_isDTMFStarted)
    }

    override fun getStartFlashlight(): Boolean {
        return _isDTMFStarted.value
    }

    override fun setStartFlashlight(enabled: Boolean, hasProblem: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Установка языка для TextToSpeech
                textToSpeech.language = Locale.getDefault()
            } else {
                playMediaPlayer(MediaPlayer.create(context, R.raw.no_ttc), true)
            }
        }

        // остановка ттс при поднятии трубки любым абонентом
        if (getCallDirection() == CallDirection.DIRECTION_ACTIVE) {
            textToSpeech.stop()
            textToSpeech.shutdown()
            setFlashlight(false)
        }

        if (enabled) {
            startDtmf()
            scope.launch {

                // При подключенных наушниках и старте системы включить режим "Не беспокоить"
                if (getIsConnect()) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                } else {
                    // При отключенных наушниках и старте системы отключить режим "Не беспокоить"
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    if (getConnType() == "Репитер (2 Канала)") {
                        // Устанавливаем громкость для мелодии входящего вызова в режиме репитер
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_RING,
                            (audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) * 0.7).toInt(),
                            0
                        )
                    } else {
                        // В режиме супертелефон и старте системы включить режим "Не беспокоить"
                        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    }
                }

                // мелодия старта либо звуковое сообщение если увеличеное сопротивление в кнопке
                if (getCallDirection() != CallDirection.DIRECTION_INCOMING) {
                    var mediaId: Int = R.raw.start_timer
                    if (hasProblem) { // Запуск при увеличеном сопротивлении кнопки гарнитуры
                        mediaId = R.raw.headset_malfunction
                    }

                    // Создаем экземпляр MediaPlayer
                    val mediaPlayer = MediaPlayer.create(context, mediaId)

                    // Задержка отклика
                    delay(2000)

                    // Проверяем, проигрывается ли аудио
                    if (!mediaPlayer.isPlaying) {
                        if (getConnType() == "Репитер (2 Канала)") {
                            mediaPlayer.start() // Запускаем воспроизведение
                        }
                    }

                    // Добавляем слушателя для завершения проигрывания
                    mediaPlayer.setOnCompletionListener {
                        mediaPlayer.release() // Освобождаем ресурсы
                    }

                }

                if (getCallDirection() == CallDirection.DIRECTION_INCOMING) {
                    speakSuperTelephone()
                }
            }

            flashlightJob.launch {
                // Контрольные точки воспроизведения мелодии при исходящем вызове но пока трубку еще не подняли
                val timerCheckpoints = (0..90 step 5).map { it * 1000L }.reversed()
                var timer: Long
                do {
                    timer = getTimer()
                    // Бесконечный цикл дтмф анализа в режиме Супертелефон и Репитер (1 Канальный) и в режиме Репитер (2 канальный) при включеном переключателе (аппаратный DTMF)
                    val updateTimer = getConnType()
                    val updateTimer1 = preferencesRepository.getDtmModule()
                    if ((updateTimer == "Супертелефон" || updateTimer == "Репитер (1 Канал)" || updateTimer1) && timer < 5) {
                        timer = 30000
                        setInput("")
                    }

                    if (timer != 0L) {
                        if (getCallDirection() == CallDirection.DIRECTION_UNKNOWN) {
                            setTimer(timer - 1000)
                        }
                        // Сигнальный звук при исходящем вызове в режимах Репитер (2 канала) и Супертелефон
                        if (getCallDirection() == CallDirection.DIRECTION_OUTGOING) {
                            setTimer(timer - 1000)
                            playSoundJob.launch {
                                if (timerCheckpoints.contains(timer) && getConnType() == "Репитер (2 Канала)") {
                                   // Контрольное тиканье для контроля что вызов начался (Конфликтует с VOX)
                                    playMediaPlayer(
                                        MediaPlayer.create(
                                            context,
                                            R.raw.quiet_ticking
                                        ), true
                                    )
                                    // Если попытка дозвонится выполняется дольше обычного (прошло более 80 секунд) принудительно прекратить попытку
                                    if (timer == 10000L) {
                                        // Сообщение "Не удалось дозвонится. Не удачное расположение репитера, выберите другое место"
                                        playMediaPlayer(
                                            MediaPlayer.create(
                                                context,
                                                R.raw.cannot_contact
                                            ), true
                                        )
                                        DtmfService.callEnd(context)
                                    }
                                }
                            }
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

    // Значение сервисного номера полученного из настроек (стринг)

    override fun getServiceNumberFlow(): Flow<String> = preferencesRepository.getServiceNumberFlow()

    override fun getServiceNumber(): String = preferencesRepository.getServiceNumber()

    override fun setServiceNumber(number: String) = preferencesRepository.setServiceNumber(number)

    override fun getPlayMusicFlow(): Flow<Boolean> = preferencesRepository.getPlayMusicFlow()

    override fun getPlayMusic(): Boolean = preferencesRepository.getPlayMusic()

    override fun setPlayMusic(enabled: Boolean) = preferencesRepository.setPlayMusic(enabled)

    // Значение выключатель сигнальной вспышки (булеан)

    override fun getFlashSignalFlow(): Flow<Boolean> = preferencesRepository.getFlashSignalFlow()

    override fun getFlashSignal(): Boolean = preferencesRepository.getFlashSignal()

    override fun setFlashSignal(enabled: Boolean) = preferencesRepository.setFlashSignal(enabled)

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

    // Значение режимов работы полученное из настроек (стринг) Репитер 2 канальный, Репитер 1 канальный, Супертелефон.

    override fun getConnTypeFlow(): Flow<String> = preferencesRepository.getConnTypeFlow()

    override fun getConnType(): String = preferencesRepository.getConnType()

    override fun setConnType(value: String) = preferencesRepository.setConnType(value)

    // получение переменной истинной частоты с блока распознавания
    override fun getOutput1Flow(): Flow<Float> = flow {
        if (_outputFrequency.value == null) {
            try {
                getOutput1()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        emitAll(_outputFrequency.filterNotNull())
    }

    override fun getOutput1(): Float {
        return _outputFrequency.value ?: 0f
    }

    // включение/отключение вспышки по любой определенной частоте в режиме супертефона (в данном случае 1750гц)
    override fun setOutput1(outputFrequency: Float) {
        _outputFrequency.update { outputFrequency }
        scope.launch {
            if ((outputFrequency == 1750f && getFlashSignal()) && getConnType() == "Супертелефон") {
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
        if ((getConnType() == "Репитер (1 Канал)" || preferencesRepository.getDtmModule()) && duration == 0L) {
            setFlashlight(false)
        }
        // Отключение громкоговорителя по истечении таймера
        if (getCallDirection() == CallDirection.DIRECTION_UNKNOWN && duration == 0L) {
            disableSpeaker()
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
        textToSpeech.stop()
        textToSpeech.shutdown()
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
        if (getConnType() == "Репитер (1 Канал)" && callDirection == 2) {
            setFlashlight(false)
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
        if (getStartFlashlight()) {
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
        val cameraId = cameraManager.cameraIdList[0]
        runCatching {
            cameraManager.setTorchMode(cameraId, value)
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
        Utils.registerHeadphoneReceiver(context) { isConnected ->
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
        val soundSourceString = preferencesRepository.getSoundSource()
        val soundTestString = preferencesRepository.getSoundTest()

       // Проверяем тип соединения
        val soundSource = if (getConnType() == "Репитер (2 канала)") {
            MediaRecorder.AudioSource.MIC // В режиме репитер двухканальный источник звука всегда микрофон
        } else {
            when (soundSourceString) { // В режиме репитер одноканальный источник звука определяется в настройках
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
        }
        runCatching { // Обработка исключения для предотвращения падения
            audioRecord = AudioRecord(
                soundSource,
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
                        while (isActive) {
                            val bufferReadSize = audio.read(buffer, 0, blockSize)
                            val dataBlock = DataBlock(buffer, blockSize, bufferReadSize)
                            blockingQueue.put(dataBlock)

                            val maxAmplitude = buffer.take(bufferReadSize).maxOrNull()?.toFloat() ?: 0f
                            val holdTime = preferencesRepository.getDelayMusic1()
                            val threshold = preferencesRepository.getDelayMusic2()
                            val soundSourceDemo = preferencesRepository.getSoundSource()
                            val isSoundDetected = maxAmplitude > threshold

                            if ((isSoundDetected || getMicKeyClick() == 24) &&
                                (getCallDirection() == CallDirection.DIRECTION_ACTIVE || soundSourceDemo == "Отладка порога и удержания")) {

                                if (!flashlightOn && getConnType() == "Репитер (1 Канал)") {
                                    flashlightOn = true
                                    setTimer(30000)
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
                    }
                }
                audio.stop()
            }
        }.onFailure {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Источник звука не доступен", Toast.LENGTH_SHORT)
                    .show()
            }
        }
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
                // получение значения key из блока распознавания для дальнейшей обработки
                clickKey(getInput() ?: "", key)
                setKey(key)
            }
        }
    }

    // Включение экрана из выключеного положения на 10 секунд и затем возврат к предыдущему состоянию
    // при включенном режиме не беспокоить экран не включится а звонок выполнится
    // до этого без режима не беспокоить не совершался исходящий на заблокированном экране
    override fun wakeUpScreen(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // отключаем режим не беспокоить чтобы пробудить экран
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        setTimer(90000)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MyApp:WakeLock"
        )
        wakeLock.acquire(10000) // Держим экран включенным в течение 10 секунд
        wakeLock.release() // Освобождаем WakeLock
        // включаем режим не беспокоить для дальнейшей работы
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

    }

    //Основной блок обработки нажатий клавиш

    override fun clickKey(input: String, key: Char?) {

        // Регулировка громкости в режиме супертелефон
        if (input == "A" && getConnType() == "Супертелефон") {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            scope.launch {
                if (volumeLevel <= 90) {
                    volumeLevel += 10
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * volumeLevel / 100),
                        0
                    )
                    delayTon1000hz { speakText("Громкость добавлена на одну позицию и теперь составляет $volumeLevel процентов") }
                } else {
                    delayTon1000hz {
                        speakText("Достигнут максимальный уровень громкости")
                    }
                }
                setInput("")
            }
        }
        // Регулировка громкости в режиме супертелефон
        if (input == "B" && getConnType() == "Супертелефон") {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            scope.launch {
                if (volumeLevel >= 50) {
                    volumeLevel -= 10
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * volumeLevel / 100),
                        0
                    )
                    delayTon1000hz { speakText("Громкость убавлена на одну позицию и теперь составляет $volumeLevel процентов") }
                } else {
                    delayTon1000hz {
                        speakText("Достигнут минимальный уровень громкости")
                    }
                }
                setInput("")
            }
        }


        val previousKey = _key.value
        if (input.length > 11 && getCallDirection() != CallDirection.DIRECTION_INCOMING) {
            playMediaPlayer((MediaPlayer.create(context, R.raw.overflow)), true)
            setInput("")
        }

        if (key != ' ' && key != previousKey) {
            if (preferencesRepository.getDtmModule() && getConnType() == "Репитер (2 Канала)") {
                setFlashlight(true)
                setTimer(30000)
            }

            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Установка языка для TextToSpeech
                    textToSpeech.language = Locale.getDefault()
                } else {
                    playMediaPlayer(MediaPlayer.create(context, R.raw.no_ttc), true)
                }
            }

            if (key == 'A' && getConnType() != "Супертелефон") {
                if (input.length in 3..11) {
                    val currentNumberA = getNumberA()
                    if (currentNumberA != input) {
                        when (input) {
                            getNumberB() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(context, R.raw.number_already_assigned_b), true
                                )
                                setInput("")
                            }

                            getNumberC() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(context, R.raw.number_already_assigned_c), true
                                )
                                setInput("")
                            }

                            getNumberD() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(context, R.raw.number_already_assigned_d), true
                                )
                                setInput("")
                            }

                            else -> {
                                scope.launch {
                                    val callerName = getContactNameByNumber(input)
                                    if (callerName.isNullOrEmpty()) {
                                        delayTon1000hz { speakText("Неизвестный номер был закреплен за этой клавишей") }
                                    } else {
                                        delayTon1000hz { speakText("Номер абонента $callerName. был закреплен за этой клавишей") }
                                    }
                                }
                                setNumberA(input)
                                setInput("")
                            }
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.duplicate_number), true)
                    }
                } else if (getStartFlashlight()) {
                    val numberA = getNumberA()
                    if (numberA.length in 3..11) {
                        scope.launch {
                            setInput(numberA)
                            val callerName = getContactNameByNumber(numberA)
                            if (callerName.isNullOrEmpty()) {
                                delayTon1000hz { speakText("Будет выполнен вызов абонента с неизвестным номером")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            } else {
                                delayTon1000hz { speakText("Номер абонента $callerName набран")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            }
                            delay(6000)
                            DtmfService.callStart(context)
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.no_number_assigned), true)
                    }
                }
            } else if (key == 'B' && getConnType() != "Супертелефон") {
                if (input.length in 3..11) {
                    val currentNumberB = getNumberB()
                    if (currentNumberB != input) {
                        when (input) {
                            getNumberA() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_a
                                    ), true
                                )
                                setInput("")
                            }

                            getNumberC() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_c
                                    ), true
                                )
                                setInput("")
                            }

                            getNumberD() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_d
                                    ), true
                                )
                                setInput("")
                            }

                            else -> {
                                scope.launch {
                                    val callerName = getContactNameByNumber(input)
                                    if (callerName.isNullOrEmpty()) {
                                        delayTon1000hz { speakText("Неизвестный номер был закреплен за этой клавишей") }
                                    } else {
                                        delayTon1000hz { speakText("Номер абонента $callerName. был закреплен за этой клавишей") }
                                    }
                                }
                                setNumberB(input)
                                setInput("")
                            }
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.duplicate_number), true)
                    }
                } else if (getStartFlashlight()) {
                    val numberB = getNumberB()
                    if (numberB.length in 3..11) {
                        scope.launch {
                            setInput(numberB)
                            val callerName = getContactNameByNumber(numberB)
                            if (callerName.isNullOrEmpty()) {
                                delayTon1000hz { speakText("Будет выполнен вызов абонента с неизвестным номером")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            } else {
                                delayTon1000hz { speakText("Номер абонента $callerName набран")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            }
                            delay(6000)
                            DtmfService.callStart(context)
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.no_number_assigned), true)
                    }
                }
            } else if (key == 'C') {
                if (input.length in 3..11) {
                    val currentNumberC = getNumberC()
                    if (currentNumberC != input) {
                        when (input) {
                            getNumberA() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_a
                                    ), true
                                )
                                setInput("")
                            }

                            getNumberB() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_b
                                    ), true
                                )
                                setInput("")
                            }

                            getNumberD() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_d
                                    ), true
                                )
                                setInput("")
                            }

                            else -> {
                                scope.launch {
                                    val callerName = getContactNameByNumber(input)
                                    if (callerName.isNullOrEmpty()) {
                                        delayTon1000hz { speakText("Неизвестный номер был закреплен за этой клавишей") }
                                    } else {
                                        delayTon1000hz { speakText("Номер абонента $callerName. был закреплен за этой клавишей") }
                                    }
                                }
                                setNumberC(input)
                                setInput("")
                            }
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.duplicate_number), true)
                    }
                } else if (getStartFlashlight()) {
                    val numberC = getNumberC()
                    if (numberC.length in 3..11) {
                        scope.launch {
                            setInput(numberC)
                            val callerName = getContactNameByNumber(numberC)
                            if (callerName.isNullOrEmpty()) {
                                delayTon1000hz { speakText("Будет выполнен вызов абонента с неизвестным номером")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            } else {
                                delayTon1000hz { speakText("Номер абонента $callerName набран")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            }
                            delay(6000)
                            DtmfService.callStart(context)
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.no_number_assigned), true)
                    }
                }
            } else if (key == 'D') {
                if (input.length in 3..11) {
                    val currentNumberD = getNumberD()
                    if (currentNumberD != input) {
                        when (input) {
                            getNumberA() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_a
                                    ), true
                                )
                                setInput("")
                            }

                            getNumberB() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_b
                                    ), true
                                )
                                setInput("")
                            }

                            getNumberC() -> {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.number_already_assigned_c
                                    ), true
                                )
                                setInput("")
                            }

                            else -> {
                                scope.launch {
                                    val callerName = getContactNameByNumber(input)
                                    if (callerName.isNullOrEmpty()) {
                                        delayTon1000hz { speakText("Неизвестный номер был закреплен за этой клавишей") }
                                    } else {
                                        delayTon1000hz { speakText("Номер абонента $callerName. был закреплен за этой клавишей") }
                                    }
                                }
                                setNumberD(input)
                                setInput("")
                            }
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.duplicate_number), true)
                    }
                } else if (getStartFlashlight()) {
                    val numberD = getNumberD()
                    if (numberD.length in 3..11) {
                        scope.launch {
                            setInput(numberD)
                            val callerName = getContactNameByNumber(numberD)
                            if (callerName.isNullOrEmpty()) {
                                delayTon1000hz { speakText("Будет выполнен вызов абонента с неизвестным номером")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            } else {
                                delayTon1000hz { speakText("Номер абонента $callerName набран")
                                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                                    else flagVoise = true
                                }
                            }
                            delay(6000)
                            DtmfService.callStart(context)
                        }
                    } else {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.no_number_assigned), true)
                    }
                }
            } else if (key == '*') {

                if (getCallDirection() == CallDirection.DIRECTION_INCOMING) {
                    textToSpeech.stop()
                    textToSpeech.shutdown()
                }


                if (input == "") {
                    playMediaPlayer((MediaPlayer.create(context, R.raw.dial_the_number)), true)
                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                    else flagVoise = true
                }

                if (getCall() != null) {
                    if (getCallDirection() == CallDirection.DIRECTION_INCOMING) {
                        DtmfService.callAnswer(context)
                        setInput("")
                    }
                }

                // Удаленная проверка пропущенного вызова
                else if (input == "0") {
                    getLastMissedCallInfo()
                    setInput("")
                }

                // Удаленный контроль температуры аккамулятора по команде 1*
                else if (input == "1") {
                    scope.launch {
                        val batteryTemperature = Utils.getCurrentBatteryTemperature(context)
                        val temperatureValue = batteryTemperature.toDouble().roundToInt()
                        val batteryLevel = Utils.getCurrentBatteryLevel(context)
                        val levelValue = batteryLevel.toDouble().roundToInt()

                        val temperatureText = when (temperatureValue) {
                            1, 21, 31, 41, 51, 61, 71, 81, 91 -> "$temperatureValue градус"
                            in 2..4, in 22..24, in 32..34, in 42..44, in 52..54, in 62..64, in 72..74, in 82..84, in 92..94 -> "$temperatureValue градуса"
                            else -> "$temperatureValue градусов"
                        }

                        val levelText = when (levelValue) {
                            1, 21, 31, 41, 51, 61, 71, 81, 91 -> "$levelValue процент"
                            in 2..4, in 22..24, in 32..34, in 42..44, in 52..54, in 62..64, in 72..74, in 82..84, in 92..94 -> "$levelValue процента"
                            else -> "$levelValue процентов"
                        }
                        delayTon1000hz {
                            speakText("Температура аккумулятора $temperatureText. Заряд аккумулятора $levelText")
                        }
                    }
                    setInput("")
                    flagVoise = false
                }

                // Удаленное сообщение о текущем времени
                else if (input == "2") {
                    speakCurrentTime()
                    setInput("")
                    flagVoise = false
                }

                // Удаленный контроль уровня сети по команде 3*
                else if (input == "3") {
                    scope.launch {
                        Utils.getCurentCellLevel(context) { signalStrength, simOperatorName ->
                            textToSpeech = TextToSpeech(context) { status ->
                                if (status == TextToSpeech.SUCCESS) {
                                    // Установка языка для TextToSpeech
                                    textToSpeech.language = Locale.getDefault()
                                } else {
                                    playMediaPlayer(MediaPlayer.create(context, R.raw.no_ttc), true)
                                }
                            }

                            setInput("")

                            val speechResource = when (signalStrength) {
                                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN -> "Полностью отсутствует. Вызов с этой сим карты невозможен"
                                CellSignalStrength.SIGNAL_STRENGTH_POOR -> "Низкий"
                                CellSignalStrength.SIGNAL_STRENGTH_MODERATE -> "Умеренный"
                                CellSignalStrength.SIGNAL_STRENGTH_GOOD -> "Хороший"
                                CellSignalStrength.SIGNAL_STRENGTH_GREAT -> "Отличный"
                                else -> "Полностью отсутствует. Вызов с этой сим карты невозможен"
                            }

                            val operatorName = when (simOperatorName) {
                                "ACTIV" -> "Актив"
                                "ALTEL" -> "Алтэл"
                                "MTS" -> "МТС"
                                "Beeline KZ" -> "Билайн"
                                "Megafon" -> "Мегафон"
                                "Tele2" -> "Теле2"
                                else -> simOperatorName
                            }

                            delayTon1000hz {
                                speakText("Уровень сети оператора $operatorName, по индикатору антэны $speechResource")
                            }
                        }
                    }
                } else if (input == "4") {
                    scope.launch {
                        playMediaPlayer(MediaPlayer.create(context, R.raw.restart_radio), true)
                        setInput("")
                        flagVoise = false
                    }
                } else if (input == "5") {
                    scope.launch {
                        delayTon1000hz {
                            speakText(
                                "Введите время будильника в двадцати четырех часовом формате."
                            )
                        }
                        setInput("")
                        delay(15000)

                        val alarmInput = getInput()
                        if (!alarmInput.isNullOrEmpty() && alarmInput.length == 4) {
                            val hours =
                                alarmInput.substring(0, 2).toInt() // Первые две цифры - часы
                            val minutes =
                                alarmInput.substring(2, 4).toInt() // Последние две цифры - минуты

                            val hourText = when {
                                hours % 10 == 1 && hours % 100 != 11 -> "$hours час"
                                hours % 10 in 2..4 && hours % 100 !in 12..14 -> "$hours часа"
                                else -> "$hours часов"
                            }

                            val minuteText = when {
                                minutes % 10 == 1 && minutes % 100 != 11 -> "$minutes минута"
                                minutes % 10 in 2..4 && minutes % 100 !in 12..14 -> "$minutes минуты"
                                else -> "$minutes минут"
                            }

                            alarmClock(alarmInput.toInt())

                            delayTon1000hz {
                                speakText("Будильник установлен на $hourText, $minuteText.")
                                setInput("")
                            }
                        } else {
                            delayTon1000hz {
                                speakText("Неправильно введено время. Будильник не установлен")
                                setInput("")
                            }
                        }
                    }
                } else if (input == "6") {
                    scope.launch {
                        if (barometerSensor == null) {
                            delayTon1000hz { speakText("В данном смартфоне нет барометрического датчика") }
                        } else {
                            startBarometer()
                            delay(2000) // Даем время для получения данных
                            stopBarometer()
                            val pressure = currentPressure?.let { "$it гектопаскалей" }
                                ?: "не удалось получить данные"
                            delayTon1000hz { speakText("Текущее атмосферное давление составляет $pressure") }
                        }
                        setInput("")
                        flagVoise = false
                    }
                } else if (input == "7") {
                    scope.launch {
                        if (isOnline(context)) {
                            delayTon1000hz {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.voise_poisk
                                    ), true
                                )
                            }
                            setInput("")
                            flagVoise = false
                            delay(7000)
                            // Переключаемся на основной поток
                            withContext(Dispatchers.Main) {
                                startSpeechRecognition()
                            }
                        } else {
                            delayTon1000hz {
                                playMediaPlayer(
                                    MediaPlayer.create(
                                        context,
                                        R.raw.no_internet
                                    ), true
                                )
                            }
                            setInput("")
                            flagVoise = false
                        }
                    }
                }

                // очистить номера быстрого набора
                else if (input == "8") {
                    scope.launch {
                        setNumberA("")
                        setNumberB("")
                        setNumberC("")
                        setNumberD("")
                        setInput("")
                        playMediaPlayer(MediaPlayer.create(context, R.raw.clear_number), true)
                        setInput("")
                        flagVoise = false
                    }
                }

                //удаленное Включение/Отключение озвучивания по команде 9*
                else if (input == "9") {
                    var audioFileId = R.raw.voise_on
                    val playMusic = getPlayMusic()
                    if (playMusic) {
                        audioFileId = R.raw.voise_off
                    }
                    playMediaPlayer(MediaPlayer.create(context, audioFileId), playMusic = true)
                    setPlayMusic(!playMusic)
                    setInput("")
                    flagVoise = false
                }

                if (input.length in 3..11) {
                    setInput(input)
                    DtmfService.callStart(context)
                }

            } else if ((input.length in 3..11 && (key == '1' || key == '2')) && flagSim) {
                setInput(input)
                when (key) {
                    // Обработка случаев когда в телефоне активны обе сим карты
                    '1' -> {
                        scope.launch {
                            val operatorName1 = when (slotSim1) {
                                "ACTIV" -> "Актив"
                                "ALTEL" -> "Алтэл"
                                "MTS" -> "МТС"
                                "Beeline KZ" -> "Билайн"
                                "Megafon" -> "Мегафон"
                                "Tele2" -> "Теле2"
                                else -> slotSim1
                            }
                            isButtonPressed = true
                            sim = 0
                            delayTon1000hz {
                                speakText("Звоню с $operatorName1")
                                flagVoise = true
                            }
                            delay(5000)
                            if (getConnType() == "Репитер (1 Канал)") {
                                setFlashlight(true)
                            }
                            DtmfService.callStartSim(context, isButtonPressed, sim)
                            flagSim = false
                        }
                    }

                    '2' -> {
                        scope.launch {
                            val operatorName2 = when (slotSim2) {
                                "ACTIV" -> "Актив"
                                "ALTEL" -> "Алтэл"
                                "MTS" -> "МТС"
                                "Beeline KZ" -> "Билайн"
                                "Megafon" -> "Мегафон"
                                "Tele2" -> "Теле2"
                                else -> slotSim2
                            }
                            isButtonPressed = true
                            sim = 1
                            delayTon1000hz {
                                speakText("Звоню с $operatorName2")
                                flagVoise = true
                            }
                            delay(5000)
                            if (getConnType() == "Репитер (1 Канал)") {
                                setFlashlight(true)
                            }
                            DtmfService.callStartSim(context, isButtonPressed, sim)
                            flagSim = false
                        }
                    }
                }
            }

            // Остановка вызова если он есть а если нету то очистка поля ввода
            else if (key == '#') {
                textToSpeech.stop()
                textToSpeech.shutdown()
                flagSim = false
                if (input != "") {
                    if (getCall() == null) { // Проверяем, нет ли активного вызова
                        if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                        else flagVoise = true
                        playMediaPlayer(MediaPlayer.create(context, R.raw.clear_input), true)
                    }
                    setInput("")
                } else {
                    if (preferencesRepository.getDtmModule() && getConnType() == "Репитер (2 Канала)") {
                        setFlashlight(false)
                    }
                }

                if (getCall() != null) {
                    DtmfService.callEnd(context)
                }
            } else {
                setInput(input + key)
                when (key) {
                    '0' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_0))
                    '1' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_1))
                    '2' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_2))
                    '3' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_3))
                    '4' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_4))
                    '5' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_5))
                    '6' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_6))
                    '7' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_7))
                    '8' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_8))
                    '9' -> playMediaPlayer(MediaPlayer.create(context, R.raw.key_9))
                }
            }
        }
    }


    // Запуск дтмф анализа
    override fun startDtmf() {
        val conType = getConnType()
        if (conType == "Репитер (2 Канала)") {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.7).toInt(),
                0
            )
            if (!preferencesRepository.getDtmModule() && getConnType() == "Репитер (2 Канала)") {
                setFlashlight(true)
            }
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
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        setInput("")
        flagSim = false
        _isDTMFStarted.update { false }
        job.cancel()
        setFlashlight(false)
        audioRecord?.stop()
        audioRecord = null
        mediaPlayer?.reset()
        mediaPlayer = null
        blockingQueue.clear()
        recognizer.clear()
        DtmfService.stop(context)
        setTimer(0)
    }

    // Диалоги выбора и озвучивания сим карт

    override fun selectSimCard(slot1: String?, slot2: String?, phoneAccount: Int) {
        scope.launch {
            if (getCallDirection() != CallDirection.DIRECTION_INCOMING && phoneAccount > 1) {
                if (!flagSim) {
                    playMediaPlayer(MediaPlayer.create(context, R.raw.select_sim), true)
                    if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                    else flagVoise = true
                }
                flagSim = true
            } else {
                // Обработка случаев когда в телефоне активна только одна сим карта
                if (slot1 == "Нет сигнала" && getCallDirection() == CallDirection.DIRECTION_UNKNOWN) {
                    val operatorName1 = when (slot2) {
                        "ACTIV" -> "Актив"
                        "ALTEL" -> "Алтэл"
                        "MTS" -> "МТС"
                        "Beeline KZ" -> "Билайн"
                        "Megafon" -> "Мегафон"
                        "Tele2" -> "Теле2"
                        else -> slot2
                    }
                    isButtonPressed = true
                    delayTon1000hz {
                        speakText("Звоню с $operatorName1")
                        flagVoise = true
                    }
                    delay(5000)
                    if (getConnType() == "Репитер (1 Канал)") {
                        setFlashlight(true)
                    }
                    DtmfService.callStartSim(context, isButtonPressed, 0)
                    isButtonPressed = false
                    flagSim = false
                }
                if (slot2 == "Нет сигнала" && getCallDirection() == CallDirection.DIRECTION_UNKNOWN) {
                    val operatorName2 = when (slot1) {
                        "ACTIV" -> "Актив"
                        "ALTEL" -> "Алтэл"
                        "MTS" -> "МТС"
                        "Beeline KZ" -> "Билайн"
                        "Megafon" -> "Мегафон"
                        "Tele2" -> "Теле2"
                        else -> slot1
                    }
                    isButtonPressed = true
                    delayTon1000hz {
                        speakText("Звоню с $operatorName2")
                        flagVoise = true
                    }
                    delay(5000)
                    if (getConnType() == "Репитер (1 Канал)") {
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

    // Сотовая сеть не доступна
    override fun networNone() {
        playMediaPlayer(MediaPlayer.create(context, R.raw.network_unavailable), true)
        setInput("")
    }

    override fun noSim() {
        playMediaPlayer(MediaPlayer.create(context, R.raw.no_sim), true)
        setInput("")
    }

    // Основная функция озвучивания входящих вызовов

    override suspend fun speakSuperTelephone() {

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Установка языка для TextToSpeech
                textToSpeech.language = Locale.getDefault()
            } else {
                playMediaPlayer(MediaPlayer.create(context, R.raw.no_ttc), true)
            }
        }

        delay(500) // без этой задержки не получалось корректное значение callerNumber

        val callerNumber = (_input.value).toString()
        if (callerNumber.isEmpty()) {
            delayTon1000hz {
                speakText("Ошибка извлечения имени из телефонной книги")
                if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                else flagVoise = true
            }
        } else {
            val callerName = getContactNameByNumber(callerNumber)
            while (getCallDirection() == CallDirection.DIRECTION_INCOMING) {

                if (callerName.isNullOrEmpty()) {
                    delayTon1000hz {
                        speakText("Внимание! Вам звонит абонент, имени которого нет в телефонной книге. Примите или отклоните вызов")
                        if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                        else flagVoise = true
                    }
                } else {
                    delayTon1000hz {
                        speakText("Внимание! Вам звонит абонент $callerName. Примите или отклоните вызов")
                        if (getConnType() == "Репитер (1 Канал)") flagVoise = false
                        else flagVoise = true
                    }
                }

                if (getConnType() == "Супертелефон") {

                    delay(17000)

                } else delay(13000)
            }
        }
    }

    // Функция проверки доступен ли интернет

    private fun isOnline(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager != null) {
            val capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return true
                }
            }
        }
        return false
    }

    // Будильник
    private fun alarmClock(alarmTime: Int) {
        val handler = Handler(Looper.getMainLooper())
        val mediaId: Int = R.raw.rington1
        val mediaPlayer = MediaPlayer.create(context, mediaId)

        val runnable = object : Runnable {
            private var alarmTriggered = false

            override fun run() {
                val now = Calendar.getInstance()
                val currentHour = now.get(Calendar.HOUR_OF_DAY)
                val currentMinute = now.get(Calendar.MINUTE)
                val currentTime = currentHour * 100 + currentMinute

                // Проверка на срабатывание будильника
                when {
                    !alarmTriggered && currentTime == alarmTime - 1 -> {
                        triggerAlarm()
                        alarmTriggered = true
                    }

                    currentTime == alarmTime && mediaPlayer.isPlaying.not() -> {
                        triggerAlarm()
                    }

                    currentTime > alarmTime -> {
                        alarmTriggered = false
                    }
                }

                // Повторяем проверку каждую минуту
                handler.postDelayed(this, 60000)
            }

            private fun triggerAlarm() {
                mediaPlayer.start()
                flagVoise = true
                if (!getStartFlashlight() && getConnType() != "Супертелефон") {
                    setStartFlashlight(true)
                    setTimer(30000)
                }
            }
        }

        handler.post(runnable)
    }

    // Функция голосового распознавания

    private fun startSpeechRecognition() {

        val speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(context) // context передается из Вашей Activity или Fragment
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU") // Устанавливаем язык на русский
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            ) // Настраиваем на частичные результаты
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // Подготовка к распознаванию речи
            }

            override fun onBeginningOfSpeech() {
                // Начало речи
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Уровень громкости
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Получение буфера
            }

            override fun onEndOfSpeech() {
                // Конец речи
            }

            override fun onError(error: Int) {
                // Обработка ошибок с текстом для TTS
                when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> speakText("Время сетевой операции истекло")
                    SpeechRecognizer.ERROR_NETWORK -> speakText("Произошла ошибка сети")
                    SpeechRecognizer.ERROR_AUDIO -> speakText("Ошибка записи звука")
                    SpeechRecognizer.ERROR_SERVER -> speakText("Сервер отправил ошибку")
                    SpeechRecognizer.ERROR_CLIENT -> speakText("Ошибка на стороне клиента")
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> speakText("Нет входной речи")
                    SpeechRecognizer.ERROR_NO_MATCH -> speakText("Распознавание не дало совпадений")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> speakText("Распознаватель занят")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> speakText("Недостаточно разрешений")
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> speakText("Слишком много запросов от одного клиента")
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> speakText("Сервер был отключен")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> speakText("Запрашиваемый язык не поддерживается")
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> speakText("Запрашиваемый язык недоступен")
                    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> speakText("Невозможно проверить поддержку")
                    SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> speakText("Служба не поддерживает прослушивание событий загрузки")
                    else -> speakText("Произошла неизвестная ошибка")
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {

                    if (!getStartFlashlight() && getConnType() == "Репитер (2 Канала)") {
                        setStartFlashlight(true)
                        setTimer(30000)
                    }

                    val recognizedText = matches[0]
                    // Здесь можно обработать распознанный текст
                    speakText("Вы произнесли $recognizedText")
                    setStartFlashlight(true)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Обработка частичных результатов (можно игнорировать)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Обработка событий
            }
        })

        speechRecognizer.startListening(intent)
    }


    // Включение/Отключение озвучивания

    private fun playMediaPlayer(player: MediaPlayer, playMusic: Boolean = false) {
        if (getPlayMusic() || playMusic) {
            mediaPlayer = player.also {
                it.setOnCompletionListener {
                    Log.d("Контрольный лог", "МЕДИА ПЛЕЕР ОСТАНОВЛЕН")
                    if ((getConnType() == "Репитер (1 Канал)" || getConnType() == "Репитер (2 Канала)") && !flagVoise) {
                        setFlashlight(false)
                    }
                }
                playSoundJob.launch {
                    delayTon1000hz {
                        it.start()
                        Log.d("Контрольный лог", "МЕДИА ПЛЕЕР ЗАПУЩЕН")
                        if (getConnType() == "Репитер (1 Канал)") {
                            setFlashlight(true)
                        }
                    }
                }
            }
        }
    }

    private fun speakText(text: String) {
        if (textToSpeech.isSpeaking) {
            return
        }

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("Контрольный лог", "ТТС НАЧАЛ ПРОИЗНЕСЕНИЕ")
                // VOX СИСТЕМА включаем вспышку при старте сообщения ттс
                if (getConnType() == "Репитер (1 Канал)") {
                    setFlashlight(true)
                }
            }

            override fun onDone(utteranceId: String?) {
                Log.d("Контрольный лог", "ТТС ЗАВЕРШИЛ ПРОИЗНЕСЕНИЕ")
                // VOX СИСТЕМА выключаем вспышку при остановке сообщения ттс
                if ((getConnType() == "Репитер (1 Канал)" || getConnType() == "Репитер (2 Канала)") && !flagVoise) {
                    setFlashlight(false)
                }
            }

            @Deprecated("Этот метод устарел")
            override fun onError(utteranceId: String?) {
               // Log.e("Контрольный лог", "Ошибка ТТС: $utteranceId")
                // VOX СИСТЕМА выключаем вспышку при ошибке сообщения ттс
                if (getConnType() == "Репитер (1 Канал)" || getConnType() == "Репитер (2 Канала)") {
                    setFlashlight(false)
                }
            }
        })

        val utteranceId = System.currentTimeMillis().toString()
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
}


//  Log.d("Контрольный лог", "setStartFlashlight ВЫЗВАНА: $conType")
// context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

//         Проверка разрешений на доступ к уведомлениям
//         рабочий метод для проверки какой режим включен
//    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//        val ringerMode = audioManager.ringerMode
//
//        when (ringerMode) {
//            AudioManager.RINGER_MODE_NORMAL -> {
//                Log.d("Контрольный лог", "Режим: Нормальный")
//            }
//            AudioManager.RINGER_MODE_SILENT -> {
//                Log.d("Контрольный лог", "Режим: Без звука")
//            }
//            AudioManager.RINGER_MODE_VIBRATE -> {
//                Log.d("Контрольный лог", "Режим: Вибрация")
//            }
//        }


