// app/src/main/java/com/example/mybasicapp/MainActivity.kt
package com.example.mybasicapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

// Exact colors matching your ESP32 web server CSS
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

class MainActivity : ComponentActivity() {

    private var hasNotificationPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        createNotificationChannel()

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
            hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
        }

        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                hasNotificationPermission = true
            }
        } else {
            hasNotificationPermission = true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        } else {
            hasAudioPermission = true
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setContent {
            MainScreen(
                hasAudioPermission = hasAudioPermission,
                onTriggerNotification = { message ->
                    if (hasNotificationPermission) {
                        sendNotification("ESP32 Sensor Alert", message)
                    }
                }
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("SENSOR_CHANNEL", "Sensor Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for ESP32 Ultrasonic Sensor"
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(title: String, content: String) {
        val builder = NotificationCompat.Builder(this, "SENSOR_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            try {
                notify(System.currentTimeMillis().toInt(), builder.build())
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(hasAudioPermission: Boolean, onTriggerNotification: (String) -> Unit) {
    var ipAddress by remember { mutableStateOf("192.168.4.1") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Live ESP32 State variables
    var isPolling by remember { mutableStateOf(false) }
    var sensorEnabled by remember { mutableStateOf(false) }
    var sensorDistance by remember { mutableStateOf(0.0) }
    var safetyLock by remember { mutableStateOf(false) }
    var trippedAction by remember { mutableStateOf("stop") }
    var clearedAction by remember { mutableStateOf("stand") }
    var trippedAudio by remember { mutableStateOf("none") }
    var clearedAudio by remember { mutableStateOf("none") }
    var audioVolume by remember { mutableStateOf(50f) }
    var lastLockState by remember { mutableStateOf(false) }
    
    // Motor Variables
    var syncEnabled by remember { mutableStateOf(false) }
    var llAngle by remember { mutableStateOf(90f) }
    var hlAngle by remember { mutableStateOf(90f) }
    var lrAngle by remember { mutableStateOf(90f) }
    var hrAngle by remember { mutableStateOf(90f) }
    var activeDrag by remember { mutableStateOf<String?>(null) }
    var pendingServoPayload by remember { mutableStateOf<JSONObject?>(null) }
    
    // Notification Toggle State
    var notifyOnTrip by remember { mutableStateOf(true) }

    // Servo network throttler (prevents flooding the ESP32 while dragging sliders)
    LaunchedEffect(pendingServoPayload) {
        pendingServoPayload?.let {
            delay(40) // 40ms debounce
            try {
                val url = URL("http://$ipAddress/servo")
                withContext(Dispatchers.IO) {
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream).use { writer -> writer.write(it.toString()) }
                    conn.responseCode
                    conn.disconnect()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // Polling Loop for updating states & checking if we need to throw a native notification
    LaunchedEffect(isPolling, ipAddress) {
        if (isPolling) {
            while (true) {
                try {
                    val url = URL("http://$ipAddress/angles")
                    withContext(Dispatchers.IO) {
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 2000
                        connection.readTimeout = 2000
                        
                        if (connection.responseCode == 200) {
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(response)
                            
                            withContext(Dispatchers.Main) {
                                sensorEnabled = json.optBoolean("sensor_enabled", false)
                                safetyLock = json.optBoolean("safety_lock", false)
                                sensorDistance = json.optDouble("sensor_distance", -1.0)
                                trippedAction = json.optString("sensor_tripped_action", "stop")
                                clearedAction = json.optString("sensor_cleared_action", "stand")
                                trippedAudio = json.optString("sensor_tripped_audio", "none")
                                clearedAudio = json.optString("sensor_cleared_audio", "none")
                                
                                if (json.has("audio_volume")) {
                                    audioVolume = json.optDouble("audio_volume", 50.0).toFloat()
                                }
                                
                                if (activeDrag != "ll" && json.has("low_left")) llAngle = json.getJSONObject("low_left").optDouble("angle", 90.0).toFloat()
                                if (activeDrag != "hl" && json.has("high_left")) hlAngle = json.getJSONObject("high_left").optDouble("angle", 90.0).toFloat()
                                if (activeDrag != "lr" && json.has("low_right")) lrAngle = json.getJSONObject("low_right").optDouble("angle", 90.0).toFloat()
                                if (activeDrag != "hr" && json.has("high_right")) hrAngle = json.getJSONObject("high_right").optDouble("angle", 90.0).toFloat()

                                if (safetyLock && !lastLockState) {
                                    if (notifyOnTrip) {
                                        onTriggerNotification("Obstacle Detected! Distance: ${sensorDistance}cm")
                                    }
                                }
                                lastLockState = safetyLock
                            }
                        }
                        connection.disconnect()
                    }
                } catch (e: Exception) {}
                delay(800) // Poll interval
            }
        }
    }

    fun sendPostRequest(endpoint: String, payload: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://$ipAddress$endpoint")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                conn.responseCode 
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
    
    fun sendGetRequest(endpoint: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://$ipAddress$endpoint")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    val pagerState = rememberPagerState(pageCount = { 4 })
    val tabs = listOf("Wi-Fi Setup", "Robot & Audio", "Claw", "Camera")

    Column(modifier = Modifier.fillMaxSize().background(BgColor)) {
        // IP Address & Connection Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                label = { Text("ESP32 IP", color = PrimaryColor) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryColor,
                    unfocusedBorderColor = BtnGray,
                    focusedTextColor = TextColor,
                    unfocusedTextColor = TextColor
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            HtmlButton(
                text = if (isPolling) "Disconnect" else "Connect",
                color = if (isPolling) BtnRed else BtnGreen
            ) {
                isPolling = !isPolling
            }
        }

        // Swipeable Tabs
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = CardColor,
            contentColor = PrimaryColor,
            divider = { HorizontalDivider(color = PrimaryColor) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title, color = if (pagerState.currentPage == index) PrimaryColor else BtnGray, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> WifiTab(
                    ipAddress = ipAddress,
                    onSaveWifi = { ssid, pass -> 
                        sendPostRequest("/save", JSONObject().apply { put("ssid", ssid); put("pass", pass) })
                    },
                    onForceAp = { sendPostRequest("/switch_to_ap", JSONObject()) },
                    onUseWifi = { sendPostRequest("/switch_to_wifi", JSONObject()) }
                )
                1 -> RobotTab(
                    ipAddress = ipAddress,
                    sensorEnabled = sensorEnabled,
                    safetyLock = safetyLock,
                    sensorDistance = sensorDistance,
                    notifyOnTrip = notifyOnTrip,
                    trippedAction = trippedAction,
                    clearedAction = clearedAction,
                    trippedAudio = trippedAudio,
                    clearedAudio = clearedAudio,
                    audioVolume = audioVolume,
                    syncEnabled = syncEnabled,
                    llAngle = llAngle,
                    hlAngle = hlAngle,
                    lrAngle = lrAngle,
                    hrAngle = hrAngle,
                    hasAudioPermission = hasAudioPermission,
                    onNotifyToggle = { notifyOnTrip = it },
                    onSyncToggle = { syncEnabled = it },
                    onSliderChanged = { id, angle ->
                        activeDrag = id
                        val payload = JSONObject()
                        when (id) {
                            "ll" -> { llAngle = angle; payload.put("ll", angle.toInt()); if (syncEnabled) { lrAngle = angle; payload.put("lr", angle.toInt()) } }
                            "hl" -> { hlAngle = angle; payload.put("hl", angle.toInt()); if (syncEnabled) { hrAngle = angle; payload.put("hr", angle.toInt()) } }
                            "lr" -> { lrAngle = angle; payload.put("lr", angle.toInt()); if (syncEnabled) { llAngle = angle; payload.put("ll", angle.toInt()) } }
                            "hr" -> { hrAngle = angle; payload.put("hr", angle.toInt()); if (syncEnabled) { hlAngle = angle; payload.put("hl", angle.toInt()) } }
                        }
                        pendingServoPayload = payload
                    },
                    onSliderChangeFinished = { activeDrag = null },
                    onSensorConfigUpdate = { tAction, cAction, tAudio, cAudio, enabled ->
                        val json = JSONObject().apply {
                            put("enabled", enabled)
                            put("tripped_action", tAction)
                            put("cleared_action", cAction)
                            put("tripped_audio", tAudio)
                            put("cleared_audio", cAudio)
                        }
                        sendPostRequest("/sensor", json)
                        trippedAction = tAction; clearedAction = cAction; trippedAudio = tAudio; clearedAudio = cAudio; sensorEnabled = enabled
                    },
                    onRobotAction = { act ->
                        val json = JSONObject().apply { put("action", act) }
                        sendPostRequest("/action", json)
                    },
                    onAudioCommand = { endpoint, payload -> sendPostRequest(endpoint, payload) }
                )
                2 -> ClawTab(
                    onClawCommand = { cmd -> sendGetRequest("/claw?cmd=$cmd") },
                    onClawAngle = { angle -> sendGetRequest("/claw?angle=$angle") }
                )
                3 -> CameraTab(
                    ipAddress = ipAddress,
                    onFlipCamera = { sendPostRequest("/cam_flip", JSONObject()) }
                )
            }
        }
    }
}

@Composable
fun WifiTab(
    ipAddress: String,
    onSaveWifi: (String, String) -> Unit,
    onForceAp: () -> Unit,
    onUseWifi: () -> Unit
) {
    var ssidList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())
    ) {
        CardContainer(title = "Wi-Fi Provisioning") {
            HtmlButton(
                text = if (isScanning) "Scanning..." else "Scan Wi-Fi Networks",
                color = BtnGray,
                modifier = Modifier.fillMaxWidth()
            ) {
                isScanning = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val url = URL("http://$ipAddress/scan")
                        val conn = url.openConnection() as HttpURLConnection
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = JSONArray(resp)
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        
                        withContext(Dispatchers.Main) {
                            ssidList = list
                            if (list.isNotEmpty()) selectedSsid = list[0]
                            isScanning = false
                            Toast.makeText(context, "Found ${list.size} networks", Toast.LENGTH_SHORT).show()
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isScanning = false
                            Toast.makeText(context, "Scan Failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(15.dp))

            LabeledDropdown("Target SSID", selectedSsid, ssidList) { selectedSsid = it }
            
            Spacer(modifier = Modifier.height(15.dp))
            OutlinedTextField(
                value = wifiPassword,
                onValueChange = { wifiPassword = it },
                label = { Text("Wi-Fi Password", color = PrimaryColor) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryColor,
                    unfocusedBorderColor = BtnGray,
                    focusedTextColor = TextColor,
                    unfocusedTextColor = TextColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(15.dp))
            HtmlButton("Save and Connect", BtnGreen, Modifier.fillMaxWidth()) {
                if (selectedSsid.isEmpty()) {
                    Toast.makeText(context, "Select an SSID first!", Toast.LENGTH_SHORT).show()
                } else {
                    onSaveWifi(selectedSsid, wifiPassword)
                    Toast.makeText(context, "Credentials Saved! Robot Rebooting...", Toast.LENGTH_LONG).show()
                }
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        CardContainer(title = "Quick Boot Mode Switch") {
            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Force AP Mode", BtnOrange, Modifier.weight(1f).padding(end = 4.dp)) { 
                    onForceAp()
                    Toast.makeText(context, "Forcing AP Mode... Rebooting...", Toast.LENGTH_LONG).show()
                }
                HtmlButton("Use Saved Wi-Fi", BtnGreen, Modifier.weight(1f).padding(start = 4.dp)) { 
                    onUseWifi()
                    Toast.makeText(context, "Switching to Wi-Fi... Rebooting...", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

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
    onAudioCommand: (String, JSONObject) -> Unit
) {
    val context = LocalContext.current
    var isStreamingMic by remember { mutableStateOf(false) }
    var socketHolder by remember { mutableStateOf<Socket?>(null) }
    var audioRecordHolder by remember { mutableStateOf<AudioRecord?>(null) }

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
                    val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                    audioRecordHolder = audioRecord
                    audioRecord.startRecording()
                    
                    val buffer = ByteArray(bufferSize)
                    while (isStreamingMic) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            outStream.write(buffer, 0, read)
                        }
                    }
                    audioRecord.stop()
                    audioRecord.release()
                }
                socket.close()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    fun stopWalkieTalkie() {
        isStreamingMic = false
        try { socketHolder?.close() } catch (e: Exception) {}
        try { audioRecordHolder?.release() } catch (e: Exception) {}
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())
    ) {
        if (safetyLock) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF7F1D1D), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("WARNING: MOTORS LOCKED - Obstacle Detected!", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(15.dp))
        }

        // ==========================================
        // AUDIO & SPEAKER OUTPUT CARD (Moved to top)
        // ==========================================
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
            
            // WALKIE-TALKIE NATIVE TOUCH IMPLEMENTATION
            val interactionSource = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        startWalkieTalkie()
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
            
            // Listen to Robot Mic
            var listenMicActive by remember { mutableStateOf(false) }
            if (listenMicActive) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    update = { view ->
                        val html = "<html><body style='margin:0;padding:0;'><audio controls autoplay style='width:100%;height:50px;'><source src='http://$ipAddress:82/' type='audio/wav'></audio></body></html>"
                        view.loadDataWithBaseURL("http://$ipAddress/", html, "text/html", "UTF-8", null)
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                )
            }
            HtmlButton(
                text = if(listenMicActive) "Stop Listening to Robot" else "Listen to Robot Mic",
                color = if(listenMicActive) BtnRed else BtnBlue,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                listenMicActive = !listenMicActive
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ==========================================
        // HYPERSONIC SENSOR CARD
        // ==========================================
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

        // ==========================================
        // ROBOT MOVEMENT CARD
        // ==========================================
        CardContainer(title = "Robot Movement") {
            val buttons: List<Pair<String, Color>> = listOf(
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

        // ==========================================
        // MANUAL JOINT CONTROL CARD
        // ==========================================
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

@Composable
fun ClawTab(
    onClawCommand: (String) -> Unit,
    onClawAngle: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())
    ) {
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
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryColor,
                    activeTrackColor = PrimaryColor
                )
            )
        }
    }
}

@Composable
fun CameraTab(ipAddress: String, onFlipCamera: () -> Unit) {
    var camActive by remember { mutableStateOf(false) }
    var camRotation by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())
    ) {
        CardContainer(title = "Live Camera Stream") {
            HtmlButton(
                text = if (camActive) "Turn Camera OFF" else "Turn Camera ON",
                color = if (camActive) BtnRed else BtnBlue,
                modifier = Modifier.fillMaxWidth()
            ) {
                camActive = !camActive
            }

            Spacer(modifier = Modifier.height(15.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (camActive) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setOnTouchListener { _, _ -> false } 
                            }
                        },
                        update = { view ->
                            val currentUrl = view.url ?: ""
                            if (!currentUrl.startsWith("data:text/html")) {
                                val html = "<html><body style='background:black;margin:0;padding:0;display:flex;align-items:center;justify-content:center;height:100%;'><img id='stream' src='http://$ipAddress:81/' style='width:100%;height:auto;transition:transform 0.2s;' /></body></html>"
                                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            }
                            view.evaluateJavascript("if(document.getElementById('stream')) document.getElementById('stream').style.transform = 'rotate(${camRotation}deg)';", null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("Camera is OFF", color = BtnGray)
                }
            }

            Spacer(modifier = Modifier.height(15.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Rotate 90°", BtnOrange, Modifier.weight(1f).padding(end = 4.dp)) { 
                    camRotation = (camRotation + 90) % 360 
                }
                HtmlButton("Flip Camera", BtnPurple, Modifier.weight(1f).padding(start = 4.dp)) { 
                    onFlipCamera() 
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            HtmlButton("Save HD Picture", BtnGreen, Modifier.fillMaxWidth()) {
                coroutineScope.launch {
                    saveImageToGallery(context, ipAddress, camRotation)
                }
            }
        }
    }
}

suspend fun saveImageToGallery(context: Context, ipAddress: String, rotationZ: Int) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$ipAddress/capture")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            
            if (conn.responseCode == 200) {
                val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                
                val finalBitmap = if (rotationZ % 360 != 0) {
                    val matrix = Matrix().apply { postRotate(rotationZ.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else bitmap

                val filename = "ESP32_Capture_${System.currentTimeMillis()}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                }
                
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    context.contentResolver.openOutputStream(it).use { out ->
                        if (out != null) {
                            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Image saved to Gallery!", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to capture image.", Toast.LENGTH_SHORT).show()
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Network Error. Image not saved.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

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
                focusedTextColor = TextColor,
                unfocusedTextColor = TextColor,
                focusedBorderColor = PrimaryColor,
                unfocusedBorderColor = BtnGray
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