package com.mcal.dtmf.composition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RadioButtonText1(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(
        modifier = modifier
            .toggleable(
                value = selected,
                role = Role.RadioButton,
                onValueChange = onCheckedChange,
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.heightIn(48.dp)
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                text = text,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(500),
                )
            )
            RadioButton(
                modifier = Modifier,
                selected = selected,
                onClick = {
                    onCheckedChange(!selected)
                }
            )
        }
        HorizontalDivider(
            modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(color = Color(0x4D000000))
        )
    }
}