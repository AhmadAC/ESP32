// app/src/main/java/com/example/mybasicapp/MainActivity.kt
package com.example.mybasicapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        // Check for existing permission prior to launching request (Essential for SDK < 33 support)
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permissions are inherently granted on Android 12 and below
        }
        hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.POST_NOTIFICATIONS]?.let { hasNotificationPermission = it }
            }
            permissions[Manifest.permission.RECORD_AUDIO]?.let { hasAudioPermission = it }
            permissions[Manifest.permission.ACCESS_FINE_LOCATION]?.let { hasLocationPermission = it }
        }

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
            try { notify(System.currentTimeMillis().toInt(), builder.build()) } catch (e: SecurityException) {}
        }
    }
}