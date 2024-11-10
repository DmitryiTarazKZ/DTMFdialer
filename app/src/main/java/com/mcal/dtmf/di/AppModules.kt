package com.mcal.dtmf.di

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
        )
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
        )
    }
}

private val repositoriesModule = module {
    single<MainRepository> {
        MainRepositoryImpl(
            context = get(),
            preferencesRepository = get()
        )
    }

    single<PreferencesRepository> {
        PreferencesRepositoryImpl(
            context = get()
        ) 
    }
}
