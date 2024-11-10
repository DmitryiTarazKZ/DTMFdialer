package com.mcal.dtmf.recognizer

class Spectrum(
    private val spectrum: DoubleArray
) {
    private val length = spectrum.size

    fun normalize() {
        var maxValue = 0.0
        for (i in 0 until length) {
            if (maxValue < spectrum[i]) {
                maxValue = spectrum[i]
            }
        }
        if (maxValue != 0.0) {
            for (i in 0 until length) {
                spectrum[i] /= maxValue
            }
        }
    }

    operator fun get(index: Int): Double {
        return spectrum[index]
    }

    fun length(): Int {
        return length
    }
}
