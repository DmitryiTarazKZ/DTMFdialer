package com.mcal.dtmf.di

import android.speech.tts.TextToSpeech
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.data.repositories.main.MainRepositoryImpl
import com.mcal.dtmf.data.repositories.preferences.PreferencesRepository
import com.mcal.dtmf.data.repositories.preferences.PreferencesRepositoryImpl
import com.mcal.dtmf.ui.main.MainViewModel
import com.mcal.dtmf.ui.preferences.PreferencesViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

object AppModules : FeatureModule {
    override val modules: List<Module>
        get() = listOf(
            viewModelsModule,
            repositoriesModule,
            ttsModule
        )
}

private val ttsModule = module {
    single { TextToSpeech(get(), null) }
}

private val viewModelsModule = module {
    factory {
        MainViewModel(
            mainRepository = get(),
        )
    }

    factory {
        PreferencesViewModel(
            preferencesRepository = get(),
            mainRepository = get(),
        )
    }
}

private val repositoriesModule = module {
    single<PreferencesRepository> {
        PreferencesRepositoryImpl(
            context = get()
        )
    }

    single<MainRepository> {
        MainRepositoryImpl(
            context = get(),
            preferencesRepository = get(),
            textToSpeech = get()
        )
    }
}
