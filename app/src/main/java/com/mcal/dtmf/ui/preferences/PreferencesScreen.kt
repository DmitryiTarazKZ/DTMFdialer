package com.mcal.dtmf.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mcal.dtmf.composition.PreferencePhoneTextField
import com.mcal.dtmf.composition.PreferencePlusMinus
import com.mcal.dtmf.composition.PreferencePlusMinus1
import com.mcal.dtmf.composition.PreferencePlusMinus2
import com.mcal.dtmf.composition.PreferenceRadioButtonDialog
import com.mcal.dtmf.composition.PreferenceRadioButtonDialog1
import com.mcal.dtmf.composition.PreferenceSwitch
import com.mcal.dtmf.composition.PreferenceSwitch1
import com.mcal.dtmf.navigator.AppScreen


class PreferencesScreen : AppScreen {
    override val key: ScreenKey
        get() = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = getScreenModel<PreferencesViewModel>()
        val screenState by viewModel.screenState.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = "Настройки")
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                navigator.pop()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                "Back"
                            )
                        }
                    }
                )
            },
            content = { paddingValues ->
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                ) {
                    Card {
                        PreferencePhoneTextField(
                            text = "Сервисный номер",
                            value = screenState.serviceNumber,
                            onValueChange = viewModel::setServiceNumber,
                            summary = "При звонке с этого номера, на него будет отправлено сообщение со служебной " +
                                    "информацией. Необходимо для удаленного контроля состояния аккамулятора"
                        )
                    }

                    Spacer(modifier = Modifier.height(15.dp))

                        Card {
                            PreferenceRadioButtonDialog(
                                text = "Режим работы",
                                items = listOf(
                                    "Репитер (2 Канала)",
                                    "Репитер (1 Канал)",
                                    "Супертелефон"
                                ),
                                selectedItem = screenState.connType,
                                onValueChange = viewModel::setConnType
                            )
                        }

                    Spacer(modifier = Modifier.height(15.dp))

                    if (screenState.connType == "Репитер (1 Канал)") {
                        Card {
                            PreferenceRadioButtonDialog1(
                                text = "Источник звука для VOX",
                                items = listOf(
                                    "Отладка порога и удержания",
                                    "MIC",
                                    "VOICE_UPLINK",
                                    "VOICE_DOWNLINK",
                                    "VOICE_CALL",
                                    "CAMCORDER",
                                    "VOICE_RECOGNITION",
                                    "VOICE_COMMUNICATION",
                                    "REMOTE_SUBMIX",
                                    "UNPROCESSED",
                                    "VOICE_PERFORMANCE",
                                ),
                                selectedItem = screenState.soundSource,
                                onValueChange = viewModel::setSoundSource
                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))
                    }

                    if (screenState.connType == "Супертелефон") {
                        Card {
                            PreferencePlusMinus(
                                text = "Активация VOX",
                                value = screenState.delayMusic,
                                onValueChange = viewModel::setDelayMusic,
                                summary = "Время проигрывания тона 1000гц, до момента когда система VOX включит " +
                                        "радиостанцию на передачу. Необходимо для устранения проглатывания начальных слов сообщения." +
                                        " Для рации Baofeng U82R = 250ms"

                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))
                    }

                    if (screenState.connType == "Репитер (1 Канал)") {
                        Card {
                            PreferencePlusMinus1(
                                text = "Время удержания VOX",
                                value = screenState.delayMusic1,
                                onValueChange = viewModel::setDelayMusic1,
                                summary = "Минимальная длительность паузы речевого сигнала (ms), при превышении которой вспышка отключится"

                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))    
                    }
                    
                    if (screenState.connType == "Репитер (1 Канал)") {
                        Card {
                            PreferencePlusMinus2(
                                text = "Порог срабатывания VOX",
                                value = screenState.delayMusic2,
                                onValueChange = viewModel::setDelayMusic2,
                                summary = "Настройка чувствительности порога при превышении которого сработает вспышка от о до 10000"

                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))    
                    }

                    if (screenState.connType == "Супертелефон") {
                        Card {
                            PreferenceSwitch(
                                text = "Сигнальная вспышка",
                                checked = screenState.isFlashSignal,
                                onCheckedChange = viewModel::setFlashSignal,
                            )
                        }
                    }

                    if (screenState.connType == "Репитер (2 Канала)") {
                        Card {
                            PreferenceSwitch1(
                                text = "Аппаратный DTMF",
                                checked = screenState.isNoDtmModule,
                                onCheckedChange = viewModel::setNoDtmModule,
                            )
                        }
                    }
                }
            }
        )
    }
}
