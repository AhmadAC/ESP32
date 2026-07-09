// app/src/main/java/com/example/mybasicapp/MainScreen.kt
package com.example.mybasicapp

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(hasAudioPermission: Boolean, hasLocationPermission: Boolean, onTriggerNotification: (String) -> Unit) {
    var ipAddress by remember { mutableStateOf("192.168.4.1") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var isPolling by remember { mutableStateOf(false) }
    var isOnline by remember { mutableStateOf(false) }
    var sensorEnabled by remember { mutableStateOf(false) }
    var sensorDistance by remember { mutableStateOf(0.0) }
    var safetyLock by remember { mutableStateOf(false) }
    var trippedAction by remember { mutableStateOf("stop") }
    var clearedAction by remember { mutableStateOf("stand") }
    var trippedAudio by remember { mutableStateOf("none") }
    var clearedAudio by remember { mutableStateOf("none") }
    var audioVolume by remember { mutableStateOf(50f) }
    var lastLockState by remember { mutableStateOf(false) }
    
    var isScanningSubnet by remember { mutableStateOf(false) }
    var subnetProgress by remember { mutableStateOf(0f) }

    var syncEnabled by remember { mutableStateOf(false) }
    var llAngle by remember { mutableStateOf(90f) }
    var hlAngle by remember { mutableStateOf(90f) }
    var lrAngle by remember { mutableStateOf(90f) }
    var hrAngle by remember { mutableStateOf(90f) }
    var activeDrag by remember { mutableStateOf<String?>(null) }
    var pendingServoPayload by remember { mutableStateOf<JSONObject?>(null) }
    var notifyOnTrip by remember { mutableStateOf(true) }

    LaunchedEffect(pendingServoPayload) {
        pendingServoPayload?.let {
            delay(40)
            try {
                val url = URL("http://${ipAddress}/servo")
                withContext(Dispatchers.IO) {
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream).use { writer -> writer.write(it.toString()) }
                    conn.responseCode
                    conn.disconnect()
                }
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(isPolling, ipAddress) {
        if (isPolling) {
            while (true) {
                try {
                    val url = URL("http://${ipAddress}/angles")
                    withContext(Dispatchers.IO) {
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 2000
                        connection.readTimeout = 2000
                        
                        if (connection.responseCode == 200) {
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(response)
                            
                            withContext(Dispatchers.Main) {
                                isOnline = true
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
                                
                                if (activeDrag != "ll" && json.has("low_left")) {
                                    llAngle = json.getJSONObject("low_left").optDouble("angle", 90.0).toFloat()
                                }
                                if (activeDrag != "hl" && json.has("high_left")) {
                                    hlAngle = json.getJSONObject("high_left").optDouble("angle", 90.0).toFloat()
                                }
                                if (activeDrag != "lr" && json.has("low_right")) {
                                    lrAngle = json.getJSONObject("low_right").optDouble("angle", 90.0).toFloat()
                                }
                                if (activeDrag != "hr" && json.has("high_right")) {
                                    hrAngle = json.getJSONObject("high_right").optDouble("angle", 90.0).toFloat()
                                }

                                if (safetyLock && !lastLockState) {
                                    if (notifyOnTrip) {
                                        onTriggerNotification("Obstacle Detected! Distance: ${sensorDistance}cm")
                                    }
                                }
                                lastLockState = safetyLock
                            }
                        } else {
                            withContext(Dispatchers.Main) { isOnline = false }
                        }
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { isOnline = false }
                }
                delay(800)
            }
        } else {
            isOnline = false
        }
    }

    fun sendPostRequest(endpoint: String, payload: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://${ipAddress}${endpoint}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                conn.responseCode 
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }
    
    fun sendGetRequest(endpoint: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://${ipAddress}${endpoint}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    val pagerState = rememberPagerState(pageCount = { 4 })
    val tabs = listOf("Wi-Fi Setup", "Robot & Audio", "Claw", "Camera")

    Column(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                label = { Text("ESP32 IP", color = PrimaryColor) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryColor, unfocusedBorderColor = BtnGray,
                    focusedTextColor = TextColor, unfocusedTextColor = TextColor
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            
            HtmlButton(
                text = if (isScanningSubnet) "${(subnetProgress * 100).toInt()}%" else "Auto-Find",
                color = if (isScanningSubnet) BtnOrange else BtnPurple,
                modifier = Modifier.width(100.dp)
            ) {
                if (!isScanningSubnet) {
                    isScanningSubnet = true
                    discoverEspRobotOnSubnet(
                        context = context,
                        scope = scope,
                        onProgress = { progress -> subnetProgress = progress },
                        onFound = { foundIp ->
                            ipAddress = foundIp
                            isPolling = true
                            Toast.makeText(context, "ESP Found at $foundIp!", Toast.LENGTH_SHORT).show()
                        },
                        onFinished = { success ->
                            isScanningSubnet = false
                            if (!success) {
                                Toast.makeText(context, "Could not locate ESP Robot.", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            
            HtmlButton(
                text = if (isPolling) "Disconnect" else "Connect",
                color = if (isPolling) BtnRed else BtnGreen
            ) {
                isPolling = !isPolling
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 5.dp)
                .background(if (isOnline) Color(0xFF064E3B) else Color(0xFF451A03), RoundedCornerShape(10.dp))
                .border(width = 1.dp, color = if (isOnline) Color(0xFF10B981) else Color(0xFFF59E0B), shape = RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).background(if (isOnline) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isOnline) "Connected to ESP Robot [Online]" else "Searching for ESP Robot [Offline]",
                color = if (isOnline) Color(0xFFD1FAE5) else Color(0xFFFEF3C7),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

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
                    text = { Text(title, color = if (pagerState.currentPage == index) PrimaryColor else BtnGray, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> WifiTab(
                    ipAddress = ipAddress,
                    hasLocationPermission = hasLocationPermission,
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
                            "ll" -> { 
                                llAngle = angle
                                payload.put("ll", angle.toInt())
                                if (syncEnabled) { 
                                    lrAngle = angle
                                    payload.put("lr", angle.toInt()) 
                                } 
                            }
                            "hl" -> { 
                                hlAngle = angle
                                payload.put("hl", angle.toInt())
                                if (syncEnabled) { 
                                    hrAngle = angle
                                    payload.put("hr", angle.toInt()) 
                                } 
                            }
                            "lr" -> { 
                                lrAngle = angle
                                payload.put("lr", angle.toInt())
                                if (syncEnabled) { 
                                    llAngle = angle
                                    payload.put("ll", angle.toInt()) 
                                } 
                            }
                            "hr" -> { 
                                hrAngle = angle
                                payload.put("hr", angle.toInt())
                                if (syncEnabled) { 
                                    hlAngle = angle
                                    payload.put("hl", angle.toInt()) 
                                } 
                            }
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
                        trippedAction = tAction
                        clearedAction = cAction
                        trippedAudio = tAudio
                        clearedAudio = cAudio
                        sensorEnabled = enabled
                    },
                    onRobotAction = { act ->
                        val json = JSONObject().apply { put("action", act) }
                        sendPostRequest("/action", json)
                    },
                    onAudioCommand = { endpoint, payload -> sendPostRequest(endpoint, payload) },
                    onWalkieTalkie = {
                        Toast.makeText(context, "Mic Transmitting to Robot...", Toast.LENGTH_SHORT).show()
                    }
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