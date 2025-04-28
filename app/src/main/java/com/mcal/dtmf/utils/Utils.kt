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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mcal.dtmf.data.repositories.main.MainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
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
    private var lastMissedCallNumber: String? = null
    private var lastMissedCallTime: String? = null
    private val requestCodeContactsPermission = 1
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var availableMB = 0L
    private var audioRecord1: AudioRecord? = null
    private var recordedFilePath: String? = null
    private val recordedFiles = mutableListOf<String>()

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
        mainRepository.speakText("Текущее время $hoursString $minutesString. Сегодня $dayOfWeek, $dayOfMonthString $month",false)
    }

    // Последний пропущенный вызов по команде 0*
    fun lastMissed(context: Context) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            mainRepository.speakText("Не получено разрешение на доступ к информации о последнем пропущенном вызове",false)
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
                        ,false)
                    mainRepository.setInput("")
                    mainRepository.setInput(number.replace("+7", "8"))
                } else {
                    mainRepository.speakText("Не найдено пропущенных вызовов.",false)
                }
            }
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
                            mainRepository.speakText("Вы ничего не произнесли.", false)
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
                mainRepository.speakText("Найден контакт $contactName. Нажмите звездочку для вызова или решетку для отмены",false) // Используем имя, как оно записано в телефонной книге
                mainRepository.setInput(contactNumber) // Устанавливаем номер для вызова
                delay(7000)
            }
        } else {
            scope.launch {
                mainRepository.setInput("")
                mainRepository.speakText(
                    "В телефонной книге нет абонента с именем $result. Вы можете добавить этого абонента в вашу телефонную книгу",
                    false
                )
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

    // Функция для получения последнего входящего СМС по команде 4*
    fun getLastIncomingSms(context: Context): String {
        val smsUri = Uri.parse("content://sms/inbox")
        val cursor = context.contentResolver.query(smsUri, null, null, null, "date DESC")
        cursor?.use {
            if (it.moveToFirst()) {
                val messageBody = it.getString(it.getColumnIndexOrThrow("body"))
                return convertToRussianText(messageBody)
            }
        }
        return "сообщения отсутствуют"
    }

    // Функция для отправки надиктованного сообщения СМС по команде 4 после набора номера
    fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
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
        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100) // 100 - громкость
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
    fun playCTCSS(frequency: Double, volumeLevelCtcss: Double) {
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

            scope.launch {
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
                        break
                    } else if (result == AudioTrack.ERROR) {
                        break
                    }
                }
            }
        }
    }

    // Oстановка генерации субтона
    fun stopPlayback() {
        if (isPlaying) {
            isPlaying = false
            audioTrack?.stop()
            // audioTrack?.release() // Не освобождайте, если хотите использовать его повторно
            // audioTrack = null // Не обнуляйте, если хотите использовать его повторно
        }
    }
    // Запись голосового сообщения
    fun startRecording(isTorchOnIs: Int, subscribers: Set<Char>) {
        scope.launch {
            // Проверяем, идет ли уже запись
            if (mainRepository.getIsRecording() == true) {
                mainRepository.speakText("Запись уже идет. Пожалуйста, остановите текущую запись перед началом новой.", false)
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
                mainRepository.speakText("Доступны ${subscribers} кому отправить сообщение?", false)
                delay(10000)

                val input = mainRepository.getInput()
                val selectedSubscriber = input?.toIntOrNull()

                if (selectedSubscriber == null) {
                    mainRepository.speakText("Вы ничего не ввели", false)
                    return@launch
                } else {
                    // Проверяем, доступен ли введенный адресат
                    if (subscribers.contains(selectedSubscriber.toString().first())) {
                        mainRepository.speakText("Сообщение получит абонент номер $selectedSubscriber, говорите", false)
                        mainRepository.setInput("")
                        mainRepository.setSelectedSubscriberNumber(selectedSubscriber)
                        delay(5000)
                    } else {
                        mainRepository.speakText("Абонент номер $selectedSubscriber не доступен", false)
                        mainRepository.setInput("")
                        return@launch
                    }
                }

            } else {
                val noteCount = recordedFiles.size
                if (noteCount == 0) {
                    mainRepository.speakText("Голосовая запись номер один, можете говорить", false)
                } else {
                    mainRepository.speakText("Голосовая запись номер ${noteCount + 1}, можете говорить", false)
                }
                delay(6000) // при 5 сек в запись попадают слова можете говорить если нет полной развязки между входом и выходом смартфона
            }

            availableMB = getAvailableMemoryInMB()
            if (availableMB < 5) {
                mainRepository.speakText("В памяти нет места для записи, осталось всего 5 мегабайт. Освободите память", false)
                return@launch
            }

            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (bufferSize <= 0) {
                return@launch
            }

            val fileName =
                "selective:${isTorchOnIs}_registers:${subscribers}_adresat:${mainRepository.getSelectedSubscriberNumber()}_time:${System.currentTimeMillis()}.pcm" // Сохраняем в формате PCM
            recordedFilePath = File(
                context.getExternalFilesDir(null),
                fileName
            ).absolutePath // Устанавливаем путь к файлу

            audioRecord1 = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord1?.state != AudioRecord.STATE_INITIALIZED) {
                return@launch
            }

            audioRecord1?.startRecording()
            mainRepository.setIsRecording(true)
            val buffer = ShortArray(bufferSize)

            FileOutputStream(recordedFilePath).use { fos ->
                while (mainRepository.getIsRecording() == true) {
                    val readSize = audioRecord1?.read(buffer, 0, buffer.size) ?: 0
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
    }

    // Функция для получения доступной памяти в мегабайтах
    fun getAvailableMemoryInMB(): Long {
        val storageStat = StatFs(context.getExternalFilesDir(null)?.absolutePath)
        val availableBytes = storageStat.availableBlocksLong * storageStat.blockSizeLong
        return availableBytes / (1024 * 1024) // Преобразуем байты в мегабайты
    }

    // Функция для остановки записи
    fun stopRecording(isTorchOnIs: Int, subscribers: Set<Char>) {
        scope.launch {
            try {
                if (mainRepository.getIsRecording() == true) {
                    audioRecord1?.stop()
                    audioRecord1?.release()
                    audioRecord1 = null
                    mainRepository.setIsRecording(false)

                    if (isTorchOnIs == 111  && subscribers.size > 1) {
                        val message = if (availableMB < 100) {
                            "Запись отправлена абоненту с номером ${mainRepository.getSelectedSubscriberNumber()}. Заканчивается память, осталось $availableMB мегабайт"
                        } else {
                            "Запись отправлена абоненту с номером ${mainRepository.getSelectedSubscriberNumber()}"
                        }
                        mainRepository.speakText(message, false)
                    } else {
                        val message = if (availableMB < 100) {
                            "Запись сохранена. Заканчивается память, осталось $availableMB мегабайт"
                        } else {
                            "Запись сохранена."
                        }
                        mainRepository.speakText(message, false)
                    }

                    delay(10000)
                    if (mainRepository.getSelectedSubscriberNumber() == 1) {
                        mainRepository.setFrequencyCtcss(203.5)
                        mainRepository.speakText("Первый, вам поступило голосовое сообщение", false)
                    }
                    if (mainRepository.getSelectedSubscriberNumber() == 2) {
                        mainRepository.setFrequencyCtcss(218.1)
                        mainRepository.speakText("Второй, вам поступило голосовое сообщение", false)
                    }
                    if (mainRepository.getSelectedSubscriberNumber() == 3) {
                        mainRepository.setFrequencyCtcss(233.6)
                        mainRepository.speakText("Третий, вам поступило голосовое сообщение", false)
                    }
                    if (mainRepository.getSelectedSubscriberNumber() == 4) {
                        mainRepository.setFrequencyCtcss(250.3)
                        mainRepository.speakText("Четвертый, вам поступило голосовое сообщение", false)
                    }
                }
            } catch (e: IllegalStateException) {
                mainRepository.speakText("Не удалось остановить запись", false)
            }
        }
    }

    // Функция для воспроизведения записанного файла
    fun playRecordedFile(isTorchOnIs: Int, subscribers: Set<Char>, abonent: Int) {
        scope.launch {

            if (mainRepository.getFrequencyCtcss() != 0.0) {
                playCTCSS(mainRepository.getFrequencyCtcss(), mainRepository.getVolumeLevelCtcss())
            }

            if (abonent in 1..4) {
                // Воспроизводим последнюю запись, адресованную конкретному абоненту
                val subscriberFiles = recordedFiles.filter { file ->
                    file.contains("adresat:$abonent")
                }

                if (subscriberFiles.isEmpty()) {
                    mainRepository.speakText("${getOrdinalNumber(abonent)}. У вас нет входящих сообщений", false)
                    return@launch
                } else {
                    // Воспроизводим последнюю запись
                    playAudioFile(subscriberFiles.last())
                    return@launch
                }
            }

            if (isTorchOnIs == 111 && subscribers.size > 1) {
                // Подсчитываем количество сообщений, адресованных конкретному абоненту
                val subscriberFiles = recordedFiles.filter { file ->
                     file.contains("selective:111") && file.contains("adresat:${mainRepository.getSelectedSubscriberNumber()}")
                }

                val specificNoteCount = subscriberFiles.size

                if (specificNoteCount == 0) {
                    mainRepository.speakText("${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}. У вас нет входящих сообщений", false)
                    return@launch
                } else if (specificNoteCount == 1) {
                    // Если только одно сообщение, воспроизводим его сразу
                    playAudioFile(subscriberFiles[0])
                    return@launch
                } else {
                    mainRepository.speakText("${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}, у вас $specificNoteCount сообщения. Какое из них требуется воспроизвести?", false)
                    delay(15000)
                }

                // Получаем индекс записи для воспроизведения
                val input = mainRepository.getInput()?.toIntOrNull()
                val index = input?.minus(1)

                if (index == null || index < 0 || index >= subscriberFiles.size) {
                    mainRepository.speakText("Сообщения с таким номером нет", false)
                    mainRepository.setInput("")
                    return@launch
                }
                playAudioFile(subscriberFiles[index])
                mainRepository.setInput("")
            } else {
                // Подсчитываем количество сообщений, созданных при отключенном селективнов вызове
                val subscriberFiles = recordedFiles.filter { file ->
                    file.contains("selective:0")
                }

                val specificNoteCount = subscriberFiles.size

                if (specificNoteCount == 0) {
                    mainRepository.speakText("У вас нет входящих сообщений", false)
                    return@launch
                } else if (specificNoteCount == 1) {
                    // Если только одно сообщение, воспроизводим его сразу
                    playAudioFile(recordedFiles[0])
                    return@launch
                } else {
                    val countText = if (specificNoteCount == 2) "две" else specificNoteCount.toString()
                    mainRepository.speakText("Какую запись требуется воспроизвести? Всего их $countText", false)
                    delay(15000)
                }

                // Получаем индекс записи для воспроизведения
                val index = mainRepository.getInput()?.toIntOrNull()?.minus(1)

                if (index == null || index < 0 || index >= recordedFiles.size) {
                    mainRepository.speakText("Записи с таким номером нет", false)
                    mainRepository.setInput("")
                    return@launch
                }

                // Воспроизводим выбранное сообщение
                playAudioFile(subscriberFiles[index])
                mainRepository.setInput("")
            }
        }
    }

    // Вспомогательная функция для воспроизведения аудиофайла
    private fun playAudioFile(path: String) {

        if (mainRepository.getFrequencyCtcss() != 0.0) {
            playCTCSS(mainRepository.getFrequencyCtcss(), mainRepository.getVolumeLevelCtcss())
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
                val trimBytes = (1000 * bytesPerSecond) / 1000 // Обрезаем указанное время в миллисекундах (1 значение)

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
            if (mainRepository.getFrequencyCtcss() != 0.0) {
                stopPlayback()
            }
        } else {
            mainRepository.speakText("Нет записанных файлов", false)
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
                    mainRepository.speakText("${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}. Нет данных для удаления", false)
                    mainRepository.setInput("")
                    return@launch
                } else {
                    val countText = if (specificNoteCount == 1) "одно" else specificNoteCount.toString()
                    mainRepository.speakText("${getOrdinalNumber(mainRepository.getSelectedSubscriberNumber())}, у вас $countText сообщения. Какое из них требуется удалить? Введите 0 для удаления всех.", false)
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
                    mainRepository.speakText("Все записи успешно удалены", false)
                    mainRepository.setInput("")
                    return@launch
                }

                val index = input?.minus(1)

                if (index == null || index < 0 || index >= subscriberFiles.size) {
                    mainRepository.speakText("Записи с таким номером нет", false)
                    mainRepository.setInput("")
                    return@launch
                }

                // Удаляем конкретную запись, адресованную конкретному абоненту
                val filePathToDelete = subscriberFiles[index]
                val fileToDelete = File(filePathToDelete)
                if (fileToDelete.exists() && fileToDelete.delete()) {
                    mainRepository.speakText("Запись успешно удалена", false)
                    recordedFiles.remove(filePathToDelete) // Удаляем запись из списка
                } else {
                    mainRepository.speakText("Не удалось удалить запись", false)
                }

                mainRepository.setInput("")
            } else {
                // Подсчитываем количество сообщений, созданных при отключенном селективном вызове
                val subscriberFiles = recordedFiles.filter { file ->
                    file.contains("selective:0")
                }

                val specificNoteCount = subscriberFiles.size

                if (specificNoteCount == 0) {
                    mainRepository.speakText("Нет данных для удаления", false)
                    mainRepository.setInput("")
                    return@launch
                } else {
                    val countText = when (specificNoteCount) {
                        1 -> "одна"
                        2 -> "две"
                        else -> specificNoteCount.toString() // Для остальных случаев просто возвращаем число как строку
                    }
                    mainRepository.speakText("Какую запись требуется удалить? Всего их $countText. Введите 0 для удаления всех.", false)
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
                    mainRepository.speakText("Все записи успешно удалены", false)
                    mainRepository.setInput("")
                    return@launch
                }

                val index = input?.minus(1)

                if (index == null || index < 0 || index >= subscriberFiles.size) {
                    mainRepository.speakText("Записи с таким номером нет", false)
                    mainRepository.setInput("")
                    return@launch
                }

                // Удаляем выбранное сообщение
                val filePathToDelete = subscriberFiles[index]
                val fileToDelete = File(filePathToDelete)
                if (fileToDelete.exists() && fileToDelete.delete()) {
                    recordedFiles.removeAt(index) // Удаляем запись из списка
                    mainRepository.speakText("Запись успешно удалена", false)
                } else {
                    mainRepository.speakText("Не удалось удалить запись", false)
                }

                mainRepository.setInput("")
            }
        }
    }

    // Вспомогательная функция для преобразования числа абонента в текстовое представление
    private fun getOrdinalNumber(number: Int): String {
        return when (number) {
            1 -> "первый"
            2 -> "второй"
            3 -> "третий"
            4 -> "четвертый"
            else -> "$number" // Если число больше 4, просто возвращаем его как строку
        }
    }
}