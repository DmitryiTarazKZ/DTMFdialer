package com.mcal.dtmf.navigator

import cafe.adriel.voyager.core.screen.Screen

interface AppScreen : Screen {
    companion object {
        const val RESULT_CANCELED: Int = 0
        const val RESULT_OK: Int = -1
    }

    fun <T> onResult(requestCode: Int, resultCode: Int, data: T) {}
}
