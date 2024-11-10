package com.mcal.dtmf.navigator

import cafe.adriel.voyager.core.registry.ScreenProvider

sealed class Screens : ScreenProvider {
    data class Handler(val error: String) : Screens()
    data object Main : Screens()
    data object Settings : Screens()
    data object Help : Screens()
}
