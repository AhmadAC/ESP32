// app/src/main/java/com/cooper/basicapk/MainActivity.kt
package com.cooper.basicapk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        createNotificationChannel()

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            hasNotificationPermission = isGranted
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                hasNotificationPermission = true
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            hasNotificationPermission = true
        }

        setContent {
            MainScreen(
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
            val name = "Sensor Alerts"
            val descriptionText = "Notifications for ESP32 Ultrasonic Sensor"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("SENSOR_CHANNEL", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
fun MainScreen(onTriggerNotification: (String) -> Unit) {
    var ipAddress by remember { mutableStateOf("192.168.4.1") }
    val scope = rememberCoroutineScope()
    
    // Live ESP32 State variables
    var isPolling by remember { mutableStateOf(false) }
    var sensorEnabled by remember { mutableStateOf(false) }
    var sensorDistance by remember { mutableStateOf(0.0) }
    var safetyLock by remember { mutableStateOf(false) }
    var trippedAction by remember { mutableStateOf("stop") }
    var lastLockState by remember { mutableStateOf(false) }
    
    // Notification Toggle State
    var notifyOnTrip by remember { mutableStateOf(true) }

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
                                
                                // Notification Fire Logic (Fires once upon edge transition false->true)
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
                    // Ignore transient network errors
                }
                delay(800) // Poll interval
            }
        }
    }

    // Generic Network Callers
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { 3 })
    val tabs = listOf("Robot & Sensor", "Claw", "Camera")

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
            contentColor = PrimaryColor
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title, color = if (pagerState.currentPage == index) PrimaryColor else BtnGray, fontWeight = FontWeight.Bold) }
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> RobotTab(
                    sensorEnabled = sensorEnabled,
                    safetyLock = safetyLock,
                    sensorDistance = sensorDistance,
                    notifyOnTrip = notifyOnTrip,
                    trippedAction = trippedAction,
                    onNotifyToggle = { notifyOnTrip = it },
                    onActionChange = { act -> 
                        trippedAction = act
                        val json = JSONObject().apply { put("tripped_action", act) }
                        sendPostRequest("/sensor", json)
                    },
                    onToggleSensor = {
                        val json = JSONObject().apply { put("enabled", !sensorEnabled) }
                        sendPostRequest("/sensor", json)
                        sensorEnabled = !sensorEnabled
                    },
                    onRobotAction = { act ->
                        val json = JSONObject().apply { put("action", act) }
                        sendPostRequest("/action", json)
                    }
                )
                1 -> ClawTab(
                    onClawCommand = { cmd -> sendGetRequest("/claw?cmd=$cmd") },
                    onClawAngle = { angle -> sendGetRequest("/claw?angle=$angle") }
                )
                2 -> CameraTab(ipAddress = ipAddress)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotTab(
    sensorEnabled: Boolean,
    safetyLock: Boolean,
    sensorDistance: Double,
    notifyOnTrip: Boolean,
    trippedAction: String,
    onNotifyToggle: (Boolean) -> Unit,
    onActionChange: (String) -> Unit,
    onToggleSensor: () -> Unit,
    onRobotAction: (String) -> Unit
) {
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

        CardContainer(title = "Hypersonic Sensor Settings") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HtmlButton(
                    text = if (sensorEnabled) "Disable Sensor" else "Enable Sensor",
                    color = if (sensorEnabled) BtnRed else BtnGreen,
                    modifier = Modifier.weight(1f)
                ) {
                    onToggleSensor()
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
            
            var expanded by remember { mutableStateOf(false) }
            val actions = listOf("stop", "forward", "backward", "sit", "stand", "none")

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = trippedAction.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Action When Tripped", color = PrimaryColor) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                        focusedTextColor = TextColor,
                        unfocusedTextColor = TextColor
                    ),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(CardColor)
                ) {
                    actions.forEach { act ->
                        DropdownMenuItem(
                            text = { Text(act.uppercase(), color = TextColor) },
                            onClick = {
                                onActionChange(act)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = notifyOnTrip,
                    onCheckedChange = { onNotifyToggle(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = PrimaryColor, checkedTrackColor = Color(0xFF334155))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Phone Push Notification on Trip", color = TextColor)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        CardContainer(title = "Robot Movement") {
            // Explicitly defining the type avoids a compiler resolution bug
            val buttons: List<Pair<String, Color>> = listOf(
                "forward" to BtnBlue, "backward" to BtnBlue,
                "step_forward" to BtnBlue, "step_backward" to BtnBlue,
                "left_wave" to BtnPurple, "right_wave" to BtnPurple,
                "sit" to BtnOrange, "stand" to BtnOrange,
                "stretch_down" to BtnPurple, "stretch_back" to BtnPurple,
                "crawl" to BtnOrange, "stop" to BtnRed
            )
            
            buttons.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (action, color) ->
                        HtmlButton(
                            text = action.uppercase().replace("_", " "),
                            color = color,
                            modifier = Modifier.weight(1f).padding(4.dp)
                        ) {
                            onRobotAction(action)
                        }
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f).padding(4.dp))
                    }
                }
            }
        }
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
fun CameraTab(ipAddress: String) {
    var camActive by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(15.dp)
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

            if (camActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                // Ignore touch interactions to ensure the user can still swipe the tab layout
                                setOnTouchListener { _, _ -> false } 
                                webViewClient = WebViewClient()
                                loadUrl("http://$ipAddress:81/")
                            }
                        },
                        update = { view ->
                            view.loadUrl("http://$ipAddress:81/")
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Camera is OFF", color = BtnGray)
                }
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