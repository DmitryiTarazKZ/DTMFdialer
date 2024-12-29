package com.mcal.dtmf.ui.main

import android.app.Activity
import android.telecom.Call
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Blue
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.input.pointer.pointerInput
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
        val context = LocalContext.current
        var sliderPosition by remember { mutableFloatStateOf(112f) }
        val currentContext = rememberUpdatedState(context)

        LaunchedEffect(screenState.modeSelection) {
            (currentContext.value as? Activity)?.let { activity ->
                if (screenState.modeSelection == "Репитер (2 Канала)") {
                    // Устанавливаем флаг FLAG_KEEP_SCREEN_ON, чтобы удерживать экран постоянно включеным
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    // Сбрасываем флаг FLAG_KEEP_SCREEN_ON для всех остальных типов подключения
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = screenState.modeSelection,
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
                            val text = when {
                                screenState.modeSelection == "Частотомер" -> String.format("%.3f Гц", screenState.outputFrequency1)
                                screenState.timer == 0 && screenState.input == "" -> "Отключен"
                                screenState.timer > 0 && screenState.input == "" -> "Включен"
                                else -> screenState.input
                            }
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

                        if (screenState.modeSelection != "Частотомер") {
                            Spacer(modifier = Modifier.width(5.dp))
                            // Выключатель громкоговорителя
                            Card(modifier = Modifier.size(54.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            if (screenState.callState != Call.STATE_DISCONNECTED) {
                                                viewModel.speaker()
                                            } else {
                                                Toast.makeText(
                                                    currentContext.value,
                                                    "Нет активного вызова",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        .background(
                                            if (screenState.isSpeakerOn) Color(0xFFE72929) else Color(0xFF2196F3)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (screenState.isSpeakerOn) R.drawable.ic_speaker_on else R.drawable.ic_speaker_off
                                        ),
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(5.dp))

                    if (screenState.modeSelection == "Частотомер") {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // основное поле вывода частоты по позиции ползунка
                            val frequency1 = screenState.outputFrequency1
                            val frequency2 = sliderPosition.toInt() * 15.625f
                            val text = String.format("%.3f Гц", frequency2)
                            viewModel.flashLightOn(frequency1, frequency2)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .height(54.dp)
                                    .background(if (frequency1 == frequency2) Red else Color.Transparent)
                            ) {
                                val color = Blue
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    text = text,
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        color = color,
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(5.dp))
                    }

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
                                                Blue
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
                        if (screenState.modeSelection != "Частотомер") {
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
                            Spacer(modifier = Modifier.width(5.dp))}
                        if (screenState.modeSelection != "Частотомер") {
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
                                                    Blue
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
                            Spacer(modifier = Modifier.width(5.dp))}

                        if (screenState.modeSelection != "Частотомер") {
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
                            Spacer(modifier = Modifier.width(5.dp))}


                        // вывод окна спектра
                        SpectrumCanvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            spectrum = screenState.spectrum,
                            modeSelection = screenState.modeSelection,
                            sliderPosition = sliderPosition, // Передаем значение здесь
                            onSliderPositionChange = { newPosition ->
                                sliderPosition = newPosition // Обновляем значение sliderPosition
                            }
                        )
                    }

                    if (screenState.modeSelection != "Частотомер") {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            content = {
                                items(screenState.keys) { key ->
                                    val color = when (key) {
                                        screenState.key -> Color.DarkGray
                                        in "ABCD".toCharArray() -> Color(0xFF2196F3)
                                        '#' -> Color(0xFFE72929)
                                        '*' -> Color(0xFF007F73)
                                        else -> MaterialTheme.colorScheme.primary
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
                    }

                    if (screenState.modeSelection != "Частотомер") {
                        listOf("A" to screenState.numberA, "B" to screenState.numberB, "C" to screenState.numberC, "D" to screenState.numberD).forEach { (label, number) ->
                            if (number.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(5.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Card(modifier = Modifier.size(54.dp)) {
                                        Text(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                            text = label,
                                            style = TextStyle(fontSize = 40.sp, textAlign = TextAlign.Center)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Card(modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                        Text(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                            text = number,
                                            style = TextStyle(fontSize = 40.sp, textAlign = TextAlign.Center)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    @Composable
    fun SpectrumCanvas(
        modifier: Modifier = Modifier,
        spectrum: Spectrum?,
        modeSelection: String,
        sliderPosition: Float,
        onSliderPositionChange: (Float) -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            spectrum?.let { spectrum ->
                Canvas(
                    modifier = modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val newPosition = change.position.x.coerceIn(0f, 500f)
                                onSliderPositionChange(newPosition)
                            }
                        }
                ) {
                    // цикл отрисовки вертикальных полос в спектре
                    val canvasHeight = size.height
                    for (i in 0 until 500) {
                        val downY = (canvasHeight - (spectrum[i] * canvasHeight))
                        val color = when (i) {
                            in 40..65, in 75..110 -> Blue
                            else -> Color.Black
                        }
                        drawLine(
                            color = color,
                            start = Offset(i.toFloat(), downY.toFloat()),
                            end = Offset(i.toFloat(), canvasHeight),
                            strokeWidth = 1f,
                        )
                    }

                    // Отрисовка ползунка, если режим "Частотомер"
                    if (modeSelection == "Частотомер") {
                        drawLine(
                            color = Red,
                            start = Offset(sliderPosition, 0f),
                            end = Offset(sliderPosition, canvasHeight),
                            strokeWidth = 2f,
                        )
                    }
                }
            }
        }
    }
}
