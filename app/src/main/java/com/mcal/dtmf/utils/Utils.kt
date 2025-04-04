package com.mcal.dtmf.utils

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.math.roundToInt

class Utils(
    private val mainRepository: MainRepository,
    private val scope: CoroutineScope
) {
    private var lastMissedCallNumber: String? = null
    private var lastMissedCallTime: String? = null
    private val REQUEST_CODE_CONTACTS_PERMISSION = 1

    companion object {

        // Получение данных о температуре и заряде батареи
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

        fun getCurrentBatteryVoltage(context: Context): String {
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

        var silenceDuration = 0 // Переменная для отслеживания времени молчания
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
    fun getContactNumberByName(name: String, context: Context): String? {
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

        // Вставляем новый контакт
        val rawContactUri = context.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, values)
        // Проверяем, был ли успешно добавлен контакт
        if (rawContactUri == null) {
            return false // Не удалось создать контакт
        }

        val rawContactId = ContentUris.parseId(rawContactUri)

        // Сохраняем имя контакта
        val nameValues = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, formattedName)
        }
        val nameUri = context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)

        if (nameUri == null) {
            return false // Не удалось сохранить имя контакта
        }

        // Сохраняем номер телефона
        val phoneValues = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        }
        val phoneUri = context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, phoneValues)
        if (phoneUri == null) {
            return false
        }
        return true
    }

    // Функция для удаления контакта из телефонной книги по имени
    fun deleteContactByName(result: String, context: Context): Boolean {
        // Проверяем разрешение на доступ к контактам
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {

            // Запрашиваем разрешение
            ActivityCompat.requestPermissions(context as Activity,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                REQUEST_CODE_CONTACTS_PERMISSION)
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
    fun getLastIncomingSms(context: Context): String? {
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
                val lowerChar = char.toLowerCase() // Преобразуем символ в нижний регистр
                val transliteratedChar = transliterationMap[lowerChar] ?: lowerChar
                transliteratedChar
            }.joinToString("")
        }
        return convertedWords.joinToString(" ")
    }
}