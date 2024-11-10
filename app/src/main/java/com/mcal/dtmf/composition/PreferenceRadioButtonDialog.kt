package com.mcal.dtmf.composition

import android.R
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
/**
 * TODO: В данном случае нужно хранить индексы, а не значения
 */
@Composable
fun PreferenceRadioButtonDialog(
    text: String,
    items: List<String>,
    selectedItem: String,
    onValueChange: (String) -> Unit,
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
            },
            title = {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = text,
                )
            },
            text = {
                TextSpinnerRadioButton(
                    items = items,
                    selectedItem = selectedItem,
                    onValueChange = { newValue ->
                        onValueChange(newValue)
                    },
                )
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        onValueChange(selectedItem)
                        showDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.ok))
                }
            }
        )
    }
}
@Composable
fun TextSpinnerRadioButton(
    modifier: Modifier = Modifier,
    items: List<String>,
    selectedItem: String,
    onValueChange: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
    ) {
        items(items) { item ->
            RadioButtonText(
                modifier = Modifier.fillMaxWidth(),
                text = item,
                selected = item.lowercase() == selectedItem.lowercase()
            ) {
                onValueChange(item)
            }
        }
    }
}