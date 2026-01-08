package com.mcal.dtmf.utils

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.os.StatFs
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.service.DtmfService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class Utils(
    private val mainRepository: MainRepository,
    private val scope: CoroutineScope,
    private val context: Application
) {
    private val requestCodeContactsPermission = 1
    private var audioTrack: AudioTrack? = null
    private var availableMB = 0L
    private var audioRecord: AudioRecord? = null
    private var recordedFilePath: String? = null
    private val recordedFiles by lazy {
        loadRecordedFiles().toMutableList()
    }
    private var isRecordingLocal = false
    // Переменная для хранения последней удачной локации
    var lastCurrentLocation: Location? = null

    // Объект-слушатель, который ловит данные от спутников
    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            // Как только GPS выдал новую точку, сохраняем её в переменную
            lastCurrentLocation = location
        }

        // Эти методы обязательны для реализации в старых версиях Android,
        // оставляем их пустыми
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private var previousContactsSize = 0 // Предыдущий размер списка контактов для отслеживания изменений
    private var lastAzimuth: Int? = null      // Хранит число (например, 125)
    private var lastDirection: String = ""    // Хранит слово (например, "Юго-Восток")
    private var lastCalculationResult: String = "" // Хранит весь текст для кнопки 4
    private var lastUserLatSpeech: String = ""
    private var lastUserLonSpeech: String = ""
    var smsNavigationIndex: Int = -1 // -1 означает, что навигация не активна, 0 — самое новое SMS.
    var totalSmsCount: Int = 0      // Общее количество SMS, для проверки границ.
    var sentences = emptyList<String>() // Список всех предложений помощи
    var currentSentenceIndex = 0 // Индекс текущего просматриваемого предложения
    var contacts: List<Pair<String, String>> = emptyList() // Список контактов (имя и номер)
    var currentContactIndex = -1 // Индекс текущего просматриваемого контакта
    var isContactMode = false // Флаг, который показывает, находимся ли мы в режиме просмотра контактов
    var isSmsMode = false // Флаг, который показывает, находимся ли мы в режиме просмотра сообщений
    var isHelpMode = false // Флаг, который показывает, находимся ли мы в режиме просмотра основных команд
    var isHelpMode1 = false // Флаг, который показывает, находимся ли мы в режиме просмотра помощи
    var isGpsMode = false // Флаг, который показывает, находимся ли мы в режиме навигации
    private val durationSeconds = 30000 // Время в течении которого происходит попытка дозвона для мониторинга
    var monitorJob: Job? = null
    var flagSimMonitor = false
    var monitorNumber: String = "87057895564" // Номер по умолчанию для тестовых звонков для проверки сети
    val callStates = mutableListOf<Int>() // Список изменений вызова для мониторинга точки установки репитера

    companion object {

        // Получение данных о температуре и заряде батареи
        private fun getCurrentBatteryTemperature(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                ((intent!!.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).toFloat()) / 10).toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }

        private fun getCurrentBatteryLevel(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                (intent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)).toFloat().toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }

        private fun getCurrentBatteryVoltage(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                (intent!!.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)).toFloat().toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }

        // Функция озвучивания состояния батареи
        fun batteryStatus(context: Context): Triple<String, String, String> {
            val batteryTemperature = getCurrentBatteryTemperature(context).toDouble().roundToInt()
            val batteryLevel = getCurrentBatteryLevel(context).toDouble().roundToInt()
            val batteryVoltage = getCurrentBatteryVoltage(context).toDouble() / 1000

            val temperatureText = when (batteryTemperature) {
                1, 21, 31, 41, 51, 61, 71, 81, 91 -> "$batteryTemperature градус"
                in 2..4, in 22..24, in 32..34, in 42..44, in 52..54, in 62..64, in 72..74, in 82..84, in 92..94 -> "$batteryTemperature градуса"
                else -> "$batteryTemperature градусов"
            }

            val levelText = when (batteryLevel) {
                1, 21, 31, 41, 51, 61, 71, 81, 91 -> "$batteryLevel процент"
                in 2..4, in 22..24, in 32..34, in 42..44, in 52..54, in 62..64, in 72..74, in 82..84, in 92..94 -> "$batteryLevel процента"
                else -> "$batteryLevel процентов"
            }

            val voltageWhole = batteryVoltage.toInt()
            val voltageFraction = ((batteryVoltage - voltageWhole) * 100).roundToInt()
            val voltageText = "$voltageWhole целых $voltageFraction сотых вольта"

            return Triple(temperatureText, levelText, voltageText)
        }
    }

    // Дата и время по команде 2*
    fun speakCurrentTime() {
        val currentTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = currentTime

        val hours = calendar.get(Calendar.HOUR_OF_DAY)
        val minutes = calendar.get(Calendar.MINUTE)
        val dayOfWeek =
            calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())

        val formattedTime = formatRussianTime(hours, minutes)
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

        mainRepository.speakText("Текущее время $formattedTime. Сегодня $dayOfWeek, $dayOfMonthString $month")
    }

    fun formatRussianTime(hours: Int, minutes: Int): String {
        val hoursString = when (hours) {
            1 -> "один час"
            2 -> "два часа"
            3 -> "три часа"
            4 -> "четыре часа"
            5 -> "пять часов"
            6 -> "шесть часов"
            7 -> "семь часов"
            8 -> "восемь часов"
            9 -> "девять часов"
            10 -> "десять часов"
            11 -> "одиннадцать часов"
            12 -> "двенадцать часов"
            13 -> "тринадцать часов"
            14 -> "четырнадцать часов"
            15 -> "пятнадцать часов"
            16 -> "шестнадцать часов"
            17 -> "семнадцать часов"
            18 -> "восемнадцать часов"
            19 -> "девятнадцать часов"
            20 -> "двадцать часов"
            21 -> "двадцать один час"
            22 -> "двадцать два часа"
            23 -> "двадцать три часа"
            0 -> "ноль часов"
            else -> "$hours часов"
        }

        val minutesString = when (minutes) {
            1 -> "одна минута"
            2 -> "две минуты"
            3 -> "три минуты"
            4 -> "четыре минуты"
            5 -> "пять минут"
            6 -> "шесть минут"
            7 -> "семь минут"
            8 -> "восемь минут"
            9 -> "девять минут"
            10 -> "десять минут"
            11 -> "одиннадцать минут"
            12 -> "двенадцать минут"
            13 -> "тринадцать минут"
            14 -> "четырнадцать минут"
            15 -> "пятнадцать минут"
            16 -> "шестнадцать минут"
            17 -> "семнадцать минут"
            18 -> "восемнадцать минут"
            19 -> "девятнадцать минут"
            20 -> "двадцать минут"
            21 -> "двадцать одна минута"
            22 -> "двадцать две минуты"
            23 -> "двадцать три минуты"
            24 -> "двадцать четыре минуты"
            25 -> "двадцать пять минут"
            26 -> "двадцать шесть минут"
            27 -> "двадцать семь минут"
            28 -> "двадцать восемь минут"
            29 -> "двадцать девять минут"
            30 -> "тридцать минут"
            31 -> "тридцать одна минута"
            32 -> "тридцать две минуты"
            33 -> "тридцать три минуты"
            34 -> "тридцать четыре минуты"
            35 -> "тридцать пять минут"
            36 -> "тридцать шесть минут"
            37 -> "тридцать семь минут"
            38 -> "тридцать восемь минут"
            39 -> "тридцать девять минут"
            40 -> "сорок минут"
            41 -> "сорок одна минута"
            42 -> "сорок две минуты"
            43 -> "сорок три минуты"
            44 -> "сорок четыре минуты"
            45 -> "сорок пять минут"
            46 -> "сорок шесть минут"
            47 -> "сорок семь минут"
            48 -> "сорок восемь минут"
            49 -> "сорок девять минут"
            50 -> "пятьдесят минут"
            51 -> "пятьдесят одна минута"
            52 -> "пятьдесят две минуты"
            53 -> "пятьдесят три минуты"
            54 -> "пятьдесят четыре минуты"
            55 -> "пятьдесят пять минут"
            56 -> "пятьдесят шесть минут"
            57 -> "пятьдесят семь минут"
            58 -> "пятьдесят восемь минут"
            59 -> "пятьдесят девять минут"
            else -> "$minutes минут"
        }
        return "$hoursString $minutesString"
    }

    private fun loadRecordedFiles(): List<String> {
        val storageDir = context.getExternalFilesDir(null) ?: return emptyList()

        // Находим все файлы, соответствующие вашему шаблону имени
        // Шаблон: deletesec:x_selective:x_registers:x_adresat:x_time:x.pcm
        // Мы можем использовать регулярное выражение или просто проверить расширение и структуру.

        // Простая проверка по расширению и наличию метки 'time:'
        return storageDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pcm") && it.name.contains("_time:") }
            ?.map { it.absolutePath }
            ?.toMutableList()
            ?: emptyList()
    }

    // Последний вызов: пропущенный (0*) или входящий (0#)
    fun lastMissed(context: Context, isIncomingAccepted: Boolean) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val msg = if (isIncomingAccepted) {
                "Не получено разрешение на доступ к информации о последнем входящем вызове"
            } else {
                "Не получено разрешение на доступ к информации о последнем пропущенном вызове"
            }
            mainRepository.speakText(msg)
            mainRepository.setInput("")
            return
        }

        val callLogUri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )

        val selection: String
        val selectionArgs: Array<String>
        val notFoundText: String

        if (isIncomingAccepted) {
            // Последний входящий принятый вызов (длительность > 0)
            selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DURATION} > 0"
            selectionArgs = arrayOf(CallLog.Calls.INCOMING_TYPE.toString())
            notFoundText = "Не найдено входящих вызовов."
        } else {
            // Последний пропущенный
            selection = "${CallLog.Calls.TYPE} = ?"
            selectionArgs = arrayOf(CallLog.Calls.MISSED_TYPE.toString())
            notFoundText = "Не найдено пропущенных вызовов."
        }

        val sortOrder = "${CallLog.Calls.DATE} DESC"

        context.contentResolver.query(callLogUri, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val dateColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val numberRaw = cursor.getString(numberColumn)
                    val date = cursor.getLong(dateColumn)
                    val contactName = getContactNameByNumber(numberRaw, context)
                        ?: "имени которого нет в телефонной книге"

                    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val timeText = dateFormat.format(Date(date))

                    val normalizedNumber = formatPhoneNumber(numberRaw) ?: numberRaw.replace("+7", "8")

                    if (!isIncomingAccepted) {
                        // Озвучивание для пропущенного вызова и подготовка номера
                        Log.e("Контрольный лог", "ИМЯ1: $contactName НОМЕР1 $normalizedNumber")
                        mainRepository.speakText(
                            "Последний пропущенный вызов был от абонента $contactName... он звонил в $timeText... " +
                                    "Если Вы хотите перезвонить, нажмите звездочку. Для отмены нажмите решетку. Также Вы можете " +
                                    "закрепить этот номер за одной из клавиш быстрого набора")
                        mainRepository.setInput("")
                        mainRepository.setInput(normalizedNumber)
                    } else {
                        // Озвучивание для принятого входящего вызова и подготовка номера
                        Log.e("Контрольный лог", "ИМЯ2: $contactName НОМЕР2 $normalizedNumber")
                        mainRepository.speakText(
                            "Последний входящий вызов был от абонента $contactName... он звонил в $timeText... " +
                                    "Если Вы хотите перезвонить, нажмите звездочку. Для отмены нажмите решетку. Также Вы можете " +
                                    "закрепить этот номер за одной из клавиш быстрого набора")
                        mainRepository.setInput("")
                        mainRepository.setInput(normalizedNumber)
                    }
                } else {
                    mainRepository.speakText(notFoundText)
                }
            }
    }

    // Очистка всего журнала вызовов по команде 00000#
    fun clearCallLog(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED
        ) {
            mainRepository.speakText("Не получено разрешение на очистку журнала вызовов")
            mainRepository.setInput("")
            return
        }

        try {
            val deleted = context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
            if (deleted >= 0) {
                // При некоторых прошивках может вернуться 0 даже при успешной очистке пустого журнала
                val message = if (deleted > 0) {
                    "Журнал вызовов успешно очищен"
                } else {
                    "Журнал вызовов пуст или был очищен ранее"
                }
                mainRepository.speakText(message)
            } else {
                mainRepository.speakText("Не удалось очистить журнал вызовов")
            }
        } catch (e: SecurityException) {
            mainRepository.speakText("Недостаточно прав для очистки журнала вызовов")
        } catch (e: Exception) {
            mainRepository.speakText("Ошибка при очистке журнала вызовов")
        } finally {
            mainRepository.setInput("")
        }
    }

    // Вспомогательная функция для нормализации номера к формату 87...
    private fun normalizeNumber(number: String): String {
        // Удаляем все символы, кроме цифр
        var normalized = number.replace(Regex("[^0-9]"), "")

        // Если номер начинается с +7 или 7, заменяем на 8
        if (normalized.startsWith("7") && normalized.length == 11) {
            normalized = "8" + normalized.substring(1)
        }

        return normalized
    }

    // Функция загружающая все контакты в список
    fun loadContacts(context: Context): List<Pair<String, String>> {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            mainRepository.speakText("Не получено разрешение на доступ к контактам.")
            return emptyList()
        }

        // Используем Map, чтобы избежать дублирования
        val contactMap = mutableMapOf<String, String>()
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: "Имя не найдено"
                val number = it.getString(numberIndex) ?: "Номер не найден"

                // Нормализуем номер перед добавлением
                val normalizedNumber = normalizeNumber(number)

                // Добавляем контакт в карту, используя нормализованный номер как ключ
                // Если ключ уже существует, он будет перезаписан (в данном случае это не критично)
                contactMap[normalizedNumber] = name
            }
        }

        // Преобразуем Map обратно в List<Pair>
        return contactMap.map { it.value to it.key }.toList()
    }

    fun loadSentencesFromHtml(context: Context): List<String> {
        val sentences = mutableListOf<String>()

        try {
            val inputStream = context.assets.open("help/index.html")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val htmlContent = reader.readText()

            // 1. Убираем все HTML-теги
            val cleanText = htmlContent.replace(Regex("<[^>]*>"), "")

            // 2. Нормализуем пробелы и переносы строк, оставляя только один пробел между словами
            val cleanedText = cleanText.replace(Regex("\\s+"), " ").trim()

            // 3. Разбиваем текст на предложения
            // Регулярное выражение ищет знаки препинания (. ? !) и разбивает по ним текст.
            val sentenceRegex = Regex("(?<=[.?!])\\s*")
            sentences.addAll(cleanedText.split(sentenceRegex))

            // 4. Фильтруем пустые строки
            return sentences.filter { it.isNotBlank() }

        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    // Расчет расстояния по 2 координатам
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble() / 1000.0 // в километры
    }

    // Расчет азимута на устройство
    private fun calculateAzimuth(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(2)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        // results[1] - это начальный азимут
        return (results[1].toDouble() + 360) % 360
    }

    // Функция проверки доступен ли интернет
    fun isOnline(context: Context): Boolean {
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

    // Функция голосового распознавания
    fun startSpeechRecognition(context: Context, onResult: (String?) -> Unit) {

        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU") // Устанавливаем язык на русский
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // Настраиваем на частичные результаты
        }

        var silenceDuration: Int // Переменная для отслеживания времени молчания
        val maxSilenceDuration = 10000 // Максимальная продолжительность молчания в миллисекундах
        val timer = Timer()

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                silenceDuration = 0 // Сбрасываем таймер молчания
                timer.scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        silenceDuration += 1000
                        if (silenceDuration >= maxSilenceDuration) {
                            speechRecognizer.cancel()
                            onResult(null)
                            timer.cancel()
                            mainRepository.speakText("Вы ничего не произнесли.")
                        }
                    }
                }, 0, 1000) // Запускаем таймер с интервалом 1 секунда
            }

            override fun onBeginningOfSpeech() {
                silenceDuration = 0
                timer.cancel()
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Уровень громкости
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Получение буфера
            }

            override fun onEndOfSpeech() {
            }

            override fun onError(error: Int) {
                onResult(null)
                timer.cancel() // Останавливаем таймер при ошибке
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                if (matches.isNullOrEmpty()) {
                    onResult(null)
                    return
                }

                val recognizedText = matches[0]
                onResult(recognizedText)
                timer.cancel()
            }

            override fun onPartialResults(partialResults: Bundle?) {
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
            }
        })

        speechRecognizer.startListening(intent)
    }

    fun getNameContact(result: String, context: Context) {

        // Проверяем, является ли распознанный текст именем контакта
        val contactNumber = getContactNumberByName(result, context)
        if (contactNumber != null) {
            scope.launch {
                val contactName = getContactNameByNumber(contactNumber, context)
                mainRepository.speakText("Найден контакт $contactName. Нажмите звездочку для вызова или решетку для отмены") // Используем имя, как оно записано в телефонной книге
                mainRepository.setInput(contactNumber) // Устанавливаем номер для вызова
                delay(7000)
            }
        } else {
            scope.launch {
                mainRepository.setInput("")
                mainRepository.speakText(
                    "В телефонной книге нет абонента с именем $result. Вы можете добавить этого абонента в вашу телефонную книгу")
            }
        }
    }

    /// Блок распознавания речевой команды и поиска имени в книге контактов с последующим вызовом
    private fun getContactNumberByName(name: String, context: Context): String? {
        val cr = context.contentResolver
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        var contactNumber: String? = null
        val lowerCaseName = normalizeString(name)

        val cursor = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$lowerCaseName%"),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                // Получаем номер телефона
                contactNumber = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                // Приводим номер к нужному формату
                contactNumber = formatPhoneNumber(contactNumber)
            } else {
                val fuzzyContactName = findContactWithFuzzyMatching(lowerCaseName, context)
                if (fuzzyContactName != null) {
                    // Выполняем повторный запрос для получения номера по найденному имени
                    val fuzzyCursor = cr.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        projection,
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                        arrayOf(fuzzyContactName),
                        null
                    )

                    fuzzyCursor?.use { fuzzy ->
                        if (fuzzy.moveToFirst()) {
                            contactNumber = fuzzy.getString(fuzzy.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Phone.NUMBER))
                            contactNumber = formatPhoneNumber(contactNumber)
                        }
                    }
                }
            }
        }
        return contactNumber // Возвращаем номер телефона
    }

    // Нормализация строки для поиска
    private fun normalizeString(input: String): String {
        return input.lowercase().trim() // Изменено на lowercase()
            .replace(Regex("[^а-яА-ЯёЁ0-9\\s]"), "") // Удаляем все символы, кроме русских букв и цифр
    }

    // Функция для поиска контакта с учетом нечеткого совпадения
    private fun findContactWithFuzzyMatching(name: String, context: Context): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null,
            null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val contactName = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                if (isSimilar(name, contactName.lowercase())) {
                    return contactName // Возвращаем имя, как оно записано в телефонной книге
                }
            }
        }
        return null // Если ничего не найдено
    }

    // Функция для проверки схожести двух строк
    private fun isSimilar(input: String, contactName: String): Boolean {
        val distance = levenshteinDistance(input, contactName)
        return distance <= 2 // Допускаем две ошибки
    }

    // Алгоритм Левенштейна для вычисления расстояния между строками
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    // Форматирование номера телефона
    private fun formatPhoneNumber(number: String?): String? {
        // Проверяем, что номер не пустой
        if (number.isNullOrEmpty()) return null

        // Убираем все нецифровые символы
        val cleanedNumber = number.replace(Regex("\\D"), "")

        // Если номер начинается с 7, заменяем на 8
        return if (cleanedNumber.startsWith("7")) {
            "8${cleanedNumber.substring(1)}"
        } else {
            number // Возвращаем номер без изменений, если он не начинается с 7
        }
    }

    // Получение имени по номеру с книги контактов
    fun getContactNameByNumber(number: String, context: Context): String? {
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

    fun saveContact(phoneNumber: String, contactName: String, context: Context): Boolean {
        // Приводим имя к формату с заглавной буквы
        val formattedName = contactName.split(" ").joinToString(" ") {
            it.replaceFirstChar { char -> char.uppercase() } // Приводим первую букву каждого слова к заглавной
        }

        // Создаем новый объект для контакта
        val values = ContentValues().apply {
            put(ContactsContract.RawContacts.ACCOUNT_TYPE, "")
            put(ContactsContract.RawContacts.ACCOUNT_NAME, "")
        }

        // Вставляем новый контакт и проверяем, был ли успешно добавлен контакт
        val rawContactUri = context.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, values)
            ?: return false // Не удалось создать контакт

        val rawContactId = ContentUris.parseId(rawContactUri)

        // Сохраняем имя контакта
        val nameValues = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, formattedName)
        }
        // Проверяем, удалось ли сохранить имя контакта
        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)
            ?: return false // Не удалось сохранить имя контакта

        // Сохраняем номер телефона
        val phoneValues = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        }
        // Проверяем, удалось ли сохранить номер телефона
        return context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, phoneValues) != null // Возвращаем true, если номер телефона успешно сохранен
    }

    // Функция для удаления контакта из телефонной книги по имени
    fun deleteContactByName(result: String, context: Context): Boolean {
        // Проверяем разрешение на доступ к контактам
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {

            // Запрашиваем разрешение
            ActivityCompat.requestPermissions(context as Activity,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                requestCodeContactsPermission)
            return false
        }

        // Определяем URI для поиска контакта по имени
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val selection = "LOWER(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}) = LOWER(?)"
        val selectionArgs = arrayOf(result.lowercase()) // Используем LOWER для поиска

        // Выполняем запрос для получения ID контакта
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID))

                // Теперь выполняем запрос к RawContacts, чтобы получить все записи
                val rawProjection = arrayOf(ContactsContract.RawContacts._ID)
                val rawSelection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
                val rawSelectionArgs = arrayOf(contactId.toString())

                context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, rawProjection, rawSelection, rawSelectionArgs, null)?.use { rawCursor ->
                    if (rawCursor.moveToFirst()) {
                        do {
                            val rawContactId = rawCursor.getLong(rawCursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
                            val deleteUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId)

                            // Удаляем контакт по его RawContact ID
                            val rowsDeleted = context.contentResolver.delete(deleteUri, null, null)
                            if (rowsDeleted > 0) {
                                return true // Возвращаем true, если контакт был успешно удален
                            }
                        } while (rawCursor.moveToNext())
                    }
                }
            }
        }

        return false // Возвращаем false, если контакт не найден или не удален
    }

    // 1. Мягкий старт (без удаления данных чипа)
    fun startGpsSoft(context: Context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.removeUpdates(locationListener)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        }
    }

    // 2. Холодный старт (с полной очисткой)
    private fun forceRefreshGps(context: Context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastCurrentLocation = null
        locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null)
        locationManager.removeUpdates(locationListener)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        }
    }

    // 3. Остановка (снятие питания с чипа)
    fun stopGps(context: Context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.removeUpdates(locationListener)
        // lastCurrentLocation НЕ зануляем, чтобы старая точка была доступна для озвучки
    }

    // Перемещение по списку смс
    fun getIncomingSmsByIndex(context: Context, index: Int): Pair<String, Int> { // Pair<Сообщение, ОбщееКоличество>
        val smsUri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("address", "body", "date")

        val cursor = context.contentResolver.query(
            smsUri,
            projection,
            null,
            null,
            "date DESC" // Сортировка: самое новое сообщение первым
        )

        cursor?.use {
            val totalCount = it.count

            if (totalCount == 0) {
                return Pair("Сообщения отсутствуют.", 0)
            }

            // Проверка границ
            if (index < 0 || index >= totalCount) {
                return Pair("", totalCount)
            }

            // Перемещаемся к нужному сообщению
            if (it.moveToPosition(index)) {

                // --- 1. Извлечение и идентификация отправителя ---
                val senderAddress = it.getString(it.getColumnIndexOrThrow("address"))
                val contactName = getContactNameByNumber(senderAddress, context)
                val senderIdentifier = contactName ?: senderAddress

                // --- 2. Извлечение и расчет времени ---
                val messageDateMs = it.getLong(it.getColumnIndexOrThrow("date"))
                val currentTimeMs = System.currentTimeMillis()
                val delayMs = currentTimeMs - messageDateMs

                val delayString = when {
                    delayMs < TimeUnit.MINUTES.toMillis(1) -> "только что"
                    delayMs < TimeUnit.HOURS.toMillis(1) -> {
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(delayMs)

                        // Логика склонения для МИНУТ
                        val num = minutes % 100
                        val pluralMin = when {
                            num in 11L..19L -> "минут"
                            num % 10 == 1L -> "минуту"
                            num % 10 in 2L..4L -> "минуты"
                            else -> "минут"
                        }
                        "$minutes $pluralMin назад"
                    }
                    delayMs < TimeUnit.DAYS.toMillis(1) -> {
                        val hours = TimeUnit.MILLISECONDS.toHours(delayMs)

                        // Логика склонения для ЧАСОВ
                        val num = hours % 100
                        val pluralHour = when {
                            num in 11L..19L -> "часов"
                            num % 10 == 1L -> "час"
                            num % 10 in 2L..4L -> "часа"
                            else -> "часов"
                        }
                        "$hours $pluralHour назад"
                    }
                    else -> {
                        val sdf = SimpleDateFormat("dd.MM.yyyy в HH:mm", Locale.getDefault())
                        sdf.format(Date(messageDateMs))
                    }
                }
                // --- Конец логики времени ---

                // 3. Извлекаем и преобразуем тело сообщения
                val messageBody = it.getString(it.getColumnIndexOrThrow("body"))
                val convertedText = convertToRussianText(messageBody)

                // 4. Формируем итоговое сообщение
                val messageIndex = index + 1 // Человеческое нумерование

                // Здесь слово "получено" используется один раз для всех случаев
                val message = "Сообщение номер $messageIndex из $totalCount от $senderIdentifier, получено $delayString. Содержание: $convertedText"

                return Pair(message, totalCount)
            }
        }

        return Pair("Ошибка при чтении сообщения.", 0)
    }

    // 1. Удаление одного конкретного SMS по его индексу в списке
    private fun deleteSmsByIndex(context: Context, index: Int): Boolean {
        val smsUri = Uri.parse("content://sms/inbox")

        // Запрашиваем только ID, используя ту же сортировку, что и при чтении
        val cursor = context.contentResolver.query(
            smsUri,
            arrayOf("_id"),
            null,
            null,
            "date DESC"
        )

        cursor?.use {
            if (it.moveToPosition(index)) {
                // Получаем системный ID сообщения
                val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                val deleteUri = Uri.parse("content://sms/$id")

                // Выполняем удаление
                val rowsDeleted = context.contentResolver.delete(deleteUri, null, null)
                return rowsDeleted > 0
            }
        }
        return false
    }

    // 2. Удаление всех входящих SMS
    private fun deleteAllSms(context: Context) {
        // Проверка права на чтение (для чтения статуса/подсказки) — фактическое удаление доступно только приложению SMS по умолчанию
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            mainRepository.speakText("Недостаточно прав для удаления сообщений")
            mainRepository.setInput("")
            return
        }

        // Начиная с Android 4.4 (API 19), удалять SMS может только приложение SMS по умолчанию
        try {
            val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
            val myPackage = context.packageName
            if (defaultSmsPackage != null && defaultSmsPackage != myPackage) {
                mainRepository.speakText("Удаление невозможно: установите это приложение как программу SMS по умолчанию")
                mainRepository.setInput("")
                return
            }
        } catch (e: Exception) {
            // На некоторых устройствах Telephony API может вести себя нестандартно — продолжаем попытку удаления
        }

        try {
            val deleted = context.contentResolver.delete(Uri.parse("content://sms"), null, null)
            if (deleted > 0) {
                mainRepository.speakText("Все сообщения удалены")
                mainRepository.setInput("")
            } else {
                mainRepository.speakText("Сообщений для удаления не найдено")
                mainRepository.setInput("")
            }
        } catch (se: SecurityException) {
            mainRepository.speakText("Недостаточно прав для удаления сообщений")
            mainRepository.setInput("")
        } catch (e: Exception) {
            mainRepository.speakText("Ошибка при удалении сообщений")
            mainRepository.setInput("")
        } finally {
            mainRepository.setInput("")
        }
    }

    // Функция для отправки надиктованного сообщения СМС по команде 4 после набора номера
    fun sendSmsBySlot(context: Context, phoneNumber: String, message: String, slotIndex: Int): Boolean {
        return try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false

            val activeList = subManager.activeSubscriptionInfoList
            if (activeList.isNullOrEmpty() || slotIndex >= activeList.size) return false

            // 1. Берем РЕАЛЬНЫЙ ID симки
            val realSubId = activeList[slotIndex].subscriptionId

            // 2. Получаем SmsManager СТРОГО через createForSubscriptionId
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val systemSmsManager = context.getSystemService(SmsManager::class.java)
                systemSmsManager.createForSubscriptionId(realSubId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(realSubId)
            }

            var cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
            if (cleanNumber.startsWith("8")) cleanNumber = "+7" + cleanNumber.substring(1)

            // 3. ФИЗИЧЕСКАЯ ОТПРАВКА
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)

            // 4. ЗАПИСЬ В БАЗУ СО ЗНАЧКОМ СИМКИ (sub_id)
            try {
                val values = ContentValues().apply {
                    put("address", cleanNumber)
                    put("body", message)
                    put("date", System.currentTimeMillis())
                    put("type", 2) // 2 = SENT
                    put("sub_id", realSubId) // ВОТ ОНО! Это добавит значок SIM в приложении
                    put("error_code", 0)
                }
                context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
            } catch (e: Exception) {}
            true
        } catch (e: Exception) {
            false
        }
    }

    // Функция для преобразования номера телефона в более понятное озвучивание
    fun numberToText(number: String): String {
        if (number.isEmpty()) return "не назначен"

        // Убираем все пробелы, чтобы работать только с цифрами
        val cleanedNumber = number.replace(" ", "")

        // Проверяем длину номера
        return when (cleanedNumber.length) {
            11 -> {
                // Разбиваем на 5 частей
                val part1 = cleanedNumber.substring(0, 1)  // 1 часть
                val part2 = cleanedNumber.substring(1, 4)  // 2 часть
                val part3 = cleanedNumber.substring(4, 7)  // 3 часть
                val part4 = cleanedNumber.substring(7, 9)  // 4 часть
                val part5 = cleanedNumber.substring(9, 11) // 5 часть

                // Формируем текстовое представление
                "$part1. $part2. $part3. $part4. $part5."
            }
            else -> {
                // Произносим по цифрам
                val digits = cleanedNumber.map { it.toString() }
                return digits.joinToString(" ") { digit ->
                    when (digit) {
                        "0" -> "ноль."
                        "1" -> "один."
                        "2" -> "два."
                        "3" -> "три."
                        "4" -> "четыре."
                        "5" -> "пять."
                        "6" -> "шесть."
                        "7" -> "семь."
                        "8" -> "восемь."
                        "9" -> "девять."
                        else -> ""
                    }
                }
            }
        }
    }

    // Функция для преобразования текста СМС в нормальный русский текст если написано английскими буквами
    private fun convertToRussianText(input: String): String {
        // Удаляем ссылки из текста
        val cleanedInput = input.replace(Regex("https?://\\S+"), "")

        // Заменяем сокращения
        val updatedInput = cleanedInput
            .replace("obl.", "области")
            .replace("Zh", "ж")
            .replace("zh", "ж")
            .replace("m/s", "метров в секунду")
            .replace("ya", "я")
            .replace("ГБ", "гигабайт")
            .replace("SMS", "текстовых сообщений")
            .replace("Beeline", "билайн")
            .replace("Tele2", "теле два")
            .replace("Altel", "алтел")
            .replace("РК.", "республике казахстан.")
            .replace("др.", "другие")
            .replace("моб.", "мобильные")
            .replace("Kazakhtelecom", "казахтелеком")
            .replace("telecom.kz", "телеком точка кей зет.")
            .replace("мин", "минут")
            .replace("*", "звездочка")
            .replace("#", "решетка")
            .replace("шт", "штук")
            .replace("Мб", "мегабайт")
            .replace("#", "решетка")
            .replace("%", "процентов")
            .replace("тг.", "тенге")
            .replace("Tг.", "тенге")
            .replace("silnyi", "сильный")
            .replace("Shymkent", "Шымкент")
            .replace("Silnaya", "Сильная")
            .replace("metel", "Метель")

        val transliterationMap = mapOf(
            'a' to 'а', 'b' to 'б', 'c' to 'ц', 'd' to 'д', 'e' to 'е', 'f' to 'ф', 'g' to 'г', 'h' to 'х', 'i' to 'и',
            'j' to 'й', 'k' to 'к', 'l' to 'л', 'm' to 'м', 'n' to 'н', 'o' to 'о', 'p' to 'п', 'q' to 'щ', 'r' to 'р',
            's' to 'с', 't' to 'т', 'u' to 'у', 'v' to 'в', 'w' to 'в', 'x' to 'ь', 'y' to 'ы', 'z' to 'з'
        )
        val words = updatedInput.split(" ")
        val convertedWords = words.map { word ->
            word.map { char ->
                val lowerChar = char.lowercaseChar() // Преобразуем символ в нижний регистр
                val transliteratedChar = transliterationMap[lowerChar] ?: lowerChar
                transliteratedChar
            }.joinToString("")
        }
        return convertedWords.joinToString(" ")
    }

    // Уровни сигнала сим карт по команде 3*
    fun speakSimSignalLevels(context: Context) {
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            mainRepository.speakText("Не получено разрешение на доступ к уровню сигнала сотовой сети")
            mainRepository.setInput("")
            return
        }
        val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telMgr = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val subs = subMgr.activeSubscriptionInfoList
        if (subs.isNullOrEmpty()) {
            mainRepository.speakText("В устройстве нет ни одной действующей сим карты")
            mainRepository.setInput("")
            return
        }
        fun mapOperator(name: String?): String {
            val n = name?.uppercase() ?: ""
            return when {
                n.contains("ACTIV") -> "Актив"
                n.contains("ALTEL") -> "Алтэл"
                n.contains("MTS") || n.contains("МТС") -> "МТС"
                n.contains("BEELINE") -> "Билайн"
                n.contains("MEGAFON") || n.contains("МЕГАФОН") -> "Мегафон"
                n.contains("TELE2") -> "Теле 2"
                else -> name ?: "Нет сигнала"
            }
        }
        fun levelToWord(level: Int?): String {
            return when (level) {
                1 -> "слабый"
                2 -> "средний"
                3 -> "хороший"
                4 -> "отличный"
                else -> "неизвестен"
            }
        }
        var firstMsg = ""
        var secondMsg = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            subs.forEach { s ->
                val tm = telMgr.createForSubscriptionId(s.subscriptionId)
                val strength = try { tm.signalStrength } catch (_: Exception) { null }
                val level = try { strength?.level } catch (_: Exception) { null }
                val op = mapOperator(s.carrierName?.toString())
                val part = if (level == null || level <= 0) "сигнал отсутствует" else "уровень ${levelToWord(level)}"
                if (s.simSlotIndex == 0) firstMsg = "первая сим карта, оператор $op, $part"
                else if (s.simSlotIndex == 1) secondMsg = "вторая сим карта, оператор $op, $part"
            }
        } else {
            val cells = try { telMgr.allCellInfo } catch (_: Exception) { null }
            var bestLevelRaw = Int.MIN_VALUE
            cells?.forEach { cell ->
                try {
                    val level = when (cell) {
                        is android.telephony.CellInfoGsm -> cell.cellSignalStrength.level
                        is android.telephony.CellInfoLte -> cell.cellSignalStrength.level
                        is android.telephony.CellInfoWcdma -> cell.cellSignalStrength.level
                        is android.telephony.CellInfoCdma -> cell.cellSignalStrength.level
                        else -> Int.MIN_VALUE
                    }
                    if (level != Int.MIN_VALUE) bestLevelRaw = kotlin.math.max(bestLevelRaw, level)
                } catch (_: Exception) {}
            }
            val bestLevel: Int? = if (bestLevelRaw == Int.MIN_VALUE) null else bestLevelRaw
            subs.forEach { s ->
                val op = mapOperator(s.carrierName?.toString())
                val part = if (bestLevel == null || bestLevel <= 0) "сигнал отсутствует" else "уровень ${levelToWord(bestLevel)}"
                if (s.simSlotIndex == 0) firstMsg = "первая сим карта, оператор $op, $part"
                else if (s.simSlotIndex == 1) secondMsg = "вторая сим карта, оператор $op, $part"
            }
        }
        val message = when {
            firstMsg.isNotEmpty() && secondMsg.isNotEmpty() -> "Уровни сотовой сети: $firstMsg. $secondMsg"
            firstMsg.isNotEmpty() -> "Уровень сотовой сети: $firstMsg"
            secondMsg.isNotEmpty() -> "Уровень сотовой сети: $secondMsg"
            else -> "Нет данных по уровню сигнала"
        }
        mainRepository.speakText(message)
        mainRepository.setInput("")
    }

    // Контрольные тона для отладки VOX а также проверки гальванической развязки
    fun playDtmfTones(period: Long, duration: Long) {
        scope.launch {
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
                ToneGenerator.TONE_DTMF_9
            )

            for (i in dtmfDigits.indices) {
                delay(period) // период проигрывания
                playDtmfTone(dtmfDigits[i], period, duration)
            }
        }
    }

    fun playDtmfTone(tone: Int, period: Long, duration: Long) {
        val volume = mainRepository.getVolumeLevelTts()
        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, volume.toInt()) // 100 - громкость
        scope.launch {
            toneGenerator.startTone(tone, duration.toInt()) // Длительность тона
            delay(period) // период проигрывания
            toneGenerator.release()
        }
    }

    // Функция предварительного тона активирующего VOX при всех речевых сообщениях
     fun voxActivation(delayMillis: Long = 2000, voxActivation: Long = 500, onComplete: suspend () -> Unit) {
        val sampleRate = 44100 // Частота дискретизации
        val frequency = 800.0 // Частота звука (не допустимо выбирать частоту активации кратной или равной тонам определения)
        val bufferSize = (sampleRate * voxActivation / 1000).toInt() // Размер буфера

         scope.launch {
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
        }
    }

    // Генерация субтонов CTCSS для системы непрерывного тонального шумоподавления
    fun playCTCSS(frequency: Double, volumeLevelCtcss: Double, periodCtcss: Int, durationCtcss: Int) {
        val sampleRate = 44100 // Частота дискретизации
        val lowCutoffFrequency = frequency * 0.8 // Нижняя частота среза полосового фильтра (например, 80% от частоты)
        val highCutoffFrequency = frequency * 1.2 // Верхняя частота среза полосового фильтра (например, 120% от частоты)
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
            return
        }

        if (mainRepository.getIsPlaying() != true) {
            audioTrack?.play()
            mainRepository.setIsPlaying(true)

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

            scope.launch {
                if (periodCtcss == 0 && durationCtcss == 0) {
                    while (mainRepository.getIsPlaying() == true) {
                        for (i in 0 until bufferSize) {
                            val rawValue = volumeLevelCtcss * sin(angle) // Генерация синусоиды

                            // Применение нижнего фильтра Баттерворта
                            x[0] = rawValue
                            y[0] =
                                b[0] * x[0] + b[1] * x[1] + b[2] * x[2] - a[1] * y[1] - a[2] * y[2]
                            // Сдвиг значений
                            for (j in order downTo 1) {
                                x[j] = x[j - 1]
                                y[j] = y[j - 1]
                            }

                            // Применение верхнего фильтра Баттерворта
                            val filteredValue = y[0]
                            x[0] = filteredValue
                            yHigh[0] =
                                bHigh[0] * x[0] + bHigh[1] * x[1] + bHigh[2] * x[2] - aHigh[1] * yHigh[1] - aHigh[2] * yHigh[2]
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
                            val value =
                                (buffer[i] * 32767).toInt() // Преобразование в 16-битный формат
                            // Ограничиваем значения, чтобы избежать клиппинга
                            val clampedValue = value.coerceIn(-32768, 32767)
                            byteBuffer[2 * i] = (clampedValue and 0xFF).toByte()
                            byteBuffer[2 * i + 1] = (clampedValue shr 8 and 0xFF).toByte()
                        }

                        // Запись в AudioTrack с обработкой ошибок
                        val result = audioTrack?.write(byteBuffer, 0, byteBuffer.size)
                        if (result == AudioTrack.ERROR_INVALID_OPERATION) {
                            break
                        } else if (result == AudioTrack.ERROR) {
                            break
                        }
                    }
                } else {
                    // Режим прерывистого тона
                    val durationSamples = (durationCtcss.toDouble() / 1000.0 * sampleRate).toInt()

                    while (mainRepository.getIsPlaying() == true) {
                        var samplesWritten = 0
                        while (samplesWritten < durationSamples && mainRepository.getIsPlaying() == true) {
                            for (i in 0 until bufferSize) {
                                val rawValue = volumeLevelCtcss * sin(angle)

                                x[0] = rawValue
                                y[0] = b[0] * x[0] + b[1] * x[1] + b[2] * x[2] - a[1] * y[1] - a[2] * y[2]
                                for (j in order downTo 1) {
                                    x[j] = x[j - 1]
                                    y[j] = y[j - 1]
                                }
                                val filteredValue = y[0]
                                x[0] = filteredValue
                                yHigh[0] = bHigh[0] * x[0] + bHigh[1] * x[1] + bHigh[2] * x[2] - aHigh[1] * yHigh[1] - aHigh[2] * yHigh[2]
                                for (j in order downTo 1) {
                                    x[j] = x[j - 1]
                                    yHigh[j] = yHigh[j - 1]
                                }
                                buffer[i] = yHigh[0].toFloat()
                                angle += angleIncrement
                                if (angle >= 2 * PI) angle -= 2 * PI
                            }
                            val byteBuffer = ByteArray(bufferSize * 2)
                            for (i in buffer.indices) {
                                val value = (buffer[i] * 32767).toInt()
                                val clampedValue = value.coerceIn(-32768, 32767)
                                byteBuffer[2 * i] = (clampedValue and 0xFF).toByte()
                                byteBuffer[2 * i + 1] = (clampedValue shr 8 and 0xFF).toByte()
                            }
                            val result = audioTrack?.write(byteBuffer, 0, byteBuffer.size)
                            if (result == null || result <= 0) break
                            samplesWritten += result / 2
                        }

                        // Пауза (тишина)
                        delay(periodCtcss.toLong())
                    }

                }
            }
        }
    }

    // Oстановка генерации субтона
    fun stopPlayback() {
        if (mainRepository.getIsPlaying() == true) {
            mainRepository.setIsPlaying(false)
            audioTrack?.stop()
            // audioTrack?.release() // Не освобождайте, если хотите использовать его повторно
            // audioTrack = null // Не обнуляйте, если хотите использовать его повторно
        }
    }

    // Запись голосового сообщения
    fun startRecording(isTorchOnIs: Int, subscribers: Set<Char>) {
        scope.launch {
            // Проверяем, идет ли уже запись
            if (isRecordingLocal) {
                mainRepository.speakText("Запись уже идет. Пожалуйста, остановите текущую запись перед началом новой.")
                return@launch
            }

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }

            if (isTorchOnIs == 111 && subscribers.size > 1) {
                mainRepository.speakText("Доступны $subscribers кому отправить сообщение?")
                delay(10000)

                val input = mainRepository.getInput()
                val selectedSubscriber = input?.toIntOrNull()

                if (selectedSubscriber == null) {
                    mainRepository.speakText("Вы ничего не ввели")
                    return@launch
                } else {
                    // Проверяем, доступен ли введенный адресат
                    if (subscribers.contains(selectedSubscriber.toString().first())) {
                        mainRepository.speakText("Сообщение получит абонент номер $selectedSubscriber, говорите")
                        mainRepository.setInput("")
                        mainRepository.setSelectedSubscriberNumber(selectedSubscriber)
                        delay(5000)
                    } else {
                        mainRepository.speakText("Абонент номер $selectedSubscriber не доступен")
                        mainRepository.setInput("")
                        return@launch
                    }
                }

            } else {
                val noteCount = recordedFiles.size
                if (noteCount == 0) {
                    mainRepository.speakText("Голосовая запись номер один, можете говорить")
                } else {
                    mainRepository.speakText("Голосовая запись номер ${noteCount + 1}, можете говорить")
                }
                delay(8000) // при 5 сек в запись попадают слова можете говорить если нет полной развязки между входом и выходом смартфона
            }

            availableMB = getAvailableMemoryInMB()
            if (availableMB < 5) {
                mainRepository.speakText("В памяти нет места для записи, осталось всего 5 мегабайт. Освободите память")
                return@launch
            }

            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (bufferSize <= 0) {
                return@launch
            }

            // Временный файл для записи — имя окончательно сформируем при остановке
            recordedFilePath = File(
                context.getExternalFilesDir(null),
                "temp_record.pcm"
            ).absolutePath

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return@launch
            }

            audioRecord?.startRecording()
            mainRepository.setIsRecording(true)
            isRecordingLocal = true  // Устанавливаем локальный флаг на true
            val buffer = ShortArray(bufferSize)

            FileOutputStream(recordedFilePath).use { fos ->
                while (isRecordingLocal) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readSize > 0) {
                        val byteBuffer = ByteArray(readSize * 2)
                        for (i in 0 until readSize) {
                            val amplifiedSample = (buffer[i] * 4).coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt()
                            )
                            byteBuffer[i * 2] = (amplifiedSample and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = (amplifiedSample shr 8 and 0xFF).toByte()
                        }
                        fos.write(byteBuffer)
                    } else if (readSize < 0) {
                        mainRepository.speakText("Ошибка чтения с буфера")
                        break
                    }
                }
            }
        }
    }

    // Функция для остановки записи
    fun stopRecording(isTorchOnIs: Int, subscribers: Set<Char>, pruning: Int) {
        scope.launch {

            try {
                if (isRecordingLocal) {
                    isRecordingLocal = false

                    // Останавливаем и освобождаем AudioRecord
                    audioRecord?.let {
                        try {
                            it.stop()
                        } catch (e: IllegalStateException) {
                            mainRepository.speakText("Ошибка при остановке записи")
                        } finally {
                            it.release()
                            audioRecord = null
                            mainRepository.setIsRecording(false)
                        }
                    } ?: mainRepository.speakText("Аудио рекорд не инициализирован")

                    // Формирование окончательного имени файла и переименование
                    val fileName =
                        "deletesec:${pruning}_selective:${isTorchOnIs}_registers:${subscribers}_adresat:${mainRepository.getSelectedSubscriberNumber()}_time:${System.currentTimeMillis()}.pcm"
                    val finalFile = File(context.getExternalFilesDir(null), fileName)
                    val tempFile = File(recordedFilePath!!)
                    if (tempFile.exists()) {
                        tempFile.renameTo(finalFile)
                        recordedFilePath = finalFile.absolutePath
                        recordedFiles.add(recordedFilePath!!)
                    }

                    // Дополнительная логика для обработки сообщений
                    if (isTorchOnIs == 111 && subscribers.size > 1) {
                        val message = if (availableMB < 100) {
                            "Запись отправлена абоненту с номером ${mainRepository.getSelectedSubscriberNumber()}. Заканчивается память, осталось $availableMB мегабайт"
                        } else {
                            "Запись отправлена абоненту с номером ${mainRepository.getSelectedSubscriberNumber()}"
                        }
                        mainRepository.speakText(message)
                    } else {
                        val message = if (availableMB < 100) {
                            "Запись сохранена. Заканчивается память, осталось $availableMB мегабайт"
                        } else {
                            "Запись сохранена."
                        }
                        mainRepository.speakText(message)
                    }

                    delay(10000)
                    when (mainRepository.getSelectedSubscriberNumber()) {
                        1 -> {
                            mainRepository.setFrequencyCtcss(203.5)
                            mainRepository.speakText("Первый, Вам поступило голосовое сообщение")
                        }
                        2 -> {
                            mainRepository.setFrequencyCtcss(218.1)
                            mainRepository.speakText("Второй, Вам поступило голосовое сообщение")
                        }
                        3 -> {
                            mainRepository.setFrequencyCtcss(233.6)
                            mainRepository.speakText("Третий, Вам поступило голосовое сообщение")
                        }
                        4 -> {
                            mainRepository.setFrequencyCtcss(250.3)
                            mainRepository.speakText("Четвертый, Вам поступило голосовое сообщение")
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                mainRepository.speakText("Ошибка, не удалось остановить запись")
            } catch (e: Exception) {
                mainRepository.speakText("Ошибка, остановки записи")
            }
        }
    }

    // Функция для получения доступной памяти в мегабайтах
    fun getAvailableMemoryInMB(): Long {
        val storageStat = StatFs(context.getExternalFilesDir(null)?.absolutePath)
        val availableBytes = storageStat.availableBlocksLong * storageStat.blockSizeLong
        val availableMB = availableBytes / (1024 * 1024) // Преобразуем байты в мегабайты
        return availableMB
    }

    // Функция для воспроизведения записанного файла
    fun playRecordedFile(isTorchOnIs: Int, subscribers: Set<Char>, abonent: Int) {
        scope.launch {

            // 1) Проигрываем CTCSS, если надо
            if (mainRepository.getFrequencyCtcss() != 0.0) {
                playCTCSS(
                    mainRepository.getFrequencyCtcss(),
                    mainRepository.getVolumeLevelCtcss(),
                    mainRepository.getPeriodCtcss(),
                    mainRepository.getDurationCtcss()
                )
            }

            // 2) Собираем нужный список файлов для воспроизведения
            val files = when {
                // прямой абонент 1..4
                abonent in 1..4 -> recordedFiles.filter { it.contains("adresat:$abonent") }

                // селективный вызов с адресатом
                isTorchOnIs == 111 && subscribers.size > 1 -> {
                    val sel = mainRepository.getSelectedSubscriberNumber()
                    recordedFiles.filter {
                        it.contains("selective:111") &&
                                it.contains("adresat:$sel")
                    }
                }

                // селективный вызов без адресата
                else -> recordedFiles.filter { it.contains("selective:5") }
            }

            // 3) Если нет файлов — говорим об этом и выходим
            if (files.isEmpty()) {
                val msg = when {
                    abonent in 1..4 ->
                        "${getOrdinalNumber(abonent)}. У вас нет входящих сообщений"

                    isTorchOnIs == 111 && subscribers.size > 1 ->
                        "${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}. У вас нет входящих сообщений"

                    else ->
                        "У вас нет входящих сообщений"
                }
                mainRepository.speakText(msg)
                return@launch
            }

            // Вспомогательная функция для извлечения deleteSec из имени
            fun extractPruning(path: String): Int {
                return Regex("deletesec:(\\d+)").find(File(path).name)
                    ?.groupValues?.get(1)?.toInt() ?: 0
            }

            // Вспомогательная функция: взять файл по индексу, извлечь deleteSec и запустить плеер
            fun playByIndex(idx: Int) {
                val file = files[idx]
                val pruning = extractPruning(file)
                playAudioFile(file, pruning)
            }

            // 4) Если ровно один файл — сразу его воспроизводим
            if (files.size == 1) {
                playByIndex(0)
                return@launch
            }

            // 5) Если файлов больше одного — спрашиваем у пользователя, какой выбрать
            val count = files.size
            val countText = if (count == 2) "две" else count.toString()
            val prompt = when {
                isTorchOnIs == 111 && subscribers.size > 1 ->
                    "${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}, у вас $countText сообщений. Какое из них воспроизвести?"

                else ->
                    "Какую запись требуется воспроизвести? Всего их $countText"
            }
            mainRepository.speakText(prompt)

            // Время на ответ пользователя
            delay(
                if (isTorchOnIs == 111 && subscribers.size > 1)
                    15_000L
                else
                    11_000L
            )

            val inputIdx = mainRepository.getInput()?.toIntOrNull()?.minus(1)
            if (inputIdx == null || inputIdx !in 0 until count) {
                mainRepository.speakText("Записи с таким номером нет")
                mainRepository.setInput("")
                return@launch
            }

            // Воспроизводим выбранный файл
            playByIndex(inputIdx)
            mainRepository.setInput("")
        }
    }

    // Вспомогательная функция для воспроизведения аудиофайла
    private fun playAudioFile(path: String, deletesec: Int) {

        if (mainRepository.getFrequencyCtcss() != 0.0) {
            playCTCSS(mainRepository.getFrequencyCtcss(), mainRepository.getVolumeLevelCtcss(), mainRepository.getPeriodCtcss(),
                mainRepository.getDurationCtcss())
        }

        val file = File(path)

        if (file.exists()) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO) // Моно
                .setSampleRate(44100) // Частота дискретизации
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT) // 16 бит
                .build()

            val audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE // Генерация ID сессии
            )

            audioTrack.setVolume(1.0f)

            FileInputStream(file).use { fis ->
                val buffer = ByteArray(1024)
                val byteArrayOutputStream = ByteArrayOutputStream()
                var bytesRead: Int

                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead)
                }

                val audioData = byteArrayOutputStream.toByteArray()

                val bytesPerSecond = audioFormat.sampleRate * 2
                // Обрезаем трек с конца на время указанное в deletesec
                val trimBytes = (deletesec * bytesPerSecond) / 1000

                val trimmedAudioData = if (audioData.size > trimBytes) {
                    audioData.copyOf(audioData.size - trimBytes)
                } else {
                    audioData
                }

                audioTrack.play()
                audioTrack.write(trimmedAudioData, 0, trimmedAudioData.size)
            }

            audioTrack.stop()
            audioTrack.release()
            stopPlayback()

        } else {
            mainRepository.speakText("Нет записанных файлов")
        }
    }

    // Функция для удаления записанного файла
    fun deleteRecordedFile(isTorchOnIs: Int, subscribers: Set<Char>) {
        scope.launch {
            if (isTorchOnIs == 111 && subscribers.size > 1) {
                // Подсчитываем количество сообщений, адресованных конкретному абоненту
                val subscriberFiles = recordedFiles.filter { file ->
                    file.contains("selective:111") && file.contains("adresat:${mainRepository.getSelectedSubscriberNumber()}")
                }

                val specificNoteCount = subscriberFiles.size

                if (specificNoteCount == 0) {
                    mainRepository.speakText("${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}. Нет данных для удаления")
                    mainRepository.setInput("")
                    return@launch
                } else {
                    val countText = if (specificNoteCount == 1) "одно" else specificNoteCount.toString()
                    mainRepository.speakText("${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}, у вас $countText сообщения. Какое из них требуется удалить? Введите 0 для удаления всех.")
                    delay(15000)
                }

                // Получаем индекс записи для удаления
                val input = mainRepository.getInput()?.toIntOrNull()
                if (input == 0) {
                    // Удаляем все записи, адресованные конкретному абоненту
                    subscriberFiles.forEach { filePathToDelete ->
                        val fileToDelete = File(filePathToDelete)
                        if (fileToDelete.exists() && fileToDelete.delete()) {
                            recordedFiles.remove(filePathToDelete) // Удаляем запись из списка
                        }
                    }
                    mainRepository.speakText("Все записи успешно удалены")
                    mainRepository.setInput("")
                    return@launch
                }

                val index = input?.minus(1)

                if (index == null || index < 0 || index >= subscriberFiles.size) {
                    mainRepository.speakText("Записи с таким номером нет")
                    mainRepository.setInput("")
                    return@launch
                }

                // Удаляем конкретную запись, адресованную конкретному абоненту
                val filePathToDelete = subscriberFiles[index]
                val fileToDelete = File(filePathToDelete)
                if (fileToDelete.exists() && fileToDelete.delete()) {
                    mainRepository.speakText("Запись успешно удалена")
                    recordedFiles.remove(filePathToDelete) // Удаляем запись из списка
                } else {
                    mainRepository.speakText("Не удалось удалить запись")
                }

                mainRepository.setInput("")
            } else {
                // Подсчитываем количество сообщений, созданных при отключенном селективном вызове
                val subscriberFiles = recordedFiles.filter { file ->
                    file.contains("selective:5")
                }

                val specificNoteCount = subscriberFiles.size

                if (specificNoteCount == 0) {
                    mainRepository.speakText("Нет данных для удаления")
                    mainRepository.setInput("")
                    return@launch
                } else {
                    val countText = when (specificNoteCount) {
                        1 -> "одна"
                        2 -> "две"
                        else -> specificNoteCount.toString() // Для остальных случаев просто возвращаем число как строку
                    }
                    mainRepository.speakText("Какую запись требуется удалить? Всего их $countText. Введите 0 для удаления всех.")
                    delay(15000)
                }

                // Получаем индекс записи для удаления
                val input = mainRepository.getInput()?.toIntOrNull()
                if (input == 0) {
                    // Удаляем все записи
                    subscriberFiles.forEach { filePathToDelete ->
                        val fileToDelete = File(filePathToDelete)
                        if (fileToDelete.exists() && fileToDelete.delete()) {
                            recordedFiles.remove(filePathToDelete) // Удаляем запись из списка
                        }
                    }
                    mainRepository.speakText("Все записи успешно удалены")
                    mainRepository.setInput("")
                    return@launch
                }

                val index = input?.minus(1)

                if (index == null || index < 0 || index >= subscriberFiles.size) {
                    mainRepository.speakText("Записи с таким номером нет")
                    mainRepository.setInput("")
                    return@launch
                }

                // Удаляем выбранное сообщение
                val filePathToDelete = subscriberFiles[index]
                val fileToDelete = File(filePathToDelete)
                if (fileToDelete.exists() && fileToDelete.delete()) {
                    recordedFiles.removeAt(index) // Удаляем запись из списка
                    mainRepository.speakText("Запись успешно удалена")
                } else {
                    mainRepository.speakText("Не удалось удалить запись")
                }

                mainRepository.setInput("")
            }
        }
    }

    fun exportAllRecordsToPublicDirectory() {
        scope.launch(Dispatchers.IO) {
            // 1. Получаем доступ к папке с PCM файлами
            val internalDir = context.getExternalFilesDir(null)
            if (internalDir == null || !internalDir.exists()) {
                withContext(Dispatchers.Main) {
                    mainRepository.speakText("Ошибка. Внутренняя папка не найдена.")
                }
                return@launch
            }

            // Ищем все PCM файлы, игнорируя регистр (pcm или PCM)
            val pcmFiles = internalDir.listFiles { _, name ->
                name.lowercase().endsWith(".pcm")
            }?.sortedBy { it.lastModified() } // Сортируем от старых к новым

            if (pcmFiles.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    mainRepository.speakText("Нет записанных файлов для выгрузки.")
                }
                return@launch
            }

            // 2. Озвучка начала процесса
            withContext(Dispatchers.Main) {
                mainRepository.speakText("Найдено ${pcmFiles.size} записей. Начинаю выгрузку в папку загрузки, пожалуйста подождите.")
            }

            // Пауза, чтобы голос успел проговорить начало
            delay(4000)

            // 3. Подготовка папки назначения
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportDir = File(downloadsDir, "DTMF_Records")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            // Формат даты: 29.12.2025_08.34 (двоеточия заменены на точки для совместимости)
            val dateFormat = SimpleDateFormat("dd.MM.yyyy_HH.mm", Locale.getDefault())
            var successCount = 0

            // 4. Цикл конвертации
            pcmFiles.forEachIndexed { index, pcmFile ->
                val dateStr = dateFormat.format(Date(pcmFile.lastModified()))

                // Формируем имя: Запись_001_29.12.2025_08.34.m4a
                val formattedIndex = String.format("%03d", index + 1)
                val newName = "Запись_${formattedIndex}_$dateStr.m4a"
                val outFile = File(exportDir, newName)

                try {
                    // Вызов функции кодирования (она ниже)
                    encodePcmToAac(pcmFile, outFile)
                    successCount++
                    Log.d("EXPORT", "Файл готов: $newName")
                } catch (e: Exception) {
                    Log.e("EXPORT", "Ошибка при обработке ${pcmFile.name}: ${e.message}")
                }
            }

            // 5. Финальная озвучка результата
            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    mainRepository.speakText("Выгрузка завершена успешно. Перенесено $successCount файлов. Проверьте папку Ди Ти Эм Эф Рекордс в загрузках.")
                } else {
                    mainRepository.speakText("Произошла ошибка. Файлы найдены, но не были сконвертированы.")
                }
            }
        }
    }

    /**
     * Вспомогательная функция кодирования PCM в AAC (M4A)
     */
    private fun encodePcmToAac(pcmFile: File, outFile: File) {
        val sampleRate = 44100
        val bitRate = 64000
        val timeoutUs = 10000L

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 10)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        FileInputStream(pcmFile).use { fis ->
            FileOutputStream(outFile).use { fos ->
                val bufferInfo = MediaCodec.BufferInfo()
                var isDone = false

                while (!isDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()

                        val tempBuffer = ByteArray(inputBuffer?.capacity() ?: 1024)
                        val read = fis.read(tempBuffer)

                        if (read == -1) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            inputBuffer?.put(tempBuffer, 0, read)
                            codec.queueInputBuffer(inputBufferIndex, 0, read, 0, 0)
                        }
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    while (outputBufferIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isDone = true
                        }

                        val outBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            val outSize = bufferInfo.size
                            val outPacketSize = outSize + 7
                            val adtsHeader = ByteArray(7)

                            // Добавляем ADTS заголовок, чтобы плееры видели файл
                            addADTStoPacket(adtsHeader, outPacketSize)

                            fos.write(adtsHeader)
                            val chunk = ByteArray(outSize)
                            outBuffer.get(chunk)
                            fos.write(chunk)
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    }
                }
            }
        }
        codec.stop()
        codec.release()
    }

    /**
     * ADTS заголовок для AAC потока
     */
    private fun addADTStoPacket(packet: ByteArray, packetLen: Int) {
        val profile = 2 // AAC LC
        val freqIdx = 4 // 44100Hz
        val chanCfg = 1 // Mono

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    // Вспомогательная функция для преобразования номера абонента в текстовое представление
    private fun getOrdinalNumber(number: Int): String {
        return when (number) {
            1 -> "первый"
            2 -> "второй"
            3 -> "третий"
            4 -> "четвертый"
            else -> "$number" // Если число больше 4, просто возвращаем его как строку
        }
    }

    // Логика обработки основных режимов навигации по помощи, контактам, смс, GPS
    fun handleNavigationCommand(delta: Int) {
        // --- 1. ЛОГИКА РЕЖИМА SMS ---
        if (isSmsMode) {
            when (delta) {
                // 1. ПОВТОР (Кнопка 4)
                4 -> {
                    if (smsNavigationIndex != -1 && totalSmsCount > 0) {
                        val (smsText, _) = getIncomingSmsByIndex(context, smsNavigationIndex)
                        mainRepository.speakText(smsText)
                    } else if (smsNavigationIndex == -1) {
                        mainRepository.speakText("Сначала выберите сообщение кнопками один или семь.")
                        mainRepository.setInput("")
                    }
                }

                // 2. УДАЛЕНИЕ ВСЕХ (Кнопка 0)
                0 -> {
                    if (smsNavigationIndex != -1) {
                        deleteAllSms(context)
                        isSmsMode = false
                        smsNavigationIndex = -1
                    } else {
                        mainRepository.speakText("Сначала выберите сообщение.")
                        mainRepository.setInput("")
                    }
                }

                // 3. УДАЛЕНИЕ ТЕКУЩЕГО (Кнопка 5)
                5 -> {
                    if (smsNavigationIndex != -1 && totalSmsCount > 0) {
                        val isDeleted = deleteSmsByIndex(context, smsNavigationIndex)
                        if (isDeleted) {
                            mainRepository.speakText("Текущее сообщение было удалено.")
                            mainRepository.setInput("")
                            val (_, newCount) = getIncomingSmsByIndex(context, 0)
                            totalSmsCount = newCount

                            if (totalSmsCount == 0) {
                                isSmsMode = false
                                smsNavigationIndex = -1
                                mainRepository.speakText("Сообщений больше нет.")
                                mainRepository.setInput("")
                            } else if (smsNavigationIndex >= totalSmsCount) {
                                smsNavigationIndex = totalSmsCount - 1
                            }
                        } else {
                            mainRepository.speakText("Ошибка удаления.")
                            mainRepository.setInput("")
                        }
                    } else {
                        mainRepository.speakText("Сначала выберите сообщение.")
                        mainRepository.setInput("")
                    }
                }

                6 -> {
                    mainRepository.speakText("Команда не назначена")
                }

                // 4. НАВИГАЦИЯ (7, 1, 8, 2, 9, 3)
                7, 1, 8, 2, 9, 3 -> {
                    if (totalSmsCount <= 0) {
                        mainRepository.speakText("Сообщения отсутствуют.")
                        mainRepository.setInput("")
                        isSmsMode = false
                    } else {
                        if (smsNavigationIndex == -1) {
                            smsNavigationIndex = 0
                        } else {
                            val step = when (delta) {
                                1 -> -1; 7 -> 1; 2 -> -10; 8 -> 10; 3 -> -100; 9 -> 100; else -> 0
                            }
                            val nextIndex = smsNavigationIndex + step

                            if (nextIndex < 0) {
                                mainRepository.speakText("Выше по списку сообщений нет. Это самое новое.")
                                mainRepository.setInput("")
                            } else if (nextIndex >= totalSmsCount) {
                                mainRepository.speakText("Ниже по списку сообщений нет. Это самое старое.")
                                mainRepository.setInput("")
                            } else {
                                smsNavigationIndex = nextIndex
                            }
                        }
                        val (smsText, _) = getIncomingSmsByIndex(context, smsNavigationIndex)
                        mainRepository.speakText(smsText)
                    }
                }
            }
            return // Завершаем обработку для режима SMS
        }

        // --- 2. ЛОГИКА РЕЖИМА КОНТАКТОВ ---
        else if (isContactMode) {
            when (delta) {
                // Озвучка номера контакта (Кнопка 4)
                4 -> {
                    if (mainRepository.getInput() != "") {
                        mainRepository.speakText(numberToText(mainRepository.getInput().toString()))
                    } else {
                        mainRepository.speakText("Нет выбранного контакта")
                        mainRepository.setInput("")
                    }
                }

                // Удаление контакта (Кнопка 5)
                5 -> {
                    val currentInput = mainRepository.getInput()
                    if (currentInput.isNullOrEmpty()) {
                        mainRepository.speakText("Для удаления контакта сначала выберите его из списка")
                    } else if (currentContactIndex >= 0 && currentContactIndex < contacts.size) {
                        val contactName = contacts[currentContactIndex].first
                        if (deleteContactByName(contactName, context)) {
                            mainRepository.speakText("Контакт $contactName успешно удален")
                            previousContactsSize = contacts.size
                            contacts = contacts.filterNot { it.first == contactName }
                            if (contacts.isEmpty()) {
                                isContactMode = false
                            }
                            mainRepository.setInput("")
                        }
                    }
                }

                6, 0 -> {
                    mainRepository.speakText("Команда не назначена")
                }

                // Навигация по контактам (Кнопки 1, 7, 2, 8, 3, 9)
                1, 7, 2, 8, 3, 9 -> {
                    val currentContactsSize = contacts.size
                    if (currentContactsSize == 0) return // Выход из блока, если пусто

                    val step = when(delta) {
                        1 -> -1; 7 -> 1; 2 -> -10; 8 -> 10; 3 -> -100; 9 -> 100; else -> 0
                    }

                    val wasContactDeleted = (previousContactsSize > 0 && currentContactsSize == previousContactsSize - 1)
                    currentContactIndex = (currentContactIndex + step) % contacts.size

                    if (currentContactIndex < 0) {
                        currentContactIndex += contacts.size
                    }

                    val actualIndex = if (wasContactDeleted && step > 0) {
                        if (currentContactIndex > 0) currentContactIndex - 1 else 0
                    } else {
                        currentContactIndex
                    }

                    val contact = contacts[actualIndex]
                    mainRepository.speakText(contact.first)

                    if (contact.second.length > 11) {
                        mainRepository.speakText("Выбранный номер не помещается в стандартное поле")
                        mainRepository.setInput("")
                    } else {
                        mainRepository.setInput(contact.second)
                    }
                    previousContactsSize = currentContactsSize
                }
            }
        }

        // --- 3. ЛОГИКА РЕЖИМА ПОМОЩИ ---
        else if ((isHelpMode || isHelpMode1) && delta != 999) {
            if (sentences.isEmpty()) {
                isHelpMode = false
                isHelpMode1 = false
                return
            }
            val step = when(delta) {
                1 -> -1; 7 -> 1; 2 -> -10; 8 -> 10; 3 -> -100; 9 -> 100; else -> 0
            }
            currentSentenceIndex = (currentSentenceIndex + step) % sentences.size
            if (currentSentenceIndex < 0) {
                currentSentenceIndex += sentences.size
            }
            if (currentSentenceIndex >= 0 && currentSentenceIndex < sentences.size) {
                mainRepository.speakText(sentences[currentSentenceIndex])
                mainRepository.setInput("")
            }
        }

        // --- 4. ЛОГИКА РЕЖИМА GPS ---
        else if (isGpsMode) {
            when (delta) {
                1 -> { // Кнопка 1: КООРДИНАТЫ РЕПИТЕРА
                    val loc = lastCurrentLocation
                    if (loc != null) {
                        val latSpeech = formatCoordsForSpeech(loc.latitude)
                        val lonSpeech = formatCoordsForSpeech(loc.longitude)
                        mainRepository.speakText("Координаты репитера. Широта: $latSpeech. Долгота: $lonSpeech.")
                    } else {
                        mainRepository.speakText("Координаты еще не определены. Ждите.")
                    }
                }

                2 -> { // Кнопка 2: ПОВТОР АЗИМУТА
                    val azimuth = lastAzimuth
                    if (azimuth != null) {
                        mainRepository.speakText("Азимут на репитер $azimuth градусов. Направление на репитер $lastDirection.")
                    } else {
                        mainRepository.speakText("Азимут еще не получен. Выполните команду пять.")
                    }
                }

                3 -> { // Кнопка 3: ВЫСОТА
                    // Берем данные напрямую из переменной в utils
                    val loc = lastCurrentLocation
                    if (loc != null) {
                        mainRepository.speakText("Высота репитера: ${loc.altitude.toInt()} метров над уровнем моря.")
                    } else {
                        mainRepository.speakText("Данные о высоте еще неполучены.")
                    }
                }

                4 -> { // Кнопка 4: ПОВТОР ПОЛНОГО ОТЧЕТА
                    if (lastCalculationResult.isNotEmpty()) {
                        mainRepository.speakText(lastCalculationResult)
                    } else {
                        mainRepository.speakText("Предыдущих расчетов не найдено. Выполните команду пять.")
                    }
                    mainRepository.setInput("")
                }

                5 -> { // Кнопка 5: ВВОД ДАННЫХ АБОНЕНТА И РАСЧЕТ
                    // Берем данные напрямую из переменной в utils
                    val loc = lastCurrentLocation
                    if (loc == null) {
                        mainRepository.speakText("Координаты не получены. Спутники еще не зафиксированы.")
                    } else {
                        val accuracy = loc.accuracy.toInt()
                        val stateStatus = when {
                            accuracy <= 15 -> "Точность отличная, погрешность $accuracy метров."
                            accuracy <= 50 -> "Точность хорошая, погрешность $accuracy метров."
                            accuracy <= 150 -> "Точность средняя, погрешность $accuracy метров."
                            else -> "Точность низкая, погрешность более $accuracy метров."
                        }

                        if (accuracy > 300) {
                            mainRepository.speakText("$stateStatus Точность хуже 300 метров. Расчет отменен.")
                        } else {
                            mainRepository.speakText(stateStatus)
                            mainRepository.setInput("")
                            scope.launch {
                                delay(9000) // Пауза, чтобы дослушать статус точности
                                processAbonentCalculation(loc)
                            }
                        }
                    }
                }

                6 -> { // Кнопка 6: ПОГРЕШНОСТЬ (Accuracy)
                    // Берем данные напрямую из переменной в utils
                    val loc = lastCurrentLocation
                    if (loc != null) {
                        mainRepository.speakText("Погрешность измерения в горизонтальной плоскости: ${loc.accuracy.toInt()} метров.")
                    } else {
                        mainRepository.speakText("Погрешность не определена, ожидайте фиксации спутников.")
                    }
                }

                7 -> { // Кнопка 7: ЗАСЕЧКА
                    if (lastUserLatSpeech.isNotEmpty() && lastUserLonSpeech.isNotEmpty()) {
                        val latDouble = parseCoord(lastUserLatSpeech)
                        val lonDouble = parseCoord(lastUserLonSpeech)
                        if (latDouble != null && lonDouble != null) {
                            val latSpeech = formatCoordsForSpeech(latDouble)
                            val lonSpeech = formatCoordsForSpeech(lonDouble)
                            mainRepository.speakText("Проверка последних введенных координат. Широта: $latSpeech Долгота: $lonSpeech.")
                        } else {
                            mainRepository.speakText("Ошибка формата сохраненных координат.")
                        }
                    } else {
                        mainRepository.speakText("Последних введенных координат не обнаружено. Выполните команду пять.")
                    }
                }

                8 -> {
                    mainRepository.speakText("Команда не назначена")
                }

                9 -> {
                    val manual = """
        Инструкция по вводу. 
        Координаты вводятся цифрами.
        Для широты всегда вводите восемь цифр. Первые две станут градусами.
        Для долготы: если градусов меньше ста, вводите восемь цифр. 
        Если сто и более — вводите девять цифр. В этом случае первые три цифры станут градусами.
        Внимание: ожидается формат десятичные градусы. Не используйте форматы с минутами и секундами напрямую, это приведет к ошибке в десятки километров.
    """.trimIndent()
                    mainRepository.speakText(manual)
                }


                0 -> { // Кнопка 0: ХОЛОДНЫЙ СТАРТ
                    mainRepository.speakText("Принудительный сброс выполнен. Очистка кэша. Ожидайте поиск спутников.")

                    forceRefreshGps(context)

                    mainRepository.setInput("")

                    scope.launch {
                        delay(9000)
                        val startTime = System.currentTimeMillis()
                        var fixFound = false
                        while (!fixFound && (System.currentTimeMillis() - startTime) < 120000) {
                            val loc = lastCurrentLocation
                            if (loc != null && loc.accuracy < 50) {
                                mainRepository.speakText("Спутники найдены. Местоположение зафиксировано.")
                                fixFound = true
                                stopGps(context) // Выключаем для экономии
                            }
                        }
                        if (!fixFound) {
                            mainRepository.speakText("Таймаут поиска. Спутники не найдены.")
                            stopGps(context)
                        }
                    }
                }
            }
        }
    }

    // Вспомогательная функция для расчета (вынесена для чистоты кода)
    private suspend fun processAbonentCalculation(repeaterLoc: Location) {
        isGpsMode = false

        try {
            // ШАГ 1: Ввод широты
            mainRepository.speakText("Введите вашу широту. 8 цифр")
            mainRepository.setInput("")
            delay(30000)
            val inputLat = (mainRepository.getInput() ?: "").trim()
            val userLat = parseCoord(inputLat)

            if (userLat == null) {
                mainRepository.speakText("Ошибка ввода широты. Расчет отменен.")
                return
            }
            lastUserLatSpeech = inputLat

            // ШАГ 2: Ввод долготы
            mainRepository.speakText("Принято. Введите долготу. 8 или 9 цифр")
            mainRepository.setInput("")
            delay(30000)
            val inputLon = (mainRepository.getInput() ?: "").trim()
            val userLon = parseCoord(inputLon)

            if (userLon == null) {
                mainRepository.speakText("Ошибка ввода долготы. Расчет отменен.")
                return
            }
            lastUserLonSpeech = inputLon

            // ШАГ 3: Ввод высоты
            mainRepository.speakText("Принято. Введите вашу высоту. Четыре цифры.")
            mainRepository.setInput("")
            delay(20000)
            val inputAltStr = (mainRepository.getInput() ?: "").filter { it.isDigit() }

            if (inputAltStr.length != 4) {
                mainRepository.speakText("Ошибка. Введено ${inputAltStr.length} цифр вместо четырёх. Расчет отменен.")
                return
            }
            val userAlt = inputAltStr.toDoubleOrNull() ?: 0.0

            // --- ГЕОМЕТРИЧЕСКИЕ РАСЧЕТЫ ---

            val d = calculateDistance(repeaterLoc.latitude, repeaterLoc.longitude, userLat, userLon)
            val km = d.toInt()
            val m = ((d - km) * 1000).toInt()

            val azimuthVal = calculateAzimuth(userLat, userLon, repeaterLoc.latitude, repeaterLoc.longitude).toInt()

            val direction = when (azimuthVal) {
                in 0..22, in 338..360 -> "Север"
                in 23..67 -> "Северо-Восток"
                in 68..112 -> "Восток"
                in 113..157 -> "Юго-Восток"
                in 158..202 -> "Юг"
                in 203..247 -> "Юго-Запад"
                in 248..292 -> "Запад"
                in 293..337 -> "Северо-Запад"
                else -> "не определено"
            }

            val altDiff = (userAlt - repeaterLoc.altitude).toInt()
            val heightText = if (altDiff > 0) "Вы выше репитера на ${Math.abs(altDiff)} метров."
            else if (altDiff < 0) "Вы ниже репитера на ${Math.abs(altDiff)} метров."
            else "Вы на одной высоте с репитером."

            val distText = "${if (km > 0) "$km километров " else ""}${if (m > 0) "$m метров" else ""}"

            // --- СОХРАНЕНИЕ РЕЗУЛЬТАТОВ ---
            lastAzimuth = azimuthVal
            lastDirection = direction
            lastCalculationResult = "Расстояние от вашего местоположения до точки расположения репитера составляет $distText. Азимут $azimuthVal градусов. Направление $direction. $heightText"

            mainRepository.speakText(lastCalculationResult)

        } catch (e: Exception) {
            mainRepository.speakText("Произошла системная ошибка при расчете.")
        } finally {
            mainRepository.setInput("")
            isGpsMode = true
        }
    }

    private fun formatCoordsForSpeech(coord: Double): String {
        val s = String.format("%.6f", coord).replace(",", ".") // Гарантируем формат 00.000000
        val parts = s.split(".")
        val degrees = parts[0] // Целое (например, 42)
        val decimals = parts[1] // Дробь (например, 341700)

        // Разбиваем дробную часть на два блока по 3 цифры
        val block1 = decimals.substring(0, 3)
        val block2 = decimals.substring(3, 6)

        return "$degrees. запятая. $block1. $block2."
    }

    // Универсальный парсер:
    private fun parseCoord(raw: String): Double? {
        val clean = raw.filter { it.isDigit() }
        if (clean.length < 3) return null
        return try {
            val degreeLength = if (clean.length >= 9) 3 else 2
            val degrees = clean.substring(0, degreeLength)
            var fraction = clean.substring(degreeLength)
            while (fraction.length < 6) {
                fraction += "0"
            }
            val limitedFraction = fraction.substring(0, 6)
            (degrees + "." + limitedFraction).toDouble()
        } catch (e: Exception) {
            null
        }
    }

    // Отчет о перезапусках
    fun getSystemHealthStats(): String {
        val prefs = context.getSharedPreferences("dtmf_stats", Context.MODE_PRIVATE)
        val startTimeStr = prefs.getString("start_time", null) ?: return "Статистика пуста."

        val countLogic = prefs.getInt("count_logic", 0)
        val countSms = prefs.getInt("count_sms", 0)
        val countSystem = prefs.getInt("count_system", 0)
        val countPulse = prefs.getInt("count_pulse", 0)

        val sdf = SimpleDateFormat("HH:mm dd.MM.yyyy", Locale.getDefault())
        val startDate = try { sdf.parse(startTimeStr) } catch (e: Exception) { null } ?: return "Ошибка даты."

        val diffMinutes = (System.currentTimeMillis() - startDate.time) / (1000 * 60)
        val expectedPulsesByTime = diffMinutes / 30

        val legalTriggers = countSms + countSystem + maxOf(countPulse.toLong(), expectedPulsesByTime)

        // 1. Проверка на избыток (аномалии)
        val anomalies = if (countLogic > (legalTriggers + 5)) countLogic - legalTriggers else 0L

        // 2. Проверка на дефицит (засыпание)
        val isPulseDeficit = diffMinutes > 40 && countPulse < (expectedPulsesByTime * 0.7)

        // Формируем текст проблемы
        var healthStatus = ""

        // 1. Проверка на "левые" запуски
        if (anomalies > 5) {
            // Добавляем пробел в конце фразы
            healthStatus = "Обнаружено $anomalies перезапусков не из ресиверов. "
        }

        // 2. Проверка таймера (добавляется к первой фразе, если она есть)
        if (countPulse > (expectedPulsesByTime + 3)) {
            healthStatus += "Внимание: таймер перезапуска срабатывает аномально часто."
        } else if (isPulseDeficit) {
            val missing = expectedPulsesByTime - countPulse
            healthStatus += "Внимание: таймер перезапуска срабатывает аномально редко. Пропущено минимум $missing циклов."
        }

        // 3. Итоговый вывод
        // Если обе проверки промолчали — всё хорошо.
        val anomalyText = if (healthStatus.trim().isEmpty()) "Система стабильна." else healthStatus

        // РАСЧЕТ ВРЕМЕНИ (Дни, Часы, Минуты)
        val totalHours = diffMinutes / 60
        val days = totalHours / 24
        val hours = totalHours % 24
        val mins = diffMinutes % 60

        // Функция склонения дней
        fun getDaysString(n: Long): String {
            val lastDigit = n % 10
            val lastTwoDigits = n % 100
            return when {
                lastTwoDigits in 11..19 -> "$n дней"
                lastDigit == 1L -> "$n день"
                lastDigit in 2..4 -> "$n дня"
                else -> "$n дней"
            }
        }

        val daysText = if (days > 0) "${getDaysString(days)} " else ""

        return """
    Статистика работы системы.
    Старт произведен в $startTimeStr.
    Время непрерывной работы: $daysText$hours ч. $mins мин.
    Всего перезапусков: $countLogic.
    Из них по таймеру перезапуска: $countPulse (ожидалось $expectedPulsesByTime).
    по эс эм эс: $countSms.
    Восстановлений системой: $countSystem.
    $anomalyText
""".trimIndent()
    }

    fun resetStats() {
        val prefs = context.getSharedPreferences("dtmf_stats", Context.MODE_PRIVATE)
        val now = SimpleDateFormat("HH:mm dd.MM.yyyy", Locale.getDefault()).format(Date())

        prefs.edit()
            .clear() // Удаляет старые данные
            .putString("start_time", now) // Устанавливает новую точку отсчета времени
            .putInt("count_system", 0)
            .putInt("count_pulse", 0)
            .putInt("count_sms", 0)
            .putInt("count_logic", 0)
            .apply()
    }

    // Функция мониторинга возможности выполнить вызов для нахождения точки установки репитера
    fun checkCallStateSequence() {
        // На всякий случай отменяем предыдущий запуск, если он был
        monitorJob?.cancel()

        // Запускаем в индивидуальную корутину
        monitorJob = scope.launch {
            mainRepository.speakText("Поиск точки расположения репитера. Попытки выполнить вызов будут выполняться непрерывно")
            delay(11000)

            flagSimMonitor = true
            var currentSim = 0

            while (flagSimMonitor) {
                mainRepository.setInput(monitorNumber)
                mainRepository.setSim(currentSim)
                delay(500)
                DtmfService.callStart(context)

                for (i in 1..durationSeconds * 10) { // Опрос состояний
                    if (!flagSimMonitor) break
                    val currentState = mainRepository.getCallState()
                    callStates.add(currentState)
                    if (callStates.size >= 2) {
                        val lastIndex = callStates.size - 1
                        if (callStates[lastIndex - 1] == 1 && (callStates[lastIndex] == 7 || callStates[lastIndex] == 4)) break
                    }
                    delay(100)
                }

                var isSuccess = false
                for (i in 0 until callStates.size - 1) {
                    if (callStates[i] == 9 && callStates[i + 1] == 1) {
                        isSuccess = true
                        break
                    }
                }
                callStates.clear()

                if (isSuccess) {
                    if (mainRepository.getCall() != null) DtmfService.callEnd(context)
                    delay(5000)
                    if (flagSimMonitor) mainRepository.speakText("Попытка вызова с ${if (currentSim == 0) "первой" else "второй"} сим карты выполнена успешно")
                } else {
                    if (mainRepository.getCall() != null) DtmfService.callEnd(context)
                    if (flagSimMonitor) mainRepository.speakText("Попытка вызова с ${if (currentSim == 0) "первой" else "второй"} сим карты не удалась, переместитесь в другое место, здесь нет сигнала базовой станции")
                }

                mainRepository.setInput("")
                currentSim = if (currentSim == 0) 1 else 0

                // Если флаг уже сброшен, не ждем 40 секунд, а выходим сразу
                if (!flagSimMonitor) break

                delay(40000)
            }
        }
    }

    // Функции для подмены значений частот для двойного частотомера
    fun substituteFrequencyLow(frequency: Float): Float {
        return when (frequency) {
            703.125f -> 697.000f
            765.625f -> 770.000f
            859.375f -> 852.000f
            937.500f -> 941.000f
            else -> frequency
        }
    }

    fun substituteFrequencyHigh(frequency: Float): Float {
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
    fun formatVolumeLevel(volume: Double): String {
        val formattedVolume = String.format("%.2f", volume)
        return formattedVolume.replace(".", " ")
    }
}