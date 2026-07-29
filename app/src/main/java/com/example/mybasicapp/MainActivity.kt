// app/src/main/java/com/example/mybasicapp/MainActivity.kt
package com.example.mybasicapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var hasNotificationPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)
    private var hasBluetoothPermission by mutableStateOf(false)

    private var lastAxisX = 0f
    private var lastAxisY = 0f
    private var lastHatX = 0f
    private var lastHatY = 0f
    private var lastClawAngle = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
            hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
            hasBluetoothPermission = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: hasBluetoothPermission
            
            startGamepadService()
        }

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO, 
            Manifest.permission.ACCESS_FINE_LOCATION, 
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WAKE_LOCK
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent {
            MainScreen(
                hasAudioPermission = hasAudioPermission,
                hasLocationPermission = hasLocationPermission,
                onTriggerNotification = { msg ->
                    if (hasNotificationPermission) sendNotification("ESP32 Sensor Alert", msg)
                }
            )
        }
    }

    private fun startGamepadService() {
        val serviceIntent = Intent(this, GamepadControlService::class.java).apply {
            action = GamepadControlService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    // Intercept Nintendo Switch Controller Button Presses
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        if (isGamepad && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Switch Pro B Button (Stop Robot / Close Claw)
                KeyEvent.KEYCODE_BUTTON_B -> {
                    dispatchClawCommand("close")
                    dispatchRobotAction("stop")
                    return true
                }
                // Switch Pro A Button (Stand Robot / Open Claw)
                KeyEvent.KEYCODE_BUTTON_A -> {
                    dispatchClawCommand("open")
                    dispatchRobotAction("stand")
                    return true
                }
                // Switch Pro Y Button (Sit Robot / Half Open Claw)
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    dispatchClawCommand("half_open")
                    dispatchRobotAction("sit")
                    return true
                }
                // Switch Pro X Button (Leap Robot / Half Close Claw)
                KeyEvent.KEYCODE_BUTTON_X -> {
                    dispatchClawCommand("half_close")
                    dispatchRobotAction("leap_forward")
                    return true
                }
                // Switch Pro L Button (Stretch Down)
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    dispatchClawCommand("open")
                    dispatchRobotAction("stretch_down")
                    return true
                }
                // Switch Pro R Button (Stretch Back)
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    dispatchClawCommand("close")
                    dispatchRobotAction("stretch_back")
                    return true
                }
                // Switch Pro ZL Button (Crawl)
                KeyEvent.KEYCODE_BUTTON_L2 -> {
                    dispatchClawCommand("half_open")
                    dispatchRobotAction("crawl")
                    return true
                }
                // D-Pad Controls
                KeyEvent.KEYCODE_DPAD_UP -> {
                    dispatchClawCommand("open")
                    dispatchRobotAction("forward")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dispatchClawCommand("close")
                    dispatchRobotAction("backward")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    dispatchClawCommand("half_open")
                    dispatchRobotAction("left_wave")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    dispatchClawCommand("half_close")
                    dispatchRobotAction("right_wave")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Intercept Left Joystick Motion for Smooth Claw Angle Slider (0 to 180 degrees)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y) // Left Joystick Y-Axis

            // Smooth Analog Joystick Control for Claw Angle (0 to 180 degrees)
            // Push UP (axisY = -1.0) -> 180 degrees (Fully Open)
            // Center (axisY = 0.0)   -> 90 degrees (Half Open)
            // Push DOWN (axisY = 1.0) -> 0 degrees (Fully Closed)
            val mappedAngle = (((1.0f - axisY) / 2.0f) * 180f).toInt().coerceIn(0, 180)

            // Only transmit if the angle changed by at least 3 degrees (prevents BLE flood)
            if (Math.abs(mappedAngle - lastClawAngle) >= 3) {
                lastClawAngle = mappedAngle
                if (RobotBleController.isConnected) {
                    RobotBleController.sendBleCommand("claw_angle:$mappedAngle")
                }
            }

            // D-Pad Hat Motion
            if (hatY < -0.5f && lastHatY >= -0.5f) {
                dispatchClawCommand("open")
                dispatchRobotAction("forward")
            } else if (hatY > 0.5f && lastHatY <= 0.5f) {
                dispatchClawCommand("close")
                dispatchRobotAction("backward")
            } else if (hatX < -0.5f && lastHatX >= -0.5f) {
                dispatchClawCommand("half_open")
                dispatchRobotAction("left_wave")
            } else if (hatX > 0.5f && lastHatX <= 0.5f) {
                dispatchClawCommand("half_close")
                dispatchRobotAction("right_wave")
            }

            lastHatX = hatX
            lastHatY = hatY
            lastAxisX = axisX
            lastAxisY = axisY

            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun dispatchClawCommand(command: String) {
        if (RobotBleController.isConnected) {
            RobotBleController.sendBleCommand("claw:$command")
        }
    }

    private fun dispatchRobotAction(action: String) {
        if (RobotBleController.isConnected) {
            RobotBleController.sendBleCommand("action:$action")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("SENSOR_CHANNEL", "Sensor Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for ESP32 Ultrasonic Sensor"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
            }
        }
    }
}