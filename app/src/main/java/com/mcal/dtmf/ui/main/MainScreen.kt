package com.mcal.dtmf.ui.main

import android.app.Activity
import android.telecom.Call
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mcal.dtmf.R
import com.mcal.dtmf.navigator.AppScreen
import com.mcal.dtmf.recognizer.Spectrum
import com.mcal.dtmf.ui.help.HelpScreen
import com.mcal.dtmf.ui.preferences.PreferencesScreen

class MainScreen : AppScreen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = getScreenModel<MainViewModel>()
        val screenState by viewModel.screenState.collectAsState()
        val context = LocalContext.current as Activity

        LaunchedEffect(screenState.connectType) {
            when (screenState.connectType) {
                "Репитер (2 Канала)" -> {
                    // Устанавливаем флаг FLAG_KEEP_SCREEN_ON, чтобы удерживать экран постоянно включеным
                    // иначе не сработает кнопка гарнитуры которая запускает таймер
                    context.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                "Репитер (1 Канал)" -> {
                    // Устанавливаем флаг FLAG_KEEP_SCREEN_ON, чтобы удерживать экран постоянно включеным
                    // иначе не сработает кнопка гарнитуры запускающая VOX систему если будет использоваться кнопка
                    context.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                "Супертелефон" -> {
                    // Сбрасываем флаг FLAG_KEEP_SCREEN_ON, чтобы вернуться к системным настройкам таймаута экрана
                    context.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = screenState.connectType,
                        )
                    },

                    actions = {
                        IconButton(onClick = {
                            navigator.push(PreferencesScreen())
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                "Settings"
                            )
                        }
                        IconButton(onClick = {
                            navigator.push(HelpScreen())
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                "Help"
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
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // основное поле ввода номера
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            val text = if (screenState.timer == 0 && screenState.input == "") {
                                "Отключен"
                            } else if (screenState.timer > 0 && screenState.input == "") {
                                "Включен"
                            } else screenState.input
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                text = text,
                                style = TextStyle(
                                    fontSize = 40.sp,
                                    color = Color.Black,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(5.dp))

                        // если режим супертелефон и не отсутствие звонка включаем громкоговоритель
                        if ((screenState.connectType == "Супертелефон") && screenState.callState != Call.STATE_DISCONNECTED) {
                            viewModel.speakerOn()
                        }

                        // Выключатель громкоговорителя
                        Card(
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        if (screenState.callState != Call.STATE_DISCONNECTED) {
                                            viewModel.speaker()
                                        } else {
                                            Toast
                                                .makeText(
                                                    context,
                                                    "Нет активного вызова",
                                                    Toast.LENGTH_SHORT
                                                )
                                                .show()
                                        }
                                    }
                                    .background(
                                        if (screenState.isSpeakerOn) {
                                            Color(0xFFE72929)
                                        } else {
                                            Color(0xFF2196F3)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (screenState.isSpeakerOn) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_speaker_on),
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_speaker_off),
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // индикатор кнопок гарнитуры и кнопка запуска дтмф
                        Card(
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        viewModel.flashLight()
                                    }
                                    .background(
                                        when (screenState.micClickKeyCode) {
                                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                                Color(0xFF007F73)
                                            }

                                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                                Color(0xFFE72929)

                                            }

                                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                                Color.Blue
                                            }

                                            else -> {
                                                Color(0xFF2196F3)
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {

                              if(screenState.isConnect) {
                                  Icon(
                                      painter = painterResource(R.drawable.ic_headset),
                                      contentDescription = null,
                                      tint = Color.White
                                  )
                              } else {
                                  Icon(
                                  painter = painterResource(R.drawable.power_settings),
                                  contentDescription = null,
                                  tint = Color.White
                              )
                              }
                            }
                        }
                        Spacer(modifier = Modifier.width(5.dp))
                            // индикатор подключения зарядного устройства
                            Card(
                                modifier = Modifier.size(54.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(

                                            if (screenState.isPowerConnected) {
                                                Color(0xFFE72929)
                                            } else {
                                                Color(0xFF2196F3)
                                            }

                                        ),
                                    contentAlignment = Alignment.Center
                                ) {

                                    Icon(
                                        painter = painterResource(R.drawable.ic_charger),
                                        contentDescription = null,
                                        tint = Color.White
                                    )

                                }
                            }
                            Spacer(modifier = Modifier.width(5.dp))

                            // индикатор направления вызова
                            Card(
                                modifier = Modifier.size(54.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            when (screenState.callState) {
                                                Call.STATE_DIALING -> {
                                                    Color(0xFFE72929)
                                                }

                                                Call.STATE_RINGING -> {
                                                    Color.Blue
                                                }

                                                Call.STATE_ACTIVE -> {
                                                    Color(0xFF007F73)
                                                }

                                                else -> {
                                                    Color(0xFF2196F3)
                                                }
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val icon = when (screenState.callState) {
                                        Call.STATE_DIALING -> {
                                            R.drawable.ic_call_made
                                        }

                                        Call.STATE_RINGING -> {
                                            R.drawable.ic_received
                                        }

                                        else -> {
                                            R.drawable.ic_phone_in_talk
                                        }
                                    }

                                    Icon(
                                        painter = painterResource(icon),
                                        contentDescription = null,
                                        tint = Color.White
                                    )

                                }
                            }
                            Spacer(modifier = Modifier.width(5.dp))

                            // индикатор включения вспышки
                            Card(
                                modifier = Modifier.size(54.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            if (screenState.flashlight) {
                                                Color(0xFFE72929)
                                            } else {
                                                Color(0xFF2196F3)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {

                                    Text(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp),
                                        text = screenState.timer.toString(),
                                        style = TextStyle(
                                            fontSize = 40.sp,
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )
                                    )

                                }
                            }
                            Spacer(modifier = Modifier.width(5.dp))


                        // вывод окна спектра
                        SpectrumCanvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            spectrum = screenState.spectrum,
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        content = {
                            items(screenState.keys) { key ->
                                val color = when {
                                    screenState.key == key -> {
                                        Color.DarkGray
                                    }

                                    "ABCD".contains(key) -> {
                                        Color(0xFF2196F3)
                                    }

                                    key == '#' -> {
                                        Color(0xFFE72929)
                                    }

                                    key == '*' -> {
                                        Color(0xFF007F73)
                                    }

                                    else -> {
                                        MaterialTheme.colorScheme.primary
                                    }
                                }

                                // ОТРИСОВКА КНОПОК
                                Button(
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .size(54.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = color
                                    ),
                                    onClick = {
                                        viewModel.onClickButton(screenState.input, key)
                                    }
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
                    )


                    if (screenState.numberA.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(0.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Card(
                                modifier = Modifier.size(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = "A",
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = screenState.numberA,
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                    }

                    if (screenState.numberB.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(5.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Card(
                                modifier = Modifier.size(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = "B",
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = screenState.numberB,
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                    }

                    if (screenState.numberC.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(5.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Card(
                                modifier = Modifier.size(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = "C",
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = screenState.numberC,
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                    }

                    if (screenState.numberD.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(5.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Card(
                                modifier = Modifier.size(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = "D",
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = screenState.numberD,
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    //отрисовка спектра разными цветами
    @Composable
    fun SpectrumCanvas(
        modifier: Modifier = Modifier,
        spectrum: Spectrum?,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            spectrum?.let { spectrum ->
                Canvas(
                    modifier = modifier.fillMaxSize()
                ) {
                    //цикл отрисовки вертикальных полос в спектре
                    val canvasHeight = size.height
                    for (i in 0 until 500) {
                        val downY = (canvasHeight - (spectrum[i] * canvasHeight))
                        val color = when (i) {
                            // значение * 15.625 это частоты попадающие в двухтональный набор
                            in 40..65, in 75..110 -> Color.Blue
                            else -> Color.Black
                        }
                        // отрисовка вертикальных линий в спектре
                        drawLine(
                            color = color,
                            start = Offset(i.toFloat(), downY.toFloat()),
                            end = Offset(i.toFloat(), canvasHeight),
                            strokeWidth = 1f,
                        )
                    }
                }
            }
        }
    }
}
