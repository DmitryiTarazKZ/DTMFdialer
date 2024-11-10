package com.mcal.dtmf.recognizer

import kotlin.math.*

object FFT {
    private var mRData: DoubleArray = DoubleArray(0)
    private var mIData: DoubleArray = DoubleArray(0)

    fun magnitudeSpectrum(realPart: DoubleArray): DoubleArray {
        val imaginaryPart = DoubleArray(realPart.size)
        forwardFFT(realPart, imaginaryPart)
        for (i in realPart.indices) {
            realPart[i] = sqrt(mRData[i] * mRData[i] + mIData[i] * mIData[i])
        }
        return realPart
    }

    //	 swap Zi with Zj
    private fun swapInt(i: Int, j: Int) {
        var tempr: Double
        val ti = i - 1
        val tj = j - 1
        tempr = mRData[tj]
        mRData[tj] = mRData[ti]
        mRData[ti] = tempr
        tempr = mIData[tj]
        mIData[tj] = mIData[ti]
        mIData[ti] = tempr
    }

    private fun bitReverse2() {
        /* bit reversal */
        val n = mRData.size
        var j = 1
        var k: Int
        for (i in 1 until n) {
            if (i < j) swapInt(i, j)
            k = n / 2
            while (k in 1..<j) {
                j -= k
                k /= 2
            }
            j += k
        }
    }

    private fun forwardFFT(inR: DoubleArray, inI: DoubleArray) {
        var id: Int

        var localN: Int
        var wtemp: Double
        var wjkR: Double
        var wjkI: Double
        var wjR: Double
        var wjI: Double
        var theta: Double
        var tempr: Double
        var tempi: Double

        val numBits: Int = (log10(inR.size.toDouble()) / log10(2.0)).roundToInt()

        // Усекать входные данные до степени двойки
        val n = 1 shl numBits
        var nby2: Int

        // Копируем переданные ссылки на переменные, которые будут использоваться внутри
        // процедуры и утилиты fft
        mRData = inR
        mIData = inI

        bitReverse2()
        for (m in 1..numBits) {
            // localN = 2^m;
            localN = 1 shl m

            nby2 = localN / 2
            wjkR = 1.0
            wjkI = 0.0

            theta = Math.PI / nby2

            // для рекурсивного вычисления синуса и косинуса
            wjR = cos(theta)
            wjI = -sin(theta)

            for (j in 0 until nby2) {
                // Это самый внутренний цикл Быстрого Преобразования Фурье
                // Любая оптимизация, которую можно провести здесь, даст результат
                // большие награды.
                var k = j
                while (k < n) {
                    id = k + nby2
                    tempr = wjkR * mRData[id] - wjkI * mIData[id]
                    tempi = wjkR * mIData[id] + wjkI * mRData[id]

                    // Zid = Zi -C
                    mRData[id] = mRData[k] - tempr
                    mIData[id] = mIData[k] - tempi
                    mRData[k] += tempr
                    mIData[k] += tempi
                    k += localN
                }

                // (eq 6.23) and (eq 6.24)
                wtemp = wjkR

                wjkR = wjR * wjkR - wjI * wjkI
                wjkI = wjR * wjkI + wjI * wtemp
            }
        }
    }
}
