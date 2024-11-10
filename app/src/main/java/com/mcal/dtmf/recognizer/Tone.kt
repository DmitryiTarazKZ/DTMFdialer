package com.mcal.dtmf.recognizer

class Tone(
    private val lowFrequency: Int,
    private val highFrequency: Int,
    val key: Char

) {
    fun isDistrict(districts: BooleanArray): Boolean {
        return match(lowFrequency, districts) && match(highFrequency, districts)
    }

    private fun match(frequency: Int, districts: BooleanArray): Boolean {
        for (i in frequency - FREQUENCY_DELTA..frequency + FREQUENCY_DELTA) {
            if (districts[i]) return true
        }
        return false
    }

    fun match(lowFrequency: Int, highFrequency: Int): Boolean {
        return matchFrequency(lowFrequency, this.lowFrequency) && matchFrequency(
            highFrequency,
            this.highFrequency
        )
    }

    private fun matchFrequency(frequency: Int, frequencyPattern: Int): Boolean {
        return (frequency - frequencyPattern) * (frequency - frequencyPattern) < FREQUENCY_DELTA * FREQUENCY_DELTA
    }

    companion object {
        private const val FREQUENCY_DELTA = 2
    }
}
