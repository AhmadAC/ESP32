// app/src/main/java/com/example/mybasicapp/UIComponents.kt
package com.example.mybasicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val BgColor = Color(0xFF0F172A)
val CardColor = Color(0xFF1E293B)
val TextColor = Color(0xFFF1F5F9)
val PrimaryColor = Color(0xFF0EA5E9)
val BtnGreen = Color(0xFF10B981)
val BtnRed = Color(0xFFEF4444)
val BtnBlue = Color(0xFF3B82F6)
val BtnPurple = Color(0xFF8B5CF6)
val BtnOrange = Color(0xFFF59E0B)
val BtnGray = Color(0xFF475569)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedValue.uppercase().replace("_", " "),
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = PrimaryColor) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                focusedTextColor = TextColor, unfocusedTextColor = TextColor,
                focusedBorderColor = PrimaryColor, unfocusedBorderColor = BtnGray
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(CardColor)
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.uppercase().replace("_", " "), color = TextColor) },
                    onClick = {
                        onValueChange(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun CardContainer(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Text(title, color = PrimaryColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))
        content()
    }
}

@Composable
fun HtmlButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(50.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun MotorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextColor, fontSize = 14.sp)
            Text("${value.toInt()}°", color = PrimaryColor, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..180f,
            colors = SliderDefaults.colors(thumbColor = PrimaryColor, activeTrackColor = PrimaryColor)
        )
    }
}