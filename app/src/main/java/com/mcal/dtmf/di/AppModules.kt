package com.mcal.dtmf.di

import android.speech.tts.TextToSpeech
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.data.repositories.main.MainRepositoryImpl
import com.mcal.dtmf.ui.main.MainViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

object AppModules {
    val modules: List<Module>
        get() = listOf(
            viewModelsModule,
            repositoriesModule,
            ttsModule
        )
}

private val viewModelsModule = module {
    factory { MainViewModel(mainRepository = get()) }
}

private val ttsModule = module {
    single { TextToSpeech(get(), null) }
}

private val repositoriesModule = module {
    single<MainRepository> { MainRepositoryImpl(context = get(), textToSpeech = get()) }
}