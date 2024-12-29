package com.mcal.dtmf.ui.preferences

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton // Изменено с material на material3
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mcal.dtmf.composition.PreferenceErrorControl
import com.mcal.dtmf.composition.PreferenceFlashSignal
import com.mcal.dtmf.composition.PreferenceModeSelection
import com.mcal.dtmf.composition.PreferenceServiceNumber
import com.mcal.dtmf.composition.PreferenceVoxActivation
import com.mcal.dtmf.composition.PreferenceVoxHold
import com.mcal.dtmf.composition.PreferenceVoxSetting
import com.mcal.dtmf.composition.PreferenceVoxThreshold
import com.mcal.dtmf.navigator.AppScreen
import com.mcal.dtmf.utils.LogManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat.startActivity


class PreferencesScreen : AppScreen {
    override val key: ScreenKey
        get() = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = getScreenModel<PreferencesViewModel>()
        val screenState by viewModel.screenState.collectAsState()
        val logs by viewModel.logs.collectAsState()

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
                    if (!screenState.isErrorControl) {
                        Card {
                            PreferenceServiceNumber(
                                text = "Сервисный номер",
                                value = screenState.serviceNumber,
                                onValueChange = viewModel::setServiceNumber,
                                summary = "При звонке с этого номера, на него будет отправлено сообщение со служебной " +
                                        "информацией. Необходимо для удаленного контроля состояния аккамулятора"
                            )
                        }
                        Spacer(modifier = Modifier.height(15.dp))
                    }

                    Card {
                        PreferenceModeSelection(
                            text = "Режим работы",
                            items = listOf(
                                "Репитер (2 Канала+)",
                                "Репитер (2 Канала)",
                                "Репитер (1 Канал)",
                                "Супертелефон",
                                "Частотомер"
                            ),
                            selectedItem = screenState.modeSelection,
                            onValueChange = viewModel::setModeSelection
                        )
                    }

                    Spacer(modifier = Modifier.height(15.dp))

                    if (screenState.modeSelection == "Репитер (1 Канал)") {
                        Card {
                            PreferenceVoxSetting(
                                text = "Настройка VOX",
                                checked = screenState.voxSetting,
                                onCheckedChange = viewModel::setVoxSetting,
                            )
                        }
                        Spacer(modifier = Modifier.height(15.dp))
                    }

                    if (screenState.modeSelection == "Супертелефон") {
                        Card {
                            PreferenceVoxActivation(
                                text = "Активация VOX",
                                value = screenState.voxActivation,
                                onValueChange = viewModel::setVoxActivation,
                                summary = "Время проигрывания тона 1000гц, до момента когда система VOX включит " +
                                        "радиостанцию на передачу. Необходимо для устранения проглатывания начальных слов сообщения." +
                                        " Для рации Baofeng U82R = 250ms"

                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))
                    }

                    if (screenState.voxSetting) {
                        Card {
                            PreferenceVoxHold(
                                text = "Время удержания VOX",
                                value = screenState.voxHold,
                                onValueChange = viewModel::setVoxHold,
                                summary = "Минимальная длительность паузы речевого сигнала (ms), при превышении которой вспышка отключится"

                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))
                    }

                    if (screenState.voxSetting) {
                        Card {
                            PreferenceVoxThreshold(
                                text = "Порог срабатывания VOX",
                                value = screenState.voxThreshold,
                                onValueChange = viewModel::setVoxThreshold,
                                summary = "Настройка чувствительности порога при превышении которого сработает вспышка от о до 10000"

                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))
                    }

                    if (screenState.modeSelection == "Супертелефон") {
                        Card {
                            PreferenceFlashSignal(
                                text = "Сигнальная вспышка",
                                checked = screenState.isFlashSignal,
                                onCheckedChange = viewModel::setFlashSignal,
                            )
                        }
                    Spacer(modifier = Modifier.height(15.dp))
                    }

                    Card {
                        PreferenceErrorControl(
                            text = "Отладка приложения",
                            checked = screenState.isErrorControl,
                            onCheckedChange = viewModel::setErrorControl,
                        )
                    }

                    if (screenState.isErrorControl) {
                        Spacer(modifier = Modifier.height(15.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = "Ключевые события:",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )

                                    // Заменить этот блок LazyColumn на новый
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    ) {
                                        items(logs) { log ->
                                            val color = when {
                                                log.contains("[INFO]") -> Color(0xFF0D47A1) // Синий
                                                log.contains("[ERROR]") -> Color(0xFFE53935) // Красный
                                                log.contains("[WARNING]") -> Color(0xFFFFC107) // Желтый
                                                else -> Color.Unspecified
                                            }

                                            Text(
                                                text = log,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = color
                                            )
                                        }
                                    }
                                }

                                // Кнопка очистки в правом нижнем углу
                                FloatingActionButton(
                                    onClick = { LogManager.clearLogs() },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = 8.dp, end = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Очистить логи"
                                    )
                                }

                                val context = LocalContext.current

                                // Кнопка поделиться в левом нижнем углу
                                FloatingActionButton(
                                    onClick = { LogManager.shareLogs(context) },
                                    modifier = Modifier
                                        .align(Alignment.BottomStart) // Измените на BottomStart для левого нижнего угла
                                        .padding(bottom = 8.dp, start = 8.dp) // Измените на start для отступа слева
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share, // Используйте иконку "Поделиться"
                                        contentDescription = "Поделиться логами"
                                    )
                                }

                            }
                        }
                    }
                }
            }
        )
    }
}
