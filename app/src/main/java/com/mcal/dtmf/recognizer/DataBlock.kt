package com.mcal.dtmf.recognizer

class DataBlock(buffer: ShortArray, blockSize: Int, bufferReadSize: Int) {
    private var block: DoubleArray = DoubleArray(blockSize)

    init {
        for (i in 0 until minOf(blockSize, bufferReadSize)) {
            block[i] = buffer[i].toDouble()
        }
    }

    fun FFT(): Spectrum {
        return Spectrum(FFT.magnitudeSpectrum(block))
    }
}
