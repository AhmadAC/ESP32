// app/src/main/java/com/example/mybasicapp/PyCarTab.kt
package com.example.mybasicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun PyCarDriveButton(text: String, actionCode: Int, modifier: Modifier = Modifier, onCommand: (JSONObject) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }

    // Use raw pointerInput for absolute touch lifecycle detection 
    // This prevents ScrollViews from swallowing the continuous press interactions
    val pointerInputModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                isPressed = true
                onCommand(JSONObject().apply { put("a", actionCode) }) // Short numeric code to save MTU bytes
                try {
                    tryAwaitRelease() // Block until the user lifts their finger
                } finally {
                    isPressed = false
                    onCommand(JSONObject().apply { put("a", 1) }) // 1 = stop
                }
            }
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(60.dp)
            .background(if (isPressed) BtnPurple else BtnBlue, RoundedCornerShape(10.dp))
            .then(pointerInputModifier)
    ) {
        Text(text, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun PyCarTab(onCommand: (JSONObject) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())) {
        
        CardContainer(title = "PyCar Drive Controls") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                // 2 = Forward
                PyCarDriveButton("Forward", 2, Modifier.weight(1f).padding(4.dp), onCommand)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                // 4 = Left, 5 = Right
                PyCarDriveButton("Left", 4, Modifier.weight(1f).padding(4.dp), onCommand)
                PyCarDriveButton("Right", 5, Modifier.weight(1f).padding(4.dp), onCommand)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                // 3 = Backward
                PyCarDriveButton("Backward", 3, Modifier.weight(1f).padding(4.dp), onCommand)
            }
        }
        
        Spacer(modifier = Modifier.height(15.dp))

        CardContainer(title = "Hardware Features") {
            Row(modifier = Modifier.fillMaxWidth()) {
                // 6 = Light Toggle
                HtmlButton("Toggle Light", BtnGreen, Modifier.weight(1f).padding(4.dp)) {
                    onCommand(JSONObject().apply { put("a", 6) })
                }
                // 7 = Line Follower
                HtmlButton("Line Follower", BtnRed, Modifier.weight(1f).padding(4.dp)) {
                    onCommand(JSONObject().apply { put("a", 7) })
                }
            }
        }
    }
}