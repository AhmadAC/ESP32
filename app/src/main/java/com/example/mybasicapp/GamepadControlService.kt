// app/src/main/java/com/example/mybasicapp/GamepadControlService.kt
package com.example.mybasicapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class GamepadControlService : Service(), InputManager.InputDeviceListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var inputManager: InputManager? = null

    companion object {
        private const val TAG = "GamepadControlService"
        const val CHANNEL_ID = "gamepad_control_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.mybasicapp.ACTION_START_GAMEPAD_SERVICE"
        const val ACTION_STOP = "com.example.mybasicapp.ACTION_STOP_GAMEPAD_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing GamepadControlService")
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ESPRobot::GamepadWakeLock"
        )?.apply {
            setReferenceCounted(false)
        }

        inputManager = getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(this, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        acquireWakeLock()

        return START_STICKY
    }

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(24 * 60 * 60 * 1000L)
                Log.i(TAG, "PARTIAL_WAKE_LOCK acquired. CPU will stay alive with screen off.")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ESPRobot Gamepad & BLE Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth & Nintendo Switch Controller active when screen is off."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESPRobot Controller Active")
            .setContentText("Nintendo Switch Controller & BLE active (Screen-Off Enabled)")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }
    }

    private fun stopForegroundService() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "PARTIAL_WAKE_LOCK released.")
            }
        }
        inputManager?.unregisterInputDeviceListener(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        Log.i(TAG, "Input device added: $deviceId")
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        Log.w(TAG, "Input device removed: $deviceId")
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        Log.d(TAG, "Input device changed: $deviceId")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForegroundService()
        super.onDestroy()
    }
}