package com.mcal.dtmf.composition

import android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
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

@Composable
fun PreferenceServiceNumber(
    text: String,
    value: String,
    onValueChange: (String) -> Unit,
    summary: String
) {
    var showDialog by remember { mutableStateOf(false) }
    var localeValue by remember { mutableStateOf(value) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable {
                showDialog = true
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
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
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = if (value == "") "Не определен" else value,
                style = TextStyle(
                    fontWeight = FontWeight(400),
                    textAlign = TextAlign.Start,
                    fontSize = 18.sp,
                    color = if (value == "") Color.DarkGray else Color.Blue
                )
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                onValueChange(localeValue)
                showDialog = false
            },
            text = {
                Column {
                    PhoneTextField(
                        modifier = Modifier.fillMaxWidth(),
                        phone = localeValue,
                        label = "Введите без 8 или +",
                        onPhoneChanged = {
                            localeValue = it
                        }
                    )
                    Text(summary, fontSize = 14.sp)
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        onValueChange(localeValue)
                        showDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.ok))
                }
            }
        )
    }
}
