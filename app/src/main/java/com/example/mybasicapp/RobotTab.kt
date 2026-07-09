// app/src/main/java/com/example/mybasicapp/RobotTab.kt
package com.example.mybasicapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.net.Socket

@Composable
fun RobotTab(
    ipAddress: String,
    sensorEnabled: Boolean,
    safetyLock: Boolean,
    sensorDistance: Double,
    notifyOnTrip: Boolean,
    trippedAction: String,
    clearedAction: String,
    trippedAudio: String,
    clearedAudio: String,
    audioVolume: Float,
    syncEnabled: Boolean,
    llAngle: Float,
    hlAngle: Float,
    lrAngle: Float,
    hrAngle: Float,
    hasAudioPermission: Boolean,
    onNotifyToggle: (Boolean) -> Unit,
    onSyncToggle: (Boolean) -> Unit,
    onSliderChanged: (String, Float) -> Unit,
    onSliderChangeFinished: () -> Unit,
    onSensorConfigUpdate: (String, String, String, String, Boolean) -> Unit,
    onRobotAction: (String) -> Unit,
    onAudioCommand: (String, JSONObject) -> Unit,
    onWalkieTalkie: () -> Unit
) {
    val context = LocalContext.current
    var isStreamingMic by remember { mutableStateOf(false) }
    var socketHolder by remember { mutableStateOf<Socket?>(null) }
    var audioRecordHolder by remember { mutableStateOf<AudioRecord?>(null) }
    var listenMicActive by remember { mutableStateOf(false) }

    fun startWalkieTalkie() {
        if (!hasAudioPermission) {
            Toast.makeText(context, "Microphone Permission Required!", Toast.LENGTH_SHORT).show()
            return
        }
        isStreamingMic = true
        Thread {
            try {
                val socket = Socket(ipAddress, 83)
                socketHolder = socket
                val outStream = socket.getOutputStream()
                val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                    audioRecordHolder = record
                    record.startRecording()
                    val buffer = ByteArray(bufferSize)
                    while (isStreamingMic) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) outStream.write(buffer, 0, read)
                    }
                    record.stop()
                    record.release()
                }
                socket.close()
            } catch (e: Exception) {}
        }.start()
    }

    fun stopWalkieTalkie() {
        isStreamingMic = false
        try { socketHolder?.close() } catch (e: Exception) {}
        try { audioRecordHolder?.release() } catch (e: Exception) {}
    }

    Column(modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())) {
        if (safetyLock) {
            Box(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF7F1D1D), RoundedCornerShape(10.dp)).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("WARNING: MOTORS LOCKED - Obstacle Detected!", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(15.dp))
        }

        CardContainer(title = "Audio & Speaker Output") {
            var localVolume by remember { mutableStateOf(audioVolume) }
            LaunchedEffect(audioVolume) { localVolume = audioVolume }

            Text("Volume Control: ${localVolume.toInt()}%", color = TextColor)
            Slider(
                value = localVolume,
                onValueChange = { localVolume = it },
                onValueChangeFinished = { onAudioCommand("/audio_config", JSONObject().apply { put("volume", localVolume.toInt()) }) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = PrimaryColor, activeTrackColor = PrimaryColor)
            )

            Spacer(modifier = Modifier.height(15.dp))
            var selectedTestSound by remember { mutableStateOf("dog_bark") }
            LabeledDropdown("Test Digital Audio Stream", selectedTestSound, listOf("dog_bark")) { selectedTestSound = it }
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Play", Color(0xFF14B8A6), Modifier.weight(1f).padding(end = 4.dp)) { 
                    onAudioCommand("/audio_play", JSONObject().apply { put("sound", selectedTestSound) })
                }
                HtmlButton("Stop Sound", BtnRed, Modifier.weight(1f).padding(start = 4.dp)) { 
                    onAudioCommand("/audio_stop", JSONObject())
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            
            val interactionSource = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        startWalkieTalkie()
                        onWalkieTalkie()
                        tryAwaitRelease()
                        stopWalkieTalkie()
                    }
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(50.dp).background(if (isStreamingMic) BtnRed else BtnPurple, RoundedCornerShape(10.dp)).then(interactionSource),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isStreamingMic) "Live Transmitting..." else "Hold to Speak (Walkie Talkie)", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(15.dp))
            AudioStreamPlayer(ipAddress = ipAddress, active = listenMicActive)
            HtmlButton(
                text = if (listenMicActive) "Stop Listening to Robot" else "Listen to Robot Mic",
                color = if (listenMicActive) BtnRed else BtnBlue,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { listenMicActive = !listenMicActive }
        }

        Spacer(modifier = Modifier.height(20.dp))

        CardContainer(title = "Hypersonic Sensor Settings") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HtmlButton(
                    text = if (sensorEnabled) "Disable Sensor" else "Enable Sensor",
                    color = if (sensorEnabled) BtnRed else BtnGreen,
                    modifier = Modifier.weight(1f)
                ) {
                    onSensorConfigUpdate(trippedAction, clearedAction, trippedAudio, clearedAudio, !sensorEnabled)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (sensorEnabled) "${String.format("%.1f", sensorDistance)} cm" else "Disabled",
                    color = if (safetyLock) BtnRed else (if (sensorEnabled) BtnGreen else BtnGray),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.height(15.dp))
            
            val physicalActions = listOf("stop", "forward", "backward", "step_forward", "step_backward", "leap_forward", "left_wave", "right_wave", "back_left_wave", "back_right_wave", "crawl", "sit", "stand", "stretch_down", "stretch_back", "none")
            val audioActions = listOf("none", "dog_bark")

            LabeledDropdown("Action When Tripped", trippedAction, physicalActions) { 
                onSensorConfigUpdate(it, clearedAction, trippedAudio, clearedAudio, sensorEnabled) 
            }
            Spacer(modifier = Modifier.height(10.dp))
            LabeledDropdown("Action When Cleared", clearedAction, physicalActions) { 
                onSensorConfigUpdate(trippedAction, it, trippedAudio, clearedAudio, sensorEnabled) 
            }
            Spacer(modifier = Modifier.height(10.dp))
            LabeledDropdown("Audio Sound When Tripped", trippedAudio, audioActions) { 
                onSensorConfigUpdate(trippedAction, clearedAction, it, clearedAudio, sensorEnabled) 
            }
            Spacer(modifier = Modifier.height(10.dp))
            LabeledDropdown("Audio Sound When Cleared", clearedAudio, audioActions) { 
                onSensorConfigUpdate(trippedAction, clearedAction, trippedAudio, it, sensorEnabled) 
            }

            Spacer(modifier = Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = notifyOnTrip,
                    onCheckedChange = { onNotifyToggle(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = PrimaryColor, checkedTrackColor = Color(0xFF334155))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Push Notification on Trip", color = TextColor)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        CardContainer(title = "Robot Movement") {
            val buttons = listOf(
                "forward" to BtnBlue, "backward" to BtnBlue,
                "step_forward" to BtnBlue, "step_backward" to BtnBlue,
                "leap_forward" to BtnBlue, "crawl" to BtnOrange,
                "left_wave" to BtnPurple, "right_wave" to BtnPurple,
                "back_left_wave" to BtnPurple, "back_right_wave" to BtnPurple,
                "sit" to BtnOrange, "stand" to BtnOrange,
                "stretch_down" to BtnPurple, "stretch_back" to BtnPurple,
                "stop" to BtnRed
            )
            
            buttons.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (action, color) ->
                        HtmlButton(action.uppercase().replace("_", " "), color, Modifier.weight(1f).padding(4.dp)) { onRobotAction(action) }
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f).padding(4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        CardContainer(title = "Manual Joint Control") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sync Legs", color = TextColor, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = { onSyncToggle(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = PrimaryColor, checkedTrackColor = Color(0xFF334155))
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            MotorSlider("Low Left Leg (IO12)", llAngle, { onSliderChanged("ll", it) }, onSliderChangeFinished)
            MotorSlider("High Left Shoulder (IO11)", hlAngle, { onSliderChanged("hl", it) }, onSliderChangeFinished)
            MotorSlider("Low Right Leg (IO9)", lrAngle, { onSliderChanged("lr", it) }, onSliderChangeFinished)
            MotorSlider("High Right Shoulder (IO10)", hrAngle, { onSliderChanged("hr", it) }, onSliderChangeFinished)
        }
    }
}

@Composable
fun AudioStreamPlayer(ipAddress: String, active: Boolean) {
    if (active) {
        val context = LocalContext.current
        val webView = remember {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = WebViewClient()
            }
        }

        DisposableEffect(ipAddress) {
            val html = "<html><body style='margin:0;padding:0;'><audio id='aud' controls autoplay style='width:100%;height:50px;'><source src='http://${ipAddress}:82/' type='audio/wav'></audio></body></html>"
            webView.loadDataWithBaseURL("http://${ipAddress}/", html, "text/html", "UTF-8", null)
            
            onDispose {
                webView.stopLoading()
                webView.loadUrl("about:blank")
            }
        }

        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().height(50.dp))
    }
}