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

    // Intercept Nintendo Switch Controller Button Presses (B, A, Y, X, L, R, ZL, ZR, D-Pad)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        if (isGamepad && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Switch Pro B Button (Stop)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A -> {
                    dispatchRobotOrClawCommand("stop", "close")
                    return true
                }
                // Switch Pro A Button (Stand / Open)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B -> {
                    dispatchRobotOrClawCommand("stand", "open")
                    return true
                }
                // Switch Pro Y Button (Sit / Half Open)
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X -> {
                    dispatchRobotOrClawCommand("sit", "half_open")
                    return true
                }
                // Switch Pro X Button (Leap Forward / Half Close)
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> {
                    dispatchRobotOrClawCommand("leap_forward", "half_close")
                    return true
                }
                // Switch Pro L Button (Stretch Down)
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    dispatchRobotOrClawCommand("stretch_down", "open")
                    return true
                }
                // Switch Pro R Button (Stretch Back)
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    dispatchRobotOrClawCommand("stretch_back", "close")
                    return true
                }
                // Switch Pro ZL Button (Crawl)
                KeyEvent.KEYCODE_BUTTON_L2 -> {
                    dispatchRobotOrClawCommand("crawl", "half_open")
                    return true
                }
                // D-Pad Navigation
                KeyEvent.KEYCODE_DPAD_UP -> {
                    dispatchRobotOrClawCommand("forward", "open")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dispatchRobotOrClawCommand("backward", "close")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    dispatchRobotOrClawCommand("left_wave", "half_open")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    dispatchRobotOrClawCommand("right_wave", "half_close")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Intercept Joystick Axis Motion (Left Joystick & Hat D-Pad)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y)

            // D-Pad Hat Motion
            if (hatY < -0.5f && lastHatY >= -0.5f) {
                dispatchRobotOrClawCommand("forward", "open")
            } else if (hatY > 0.5f && lastHatY <= 0.5f) {
                dispatchRobotOrClawCommand("backward", "close")
            } else if (hatX < -0.5f && lastHatX >= -0.5f) {
                dispatchRobotOrClawCommand("left_wave", "half_open")
            } else if (hatX > 0.5f && lastHatX <= 0.5f) {
                dispatchRobotOrClawCommand("right_wave", "half_close")
            }

            // Left Joystick Motion
            if (axisY < -0.6f && lastAxisY >= -0.6f) {
                dispatchRobotOrClawCommand("forward", "open")
            } else if (axisY > 0.6f && lastAxisY <= 0.6f) {
                dispatchRobotOrClawCommand("backward", "close")
            } else if (Math.abs(axisY) <= 0.2f && Math.abs(lastAxisY) > 0.6f) {
                dispatchRobotOrClawCommand("stop", "stop")
            }

            lastHatX = hatX
            lastHatY = hatY
            lastAxisX = axisX
            lastAxisY = axisY

            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun dispatchRobotOrClawCommand(robotAction: String, clawAction: String) {
        if (RobotBleController.isConnected) {
            // Send BLE command to ESP32 instantly over GATT
            RobotBleController.sendBleCommand("action:$robotAction")
            RobotBleController.sendBleCommand("claw:$clawAction")
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