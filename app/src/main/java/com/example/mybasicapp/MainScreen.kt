// app/src/main/java/com/example/mybasicapp/MainScreen.kt
package com.example.mybasicapp

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    var isBleConnected by remember { mutableStateOf(false) }
    var bleStatusText by remember { mutableStateOf("BLE Idle") }
    var isStealthMode by remember { mutableStateOf(false) }
    
    val isOnline = isPolling || isBleConnected

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
    var discoveredIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var ipsDropdownExpanded by remember { mutableStateOf(false) }

    var syncEnabled by remember { mutableStateOf(false) }
    var llAngle by remember { mutableStateOf(90f) }
    var hlAngle by remember { mutableStateOf(90f) }
    var lrAngle by remember { mutableStateOf(90f) }
    var hrAngle by remember { mutableStateOf(90f) }
    var activeDrag by remember { mutableStateOf<String?>(null) }
    var pendingServoPayload by remember { mutableStateOf<JSONObject?>(null) }
    var notifyOnTrip by remember { mutableStateOf(true) }

    fun dispatchCommand(actionOrEndpoint: String, jsonPayload: JSONObject? = null) {
        if (RobotBleController.isConnected) {
            val cmd = jsonPayload?.toString() ?: actionOrEndpoint
            RobotBleController.sendBleCommand(cmd)
        }
        
        if (isPolling) {
            scope.launch(Dispatchers.IO) {
                try {
                    val endpoint = if (actionOrEndpoint.startsWith("/")) actionOrEndpoint else "/action"
                    val payload = jsonPayload ?: JSONObject().apply { put("action", actionOrEndpoint) }
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
    }

    LaunchedEffect(pendingServoPayload) {
        pendingServoPayload?.let {
            delay(40)
            if (RobotBleController.isConnected) {
                RobotBleController.sendBleCommand(it.toString())
            }
            if (isPolling) {
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
                        }
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                }
                delay(800)
            }
        }
    }

    fun sendGetRequest(endpoint: String) {
        if (RobotBleController.isConnected && endpoint.contains("claw")) {
            val cmdValue = endpoint.substringAfter("cmd=", "").substringBefore("&")
            val angleValue = endpoint.substringAfter("angle=", "").substringBefore("&")
            if (cmdValue.isNotEmpty()) {
                RobotBleController.sendBleCommand("claw:$cmdValue")
            } else if (angleValue.isNotEmpty()) {
                RobotBleController.sendBleCommand("claw_angle:$angleValue")
            }
        }
        if (isPolling) {
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
    }

    val pagerState = rememberPagerState(pageCount = { 4 })
    val tabs = listOf("Wi-Fi Setup", "Robot & Audio", "Claw", "Camera")

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text("ESP32 IP", color = PrimaryColor) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryColor, unfocusedBorderColor = BtnGray,
                            focusedTextColor = TextColor, unfocusedTextColor = TextColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    DropdownMenu(
                        expanded = ipsDropdownExpanded,
                        onDismissRequest = { ipsDropdownExpanded = false },
                        modifier = Modifier.background(CardColor)
                    ) {
                        discoveredIps.forEach { ip ->
                            DropdownMenuItem(
                                text = { Text(ip, color = TextColor, fontWeight = FontWeight.Bold) },
                                onClick = {
                                    ipAddress = ip
                                    ipsDropdownExpanded = false
                                    isPolling = true 
                                    Toast.makeText(context, "Connecting to $ip", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                
                HtmlButton(
                    text = if (isScanningSubnet) "${(subnetProgress * 100).toInt()}%" else "Find",
                    color = if (isScanningSubnet) BtnOrange else BtnPurple,
                    modifier = Modifier.width(65.dp)
                ) {
                    if (!isScanningSubnet) {
                        isScanningSubnet = true
                        scope.launch {
                            val udpIp = findRobotViaUDP()
                            if (udpIp != null) {
                                ipAddress = udpIp
                                isPolling = true
                                Toast.makeText(context, "Found Robot via UDP!", Toast.LENGTH_SHORT).show()
                                isScanningSubnet = false
                                return@launch
                            }

                            var mdnsFound = false
                            findRobotViaMDNS(context) { mdnsIp ->
                                if (!mdnsFound) {
                                    mdnsFound = true
                                    ipAddress = mdnsIp
                                    isPolling = true
                                    Toast.makeText(context, "Found Robot via mDNS!", Toast.LENGTH_SHORT).show()
                                    isScanningSubnet = false
                                }
                            }
                            delay(4000) 

                            if (!mdnsFound && isScanningSubnet) {
                                scanSubnetForWebServers(
                                    context = context,
                                    scope = scope,
                                    onProgress = { progress -> subnetProgress = progress },
                                    onFinished = { foundIps ->
                                        isScanningSubnet = false
                                        if (foundIps.isNotEmpty()) {
                                            discoveredIps = foundIps
                                            ipsDropdownExpanded = true
                                            Toast.makeText(context, "Select an IP from the list!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "No devices found on the network.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))

                HtmlButton(
                    text = if (isBleConnected) "BLE On" else "BLE",
                    color = if (isBleConnected) BtnPurple else BtnGray,
                    modifier = Modifier.width(65.dp)
                ) {
                    if (isBleConnected) {
                        RobotBleController.disconnect()
                        isBleConnected = false
                        bleStatusText = "BLE Disconnected"
                    } else {
                        RobotBleController.connectToRobot(
                            context = context,
                            onStatus = { status -> bleStatusText = status },
                            onConnectedStateChange = { connected -> isBleConnected = connected }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                HtmlButton(
                    text = "Stealth",
                    color = Color(0xFF1E293B),
                    modifier = Modifier.width(65.dp)
                ) {
                    isStealthMode = true
                    (context as? Activity)?.window?.attributes = (context as? Activity)?.window?.attributes?.apply {
                        screenBrightness = 0.01f
                    }
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
                    text = when {
                        isPolling && isBleConnected -> "Connected [Online (Wi-Fi + BLE)]"
                        isBleConnected -> "Connected [Online (BLE + Gamepad Active)]"
                        isPolling -> "Connected [Online (Wi-Fi HTTP)]"
                        else -> "Searching for ESP Robot [Offline]"
                    },
                    color = if (isOnline) Color(0xFFD1FAE5) else Color(0xFFFEF3C7),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
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
                            dispatchCommand("/save", JSONObject().apply { put("ssid", ssid); put("pass", pass) })
                        },
                        onForceAp = { dispatchCommand("/switch_to_ap", JSONObject()) },
                        onUseWifi = { dispatchCommand("/switch_to_wifi", JSONObject()) },
                        onBleIpReceived = { ip -> 
                            ipAddress = ip
                            isPolling = true
                        }
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
                            dispatchCommand("/sensor", json)
                            trippedAction = tAction
                            clearedAction = cAction
                            trippedAudio = tAudio
                            clearedAudio = cAudio
                            sensorEnabled = enabled
                        },
                        onRobotAction = { act ->
                            dispatchCommand(act)
                        },
                        onAudioCommand = { endpoint, payload -> dispatchCommand(endpoint, payload) },
                        onWalkieTalkie = {
                            Toast.makeText(context, "Mic Transmitting to Robot...", Toast.LENGTH_SHORT).show()
                        }
                    )
                    2 -> ClawTab(
                        onClawCommand = { cmd -> 
                            if (RobotBleController.isConnected) {
                                RobotBleController.sendBleCommand("claw:$cmd")
                            }
                            sendGetRequest("/claw?cmd=$cmd") 
                        },
                        onClawAngle = { angle -> 
                            if (RobotBleController.isConnected) {
                                RobotBleController.sendBleCommand("claw_angle:$angle")
                            }
                            sendGetRequest("/claw?angle=$angle") 
                        }
                    )
                    3 -> CameraTab(
                        ipAddress = ipAddress,
                        onFlipCamera = { dispatchCommand("/cam_flip", JSONObject()) }
                    )
                }
            }
        }

        // Pure OLED Black Screen Overlay for 0mW Stealth Mode
        if (isStealthMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        isStealthMode = false
                        (context as? Activity)?.window?.attributes = (context as? Activity)?.window?.attributes?.apply {
                            screenBrightness = -1f // Restore default brightness
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Stealth Mode Active\n(Gamepads Active - Tap 2x to Wake)",
                    color = Color(0xFF334155),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}