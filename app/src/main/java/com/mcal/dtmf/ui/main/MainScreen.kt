package com.mcal.dtmf.ui.main

import android.telecom.Call
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mcal.dtmf.R
import com.mcal.dtmf.ui.help.HelpScreen

class MainScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = getScreenModel<MainViewModel>()
        val screenState by viewModel.screenState.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when {
                                screenState.frequencyCtcss > 0.0 -> {
                                    val volumePercent = (screenState.volumeLevelCtcss * 1000).toInt()
                                    "CTCSS ${screenState.frequencyCtcss} Гц ${volumePercent}%"
                                }
                                screenState.amplitudeCheck -> if (screenState.flagDtmfMic) {
                                    "SUPER TEL" + " <${screenState.callState}> <${screenState.timer/1000}> <${screenState.micClickKeyCode}>"
                                } else {
                                    "SUPER TEL" + " <${screenState.callState}> <НЦ> <${screenState.micClickKeyCode}>"
                                }
                                else -> "Блокировка MIC"
                            },
                            color = when {
                                // 1. Если amplitudeCheck false, выводится "Блокировка MIC" -> ставим красный цвет первым приоритетом
                                !screenState.amplitudeCheck -> Color.Red
                                // 2. Остальные условия по порядку
                                screenState.statusDtmf -> Color.Blue
                                screenState.isPlaying -> Color.Red
                                else -> Color.Black
                            }
                        )
                    },
                    actions = {
                        IconButton(onClick = { navigator.push(HelpScreen()) }) {
                            Icon(imageVector = Icons.Outlined.Info, contentDescription = "Help")
                        }
                    }
                )
            },

            content = { paddingValues ->
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp)
                ) {
                    // Основное поле ввода номера и индикатор направления вызова
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Поле ввода номера
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .height(54.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    screenState.isRecording -> Color(0xFFE72929) // Красный, если идет запись голосовой заметки.
                                   // screenState.magneticField -> Color(0xFF1E88E5) // Синий, если есть магнитное поле = true (0xFF1E88E5 - глубокий синий)
                                    else -> Color(0xFFE0E0E0) // Светло-серый по умолчанию
                                }
                            )
                        ) {
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                text = if (screenState.isRecording) "Диктофон" else screenState.input,
                                style = TextStyle(
                                    fontSize = 40.sp,
                                    color = Color.Black,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }

                        Spacer(modifier = Modifier.width(5.dp))

                        // Индикатор направления вызова
                        CallIndicator(screenState.callState)
                    }

                    Spacer(modifier = Modifier.height(5.dp))

                    // Кнопки DTMF
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        content = {
                            items(screenState.keys) { key ->
                                DTMFButton(key, screenState.key) {
                                    viewModel.onClickButton(screenState.input, key)
                                }
                            }
                        }
                    )

                    // Вывод верхней и нижней частоты DTMF
                    if (screenState.flagFrequencyLowHigt) {
                        Spacer(modifier = Modifier.height(5.dp))

                        // Вывод верхней частоты
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                        ) {
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                text = screenState.outputFrequencyHigh.toString() + "Hz H",
                                style = TextStyle(
                                    fontSize = 40.sp,
                                    color = Color.Black,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(5.dp))

                        // Вывод нижней частоты
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                        ) {
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                text = screenState.outputFrequencyLow.toString() + "Hz L",
                                style = TextStyle(
                                    fontSize = 40.sp,
                                    color = Color.Black,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun CallIndicator(callState: Int) {
        val color = when (callState) {
            Call.STATE_DIALING -> Color(0xFFE72929) // Красный для исходящего вызова (1)
            Call.STATE_RINGING -> Color(0xFF0000FF) // Синий для входящего вызова (2)
            Call.STATE_ACTIVE -> Color(0xFF007F73) // Зеленый для активного вызова (4)
            Call.STATE_CONNECTING -> Color(0xFFFFEB3B) // Желтый для состояния подключения (9)
            else -> Color(0xFF2196F3) // По умолчанию синий
        }

        val icon = when (callState) {
            Call.STATE_DIALING -> R.drawable.ic_call_made
            Call.STATE_RINGING -> R.drawable.ic_received
            else -> R.drawable.ic_phone_in_talk
        }

        Card(modifier = Modifier.size(54.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }

    @Composable
    private fun DTMFButton(key: Char, selectedKey: Char, onClick: () -> Unit) {
        val color = when (key) {
            selectedKey -> Color.DarkGray
            in "ABCD".toCharArray() -> Color(0xFF2196F3)
            '#' -> Color(0xFFE72929)
            '*' -> Color(0xFF007F73)
            else -> MaterialTheme.colorScheme.primary
        }

        Button(
            modifier = Modifier
                .padding(4.dp)
                .size(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color),
            onClick = onClick
        ) {
            Text(
                text = key.toString(),
                style = TextStyle(
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}