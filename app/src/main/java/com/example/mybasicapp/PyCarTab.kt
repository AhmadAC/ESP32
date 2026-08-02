package com.example.mybasicapp

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun PyCarDriveButton(text: String, action: String, modifier: Modifier = Modifier, onCommand: (JSONObject) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed) {
            onCommand(JSONObject().apply { put("action", action) })
        } else {
            onCommand(JSONObject().apply { put("action", "stop") })
        }
    }

    Button(
        onClick = {}, // Handled inherently by Interaction Source
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(containerColor = BtnBlue),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(60.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun PyCarTab(onCommand: (JSONObject) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())) {
        
        CardContainer(title = "PyCar Drive Controls") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PyCarDriveButton("Forward", "forward", Modifier.weight(1f).padding(4.dp), onCommand)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                PyCarDriveButton("Left", "left", Modifier.weight(1f).padding(4.dp), onCommand)
                PyCarDriveButton("Right", "right", Modifier.weight(1f).padding(4.dp), onCommand)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PyCarDriveButton("Backward", "backward", Modifier.weight(1f).padding(4.dp), onCommand)
            }
        }
        
        Spacer(modifier = Modifier.height(15.dp))

        CardContainer(title = "Hardware Features") {
            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Toggle Light", BtnGreen, Modifier.weight(1f).padding(4.dp)) {
                    onCommand(JSONObject().apply { put("action", "light") })
                }
                HtmlButton("Line Follower", BtnRed, Modifier.weight(1f).padding(4.dp)) {
                    onCommand(JSONObject().apply { put("action", "line") })
                }
            }
        }
    }
}