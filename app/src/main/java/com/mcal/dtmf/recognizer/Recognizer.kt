package com.mcal.dtmf.recognizer


class Recognizer {
    private var history = mutableListOf<Char>()
    private var actualValue = ' '

    init {
        clear()
    }

    fun clear() {
        history = mutableListOf()
        actualValue = ' '
    }

    fun getRecognizedKey(recognizedKey: Char): Char {
        history.add(recognizedKey)
        if (history.size <= 4) {
            return ' '
        }
        history.removeAt(0)
        var count = 0
        for (c in history) {
            if (c == recognizedKey) {
                count++
            }
        }
        if (count >= 3) {
            actualValue = recognizedKey
        }
        return actualValue
    }
}
