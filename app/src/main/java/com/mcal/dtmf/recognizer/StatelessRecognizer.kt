package com.mcal.dtmf.recognizer


import android.util.Log
import com.mcal.dtmf.data.repositories.main.MainRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


// главный массив по которому происходит сравнение частот и присвоение соответствующего символа
// Эти величины от 45 до 106 соответствуют вертикальным строкам отрисовки спектра
// в MainScreen участки попадающие в дтмф диапазон выделены синим
class StatelessRecognizer(private val spectrum: Spectrum): KoinComponent {
    private val mainRepository: MainRepository by inject() // инжектирование репозитория
    private val tones = mutableListOf(
        Tone(45, 77, '1'),// 45=697гц  77=1209гц
        Tone(45, 86, '2'),// 45=697гц  86=1336гц
        Tone(45, 95, '3'),// 45=697гц  95=1477гц
        Tone(49, 77, '4'),// 49=770гц  77=1209гц
        Tone(49, 86, '5'),// 49=770гц  86=1336гц
        Tone(49, 95, '6'),// 49=770гц  95=1477гц
        Tone(55, 77, '7'),// 55=852гц  77=1209гц
        Tone(55, 86, '8'),// 55=852гц  86=1336гц
        Tone(55, 95, '9'),// 55=852гц  95=1477гц

        Tone(60, 77, '*'),// 60=941гц  77=1209гц
        Tone(60, 86, '0'),// 60=941гц  86=1336гц
        Tone(60, 95, '#'),// 60=941гц  95=1477гц

        Tone(45, 106, 'A'),// 45=697гц  106=1633гц
        Tone(49, 106, 'B'),// 49=770гц  106=1633гц
        Tone(55, 106, 'C'),// 55=852гц  106=1633гц
        Tone(60, 106, 'D'),// 60=941гц  106=1633гц
    )

    fun getRecognizedKey(): Char {
        val lowFragment = SpectrumFragment(0, 75, spectrum)// фрагмент выходной частоты в диапазоне от 15гц до 1187гц
        val highFragment = SpectrumFragment(75, 150, spectrum
        )// фрагмент выходной частоты в диапазоне от 1187гц до 2330гц
        val fragment = SpectrumFragment(0, 500, spectrum)// весь спектр в диапазоне от 15гц до 7.8кгц
        val lowMax = lowFragment.getMax()
        val highMax = highFragment.getMax()
        val outputFrequency = fragment.getMax() * 15.625f
        // истинная выходная частота в диапазоне от 15гц до 7.8кгц с шагом 15.625гц
        mainRepository.setOutput1(outputFrequency) //передача инжектированной переменной в репозиторий
        val allSpectrum = SpectrumFragment(0, 150, spectrum)
        val max = allSpectrum.getMax()
        if (max != lowMax && max != highMax) {
            return ' '
        }

        for (t in tones) {
            if (t.match(lowMax, highMax)) {
                return t.key

            }
        }
        return ' '
    }
}

