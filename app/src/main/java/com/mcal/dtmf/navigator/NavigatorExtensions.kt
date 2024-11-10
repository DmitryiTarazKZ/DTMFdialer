package com.mcal.dtmf.navigator

import cafe.adriel.voyager.navigator.Navigator

fun <T> Navigator.popWithResult(requestCode: Int, resultCode: Int, data: T) {
    val prev = if (items.size < 2) null else items[items.size - 2] as? AppScreen
    prev?.onResult(requestCode, resultCode, data)
    pop()
}
