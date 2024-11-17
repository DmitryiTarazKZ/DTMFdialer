package com.mcal.dtmf.composition

import android.R
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
/**
 * TODO: В данном случае нужно хранить индексы, а не значения
 */
@Composable
fun PreferenceRadioButtonDialog1(
    text: String,
    items: List<String>,
    selectedItem: String,
    onValueChange: (String) -> Unit,
    selectedItem1: Boolean,
    onValueChange1: (Boolean) -> Unit,
    soundSourceAvailability: Map<String, Boolean> // Добавлено
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable {
                showDialog = !showDialog
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = text,
                style = TextStyle(
                    fontWeight = FontWeight(400),
                    textAlign = TextAlign.Start,
                    fontSize = 18.sp,
                )
            )
            if (selectedItem.isNotEmpty()) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = selectedItem,
                    style = TextStyle(
                        fontWeight = FontWeight(300),
                        textAlign = TextAlign.Start,
                        fontSize = 16.sp,
                        color = Color.Blue,
                    )
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                onValueChange1(false) // Добавлено: сбрасываем состояние теста при закрытии диалога
            },
            title = {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = text,
                )
            },
            text = {
                TextSpinnerRadioButton1(
                    items = items,
                    selectedItem = selectedItem,
                    onValueChange = { newValue ->
                    onValueChange(newValue)
                    },
                    soundSourceAvailability = soundSourceAvailability // Исправлено: используем именованный аргумент
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            onValueChange1(true)
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(text = "ТЕСТ")
                    }
                    OutlinedButton(
                        onClick = {
                            onValueChange(selectedItem)
                            showDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                }
            }
        )
    }
}
@Composable
fun TextSpinnerRadioButton1(
    modifier: Modifier = Modifier,
    items: List<String>,
    selectedItem: String,
    onValueChange: (String) -> Unit,
    soundSourceAvailability: Map<String, Boolean> // Добавлено
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
    ) {
        items(items) { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Условие для отображения иконок только после нажатия кнопки "ТЕСТ"
                if (soundSourceAvailability.isNotEmpty()) {
                    val isAvailable = soundSourceAvailability[item] ?: false
                    val icon = when {
                        isAvailable && item != "Отладка порога и удержания" -> Icons.Default.Check // Зелёная галочка для доступных источников
                        !isAvailable && item != "Отладка порога и удержания" -> Icons.Default.Close // Красный крестик для недоступных источников
                        else -> null // Не показываем иконку для "Отладка порога и удержания"
                    }

                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = if (isAvailable) Color.Green else Color.Red // Устанавливаем цвет в зависимости от доступности
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp)) // Убираем пробел для иконки
                RadioButtonText1(
                    modifier = Modifier.fillMaxWidth(),
                    text = item,
                    selected = item.lowercase() == selectedItem.lowercase(),
                    textColor = Color.Unspecified, // Цвет текста не меняем
                    onCheckedChange = {
                        onValueChange(item) // Передаем обработчик изменения состояния
                    }
                )
            }
        }
    }
}