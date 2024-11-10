package com.mcal.dtmf.recognizer

class SpectrumFragment(
    private val start: Int,
    private val end: Int,
    private val spectrum: Spectrum
) {
    fun getAverage(): Double {
        var sum = 0.0
        for (i in start..end) {
            sum += spectrum[i]
        }
        return sum / ((end - start).toDouble())
    }

    fun getDistricts(): BooleanArray {
        val average = getAverage()
        val ret = BooleanArray(spectrum.length())
        for (i in start..end) {
            if (spectrum[i] > (average * DISTINCT_FACTOR)) {
                ret[i] = true
            }
        }
        return ret
    }

    fun getMax(): Int {
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

    companion object {
        private const val DISTINCT_FACTOR = 2.0
    }
}
