package com.mcal.dtmf.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.CellSignalStrength
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.mcal.dtmf.data.repositories.main.LogLevel
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.service.DtmfService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class Utils(
    private val mainRepository: MainRepository,
    var flagVoise: Boolean,
    private val scope: CoroutineScope
) {




    private var lastMissedCallNumber: String? = null
    private var lastMissedCallTime: String? = null

    companion object {

        // Получение данных о том подключенны наушники или нет
        fun headphoneReceiver(context: Context, callback: (Boolean) -> Unit) {
            val headphoneReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == AudioManager.ACTION_HEADSET_PLUG) {
                        val isConnected = intent.getIntExtra("state", 0) == 1

                        callback(isConnected)
                    }
                }
            }
            context.registerReceiver(headphoneReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
        }



        // получение данных о температуре и заряде батареи
        fun getCurrentBatteryTemperature(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                ((intent!!.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).toFloat()) / 10).toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }

        fun getCurrentBatteryLevel(context: Context): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return try {
                (intent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)).toFloat().toString()
            } catch (e: Exception) {
                "Не удалось получить данные"
            }
        }
    }

    // Дата и время по команде 2*

    fun speakCurrentTime() {
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
            0, 24 -> "ноль часов"
            else -> "$hours часов"
        }

// Форматируем строку для минут
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

        mainRepository.speakText("Текущее время $hoursString $minutesString. Сегодня $dayOfWeek, $dayOfMonthString $month.")
    }

    // Получения текущего уровня сигнала сотовой сети (от 0 до 4 где 0 отсутствие сигнала)
    fun getCurentCellLevel(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.listen(object : PhoneStateListener() {
                @Deprecated("Этот метод устарел")
                override fun onSignalStrengthsChanged(signalStrengths: SignalStrength) {
                    val signalStrength = signalStrengths.level
                    val simOperatorName = telephonyManager.simOperatorName
                    LogManager.logOnMain(LogLevel.INFO, "Уровень сети оператора $simOperatorName, по индикатору антены $signalStrength", mainRepository.getErrorControl())
                    val speechResource = when (signalStrength) {
                        0 -> "Полностью отсутствует. Вызов с этой сим карты невозможен"
                        1 -> "Низкий"
                        2 -> "Умеренный"
                        3 -> "Хороший"
                        4 -> "Отличный"
                        5 -> "Отличный" // Добавлено для обработки значения 5 на некоторых смартфонах
                        else -> "Полностью отсутствует. Вызов с этой сим карты невозможен"
                    }
                    mainRepository.speakText("Уровень сети оператора $simOperatorName, по индикатору антэны $speechResource")
                }
            }, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }
    }

    // Последний пропущенный вызов
    fun lastMissed(context: Context) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            mainRepository.speakText("Разрешение на чтение лога вызовов не получено")
            mainRepository.setInput("")
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
                    val contactName = getContactNameByNumber(number, context) ?: "имени которого нет в телефонной книге"
                    lastMissedCallNumber = contactName
                    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    lastMissedCallTime = dateFormat.format(Date(date))
                    mainRepository.speakText(
                        "Последний пропущенный вызов был от абонента $lastMissedCallNumber... он звонил в $lastMissedCallTime... " +
                                "Если Вы хотите перезвонить, нажмите звездочку. Для отмены нажмите решетку. Также Вы можете " +
                                "закрепить этот номер за одной из клавиш быстрого набора"
                    )
                    mainRepository.setInput("")
                    mainRepository.setInput(number.replace("+7", "8"))
                } else {
                    mainRepository.speakText("Не найдено пропущенных вызовов.")
                }
            }
    }

    // Функция проверки состояния батареи
    fun batteryStatus(context: Context) {
        val batteryTemperature = getCurrentBatteryTemperature(context).toDouble().roundToInt()
        val batteryLevel = getCurrentBatteryLevel(context).toDouble().roundToInt()

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
        mainRepository.speakText("Температура аккумулятора $temperatureText. Заряд аккумулятора $levelText")
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
                    LogManager.logOnMain(LogLevel.INFO, "isOnline() Получение интернета через мобильные данные", mainRepository.getErrorControl())
                    return true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    LogManager.logOnMain(LogLevel.INFO, "isOnline() Получение интернета через Wi-Fi", mainRepository.getErrorControl())
                    return true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    LogManager.logOnMain(LogLevel.INFO, "isOnline() Подключение через Ethernet", mainRepository.getErrorControl())
                    return true
                }
            }
        }
        LogManager.logOnMain(LogLevel.INFO, "isOnline() Нет подключения к интернету", mainRepository.getErrorControl())
        return false
    }

    // Функция голосового распознавания

    fun startSpeechRecognition(context: Context) {


        LogManager.logOnMain(LogLevel.INFO, "fun startSpeechRecognition() Начало распознавания речи", mainRepository.getErrorControl())

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
                LogManager.logOnMain(LogLevel.INFO, "onReadyForSpeech() Подготовка к распознаванию речи", mainRepository.getErrorControl())
            }

            override fun onBeginningOfSpeech() {
                LogManager.logOnMain(LogLevel.INFO, "onBeginningOfSpeech() Начало речи", mainRepository.getErrorControl())
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Уровень громкости
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Получение буфера
            }

            override fun onEndOfSpeech() {
                LogManager.logOnMain(LogLevel.INFO, "onEndOfSpeech() Конец речи", mainRepository.getErrorControl())
            }

            override fun onError(error: Int) {
                if (!mainRepository.getStartDtmf()) {
                    mainRepository.setStartDtmf(true)
                }
                when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> mainRepository.speakText("Время сетевой операции истекло")
                    SpeechRecognizer.ERROR_NETWORK -> mainRepository.speakText("Произошла ошибка сети")
                    SpeechRecognizer.ERROR_AUDIO -> mainRepository.speakText("Ошибка записи звука")
                    SpeechRecognizer.ERROR_SERVER -> mainRepository.speakText("Сервер отправил ошибку")
                    SpeechRecognizer.ERROR_CLIENT -> mainRepository.speakText("Ошибка на стороне клиента")
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> mainRepository.speakText("Нет входной речи")
                    SpeechRecognizer.ERROR_NO_MATCH -> mainRepository.speakText("Распознавание не дало совпадений")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> mainRepository.speakText("Распознаватель занят")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> mainRepository.speakText("Недостаточно разрешений")
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> mainRepository.speakText("Слишком много запросов от одного клиента")
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> mainRepository.speakText("Сервер был отключен")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> mainRepository.speakText("Запрашиваемый язык не поддерживается")
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> mainRepository.speakText("Запрашиваемый язык недоступен")
                    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> mainRepository.speakText("Невозможно проверить поддержку")
                    SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> mainRepository.speakText("Служба не поддерживает прослушивание событий загрузки")
                    else -> mainRepository.speakText("Произошла неизвестная ошибка")
                }
            }

            override fun onResults(results: Bundle?) {
                if (!mainRepository.getStartDtmf()) mainRepository.setStartDtmf(true)

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                LogManager.logOnMain(
                    LogLevel.INFO,
                    "onResults: Получены результаты распознавания речи",
                    mainRepository.getErrorControl()
                )

                if (matches.isNullOrEmpty()) {
                    LogManager.logOnMain(
                        LogLevel.WARNING,
                        "onResults: Нет распознанных результатов",
                        mainRepository.getErrorControl()
                    )
                    return // Выход из функции, если нет распознанных результатов
                }

                val recognizedText = matches[0]
                LogManager.logOnMain(
                    LogLevel.INFO,
                    "onResults: Распознанный текст - $recognizedText",
                    mainRepository.getErrorControl()
                )

                // Проверяем, является ли распознанный текст именем контакта
                val contactNumber = getContactNumberByName(recognizedText, context)
                if (contactNumber != null) {
                    scope.launch {
                        // Получаем имя контакта для озвучивания
                        val contactName = getContactNameByNumber(contactNumber, context)
                        LogManager.logOnMain(
                            LogLevel.INFO,
                            "onResults: Найден номер для контакта - $contactNumber, имя - $contactName",
                            mainRepository.getErrorControl()
                        )
                        mainRepository.setFlashlight(true)
                        mainRepository.speakText("Найден контакт $contactName") // Используем имя, как оно записано в телефонной книге
                        mainRepository.setInput(contactNumber) // Устанавливаем номер для вызова
                        delay(7000)
                        DtmfService.callStart(context) // Начинаем вызов
                    }
                } else {
                    LogManager.logOnMain(
                        LogLevel.WARNING,
                        "onResults: Абонент с именем $recognizedText не найден.",
                        mainRepository.getErrorControl()
                    )
                    mainRepository.speakText("В вашей телефонной книге нет абонента с именем $recognizedText")
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

    /// Блок распознавания речевой команды и поиска имени в книге контактов с последующим вызовом
    fun getContactNumberByName(name: String, context: Context): String? {
        val cr = context.contentResolver
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        var contactNumber: String? = null

        // Приводим имя к нижнему регистру и удаляем лишние пробелы
        val lowerCaseName = normalizeString(name)

        // Выполняем запрос к контактам с использованием LIKE
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
                // Если контакт не найден, пробуем найти с учетом нечеткого совпадения
                LogManager.logOnMain(LogLevel.INFO, "getContactNumberByName() Контакт не найден, пробуем нечеткое совпадение", mainRepository.getErrorControl())
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
                            LogManager.logOnMain(LogLevel.INFO, "getContactNumberByName() Найден номер телефона по нечеткому совпадению: $contactNumber", mainRepository.getErrorControl())
                            // Приводим номер к нужному формату
                            contactNumber = formatPhoneNumber(contactNumber)
                        } else {
                            LogManager.logOnMain(LogLevel.INFO, "getContactNumberByName() Контакт не найден по нечеткому совпадению", mainRepository.getErrorControl())
                        }
                    }
                } else {
                    LogManager.logOnMain(LogLevel.INFO, "getContactNumberByName() Не удалось найти контакт с нечетким совпадением", mainRepository.getErrorControl())
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
}