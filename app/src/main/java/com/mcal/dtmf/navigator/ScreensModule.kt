package com.mcal.dtmf.navigator

import cafe.adriel.voyager.core.registry.screenModule
import com.mcal.dtmf.ui.help.HelpScreen
import com.mcal.dtmf.ui.main.MainScreen
import com.mcal.dtmf.ui.preferences.PreferencesScreen

val mainScreenModule = screenModule {
    register<Screens.Main> {
        MainScreen()
    }
}

val preferencesScreenModule = screenModule {
    register<Screens.Settings> {
        PreferencesScreen()
    }
}

val helpScreenModule = screenModule {
    register<Screens.Help> {
        HelpScreen()
    }
}
