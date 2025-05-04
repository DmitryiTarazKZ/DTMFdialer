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
                            text = if (screenState.amplitudeCheck) "Супертелефон" else "Блокировка MIC",
                            color = if (screenState.amplitudeCheck) Color.Black else Color.Red
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
                                containerColor = if (screenState.isRecording) Color(0xFFE72929) else Color(0xFFE0E0E0) // Светло-серый цвет
                            )
                        ) {
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                text = screenState.input,
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
                }
            }
        )
    }

    @Composable
    private fun CallIndicator(callState: Int) {
        val color = when (callState) {
            Call.STATE_DIALING -> Color(0xFFE72929)
            Call.STATE_RINGING -> Color(0xFF0000FF)
            Call.STATE_ACTIVE -> Color(0xFF007F73)
            else -> Color(0xFF2196F3)
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