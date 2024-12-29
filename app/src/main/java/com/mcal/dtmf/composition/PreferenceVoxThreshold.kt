package com.mcal.dtmf.composition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcal.dtmf.R

/**
 * Правило: максимальное значение должно делиться на период без остатка
 *
 * 5000 / 500 = 10
 * 3000 / 300 = 10
 */
@Composable
fun PreferenceVoxThreshold(
    text: String,
    value: Long,
    period: Long = 100L,
    max: Long = 10000L,
    onValueChange: (Long) -> Unit,
    summary: String
) {
    var showDialog by remember { mutableStateOf(false) }
    var localeValueLong by remember { mutableLongStateOf(value) }
    var localeValue by remember { mutableStateOf(localeValueLong.toString()) }
    var minusEnabled by remember { mutableStateOf(localeValueLong in period..max) }
    var plusEnabled by remember { mutableStateOf(localeValueLong in 0..max - period) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable { showDialog = true },
        verticalAlignment = Alignment.CenterVertically
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
                    fontSize = 18.sp
                )
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = "$localeValue единиц",
                style = TextStyle(
                    fontWeight = FontWeight(400),
                    textAlign = TextAlign.Start,
                    fontSize = 18.sp,
                    color = Color.Blue
                )
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                onValueChange(localeValueLong)
                showDialog = false
            },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedIconButton(
                            enabled = minusEnabled,
                            onClick = {
                                if (localeValueLong in period..max) {
                                    plusEnabled = true
                                    localeValueLong -= period
                                    if (localeValueLong == 100L) {
                                        minusEnabled = false
                                    }
                                    localeValue = localeValueLong.toString()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_minus),
                                contentDescription = null
                            )
                        }
                        Text(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            text = "${localeValue.toFloat()} ед",
                            style = TextStyle(
                                fontWeight = FontWeight(400),
                                textAlign = TextAlign.Center,
                                fontSize = 18.sp
                            )
                        )
                        OutlinedIconButton(
                            enabled = plusEnabled,
                            onClick = {
                                if (localeValueLong in 0..max - period) {
                                    minusEnabled = true
                                    localeValueLong += period
                                    if (localeValueLong == max) {
                                        plusEnabled = false
                                    }
                                    localeValue = localeValueLong.toString()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_plus),
                                contentDescription = null
                            )
                        }
                    }
                    Text(summary, fontSize = 14.sp)
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        onValueChange(localeValueLong)
                        showDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}