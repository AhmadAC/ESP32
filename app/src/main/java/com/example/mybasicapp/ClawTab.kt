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
    currentMode: String,
    onClawCommand: (String) -> Unit,
    onClawAngle: (Int) -> Unit,
    onSwitchMode: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())) {
        
        // Mode Profile Selector Card
        CardContainer(title = "Device Mode Profile") {
            Row(modifier = Modifier.fillMaxWidth()) {
                val isClawActive = currentMode.equals("claw", ignoreCase = true)
                HtmlButton(
                    text = if (isClawActive) "✓ Activate Claw Mode" else "Activate Claw Mode",
                    color = if (isClawActive) BtnGreen else BtnGray,
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) {
                    onSwitchMode("claw")
                }

                val isRobotActive = currentMode.equals("robot", ignoreCase = true)
                HtmlButton(
                    text = if (isRobotActive) "✓ Activate Robot Mode" else "Activate Robot Mode",
                    color = if (isRobotActive) BtnGreen else BtnGray,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) {
                    onSwitchMode("robot")
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Claw Motion Controls Card
        CardContainer(title = "Claw Controls") {
            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Open (138)", BtnGreen, Modifier.weight(1f).padding(4.dp)) { onClawCommand("open") }
                HtmlButton("Close (0)", BtnRed, Modifier.weight(1f).padding(4.dp)) { onClawCommand("close") }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Half Open", BtnBlue, Modifier.weight(1f).padding(4.dp)) { onClawCommand("half_open") }
                HtmlButton("Half Close", BtnPurple, Modifier.weight(1f).padding(4.dp)) { onClawCommand("half_close") }
            }

            Spacer(modifier = Modifier.height(20.dp))
            var sliderValue by remember { mutableStateOf(0f) }
            Text("Claw Angle: ${sliderValue.toInt()}°", color = TextColor)
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onClawAngle(sliderValue.toInt()) },
                valueRange = 0f..138f,
                colors = SliderDefaults.colors(thumbColor = PrimaryColor, activeTrackColor = PrimaryColor)
            )
        }
    }
}