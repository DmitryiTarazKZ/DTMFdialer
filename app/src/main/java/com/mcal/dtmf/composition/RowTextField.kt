package com.mcal.dtmf.composition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RowTextField(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight(400),
                textAlign = TextAlign.Start,
            )
        )
        RowTextField2(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            visualTransformation = visualTransformation,
            placeholder = "7 (xxx) xxx-xx-xx",
            keyboardOptions = keyboardOptions,
            onValueChange = onValueChange,
        )
    }
}

@Composable
private fun RowTextField2(
    modifier: Modifier = Modifier,
    value: String,
    maxSymbols: Int = 0,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    placeholder: String,
    fontSize: TextUnit = 16.sp,
    onValueChange: (String) -> Unit,
) {
    Box(modifier = modifier.padding(top = 4.dp)) {
        BasicTextField(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 21.dp),
            value = value,
            onValueChange = {
                if (maxSymbols > 0) {
                    if (it.length <= maxSymbols) {
                        onValueChange(it)
                    }
                } else {
                    onValueChange(it)
                }
            },
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight(400),
            ),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    EmptyField(
                        modifier = Modifier.fillMaxWidth(),
                        label = placeholder,
                        innerTextField = innerTextField,
                    )
                } else {
                    NotEmptyField(
                        modifier = Modifier.fillMaxWidth(),
                        innerTextField = innerTextField,
                    )
                }
            }
        )
    }
}

@Composable
private fun EmptyField(
    modifier: Modifier = Modifier,
    label: String,
    innerTextField: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(top = 5.dp)
    ) {
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = label,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight(400),
                        color = Color(0xCC737373),
                        textAlign = TextAlign.Start,
                    )
                )
                innerTextField()
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .padding(start = 14.dp, end = 14.dp, top = 5.dp)
                .height(1.dp)
                .fillMaxWidth()
                .background(color = Color(0x4D000000))
        )
    }
}

@Composable
private fun NotEmptyField(
    modifier: Modifier = Modifier,
    innerTextField: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(top = 5.dp)
    ) {
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = "",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight(300),
                        color = Color(0xFF0052D4),
                    )
                )
                Box(
                    modifier = modifier.padding(top = 16.dp)
                ) {
                    innerTextField()
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .padding(start = 14.dp, end = 14.dp, top = 5.dp)
                .height(1.dp)
                .fillMaxWidth()
                .background(color = Color(0x4D000000))
        )
    }
}
