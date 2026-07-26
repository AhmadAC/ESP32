// app/src/main/java/com/example/mybasicapp/ClawTab.kt
package com.example.mybasicapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ClawTab(
    onClawCommand: (String) -> Unit,
    onClawAngle: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())) {
        CardContainer(title = "Claw Controls") {
            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Open (180)", BtnGreen, Modifier.weight(1f).padding(4.dp)) { onClawCommand("open") }
                HtmlButton("Close (0)", BtnRed, Modifier.weight(1f).padding(4.dp)) { onClawCommand("close") }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Half Open", BtnBlue, Modifier.weight(1f).padding(4.dp)) { onClawCommand("half_open") }
                HtmlButton("Half Close", BtnPurple, Modifier.weight(1f).padding(4.dp)) { onClawCommand("half_close") }
            }

            Spacer(modifier = Modifier.height(20.dp))
            var sliderValue by remember { mutableStateOf(90f) }
            Text("Claw Angle: ${sliderValue.toInt()}°", color = TextColor)
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onClawAngle(sliderValue.toInt()) },
                valueRange = 0f..180f,
                colors = SliderDefaults.colors(thumbColor = PrimaryColor, activeTrackColor = PrimaryColor)
            )
        }
    }
}