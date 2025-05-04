package com.mcal.dtmf.recognizer

import com.mcal.dtmf.data.repositories.main.MainRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class StatelessRecognizer(private val spectrum: Spectrum) : KoinComponent {
    private val mainRepository: MainRepository by inject() // инжектирование переменной в репозиторий
    // Массив частот и соответствующих символов
    private val tones = listOf(
        Pair(45 to 77, '1'), // DTMF tone for key 1: 1209Hz, 697Hz, continuous
        Pair(45 to 86, '2'), // DTMF tone for key 2: 1336Hz, 697Hz, continuous
        Pair(45 to 95, '3'), // DTMF tone for key 3: 1477Hz, 697Hz, continuous
        Pair(49 to 77, '4'), // DTMF tone for key 4: 1209Hz, 770Hz, continuous
        Pair(49 to 86, '5'), // DTMF tone for key 5: 1336Hz, 770Hz, continuous
        Pair(49 to 95, '6'), // DTMF tone for key 6: 1477Hz, 770Hz, continuous
        Pair(55 to 77, '7'), // DTMF tone for key 7: 1209Hz, 852Hz, continuous
        Pair(55 to 86, '8'), // DTMF tone for key 8: 1336Hz, 852Hz, continuous
        Pair(55 to 95, '9'), // DTMF tone for key 9: 1477Hz, 852Hz, continuous
        Pair(60 to 77, '*'), // DTMF tone for key *: 1209Hz, 941Hz, continuous
        Pair(60 to 86, '0'), // DTMF tone for key 0: 1336Hz, 941Hz, continuous
        Pair(60 to 95, '#'), // DTMF tone for key #: 1477Hz, 941Hz, continuous
        Pair(45 to 106, 'A'), // DTMF tone for key A: 1633Hz, 697Hz, continuous
        Pair(49 to 106, 'B'), // DTMF tone for key B: 1633Hz, 770Hz, continuous
        Pair(55 to 106, 'C'), // DTMF tone for key C: 1633Hz, 852Hz, continuous
        Pair(60 to 106, 'D')  // DTMF tone for key D: 1633Hz, 941Hz, continuous
    )

    fun getRecognizedKey(): Char {
        val lowMax = getMax(0, 75)
        val highMax = getMax(75, 150)
        val outputFrequency = getMax(0, 500) * 15.625f
        mainRepository.setOutputFrequency(outputFrequency) //передача инжектированной переменной в репозиторий

//        Log.e("Контрольный лог", "Н: $lowMax")
//        Log.e("Контрольный лог", "В: $highMax")
        // Проверка на частоту 1000 Гц для определения первой радиостанций
        if (outputFrequency == 1000.0f) {
            return 'R'
        }

        // Проверка на частоту 1450 Гц для определения второй радиостанций
        if (outputFrequency == 1453.125f) {
            return 'S'
        }

        // Проверка на частоту 1750 Гц для определения третьей радиостанций
        if (outputFrequency == 1750.0f) {
            return 'T'
        }

        // Проверка на частоту 2100 Гц для определения четвертой радиостанций
        if (outputFrequency == 2093.75f) {
            return 'V'
        }

        val allMax = getMax(0, 150)
        if (allMax != lowMax && allMax != highMax) {
            return ' '
        }

        for (tone in tones) {
            val (lowFrequency, highFrequency) = tone.first
            if (matchFrequency(lowMax, lowFrequency) && matchFrequency(highMax, highFrequency)) {
                return tone.second
            }
        }
        return ' '
    }

    private fun getMax(start: Int, end: Int): Int {
        var max = 0
        var maxValue = 0.0
        for (i in start..end) {
            if (maxValue < spectrum[i]) {
                maxValue = spectrum[i]
                max = i
            }
        }
        return max
    }

    private fun matchFrequency(frequency: Int, frequencyPattern: Int): Boolean {
        return (frequency - frequencyPattern) * (frequency - frequencyPattern) < 4
    }
}