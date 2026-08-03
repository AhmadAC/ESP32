// app/src/main/java/com/example/mybasicapp/NetworkUtils.kt
package com.example.mybasicapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

object AppNetworkManager {
    var targetIp: String = "192.168.4.1"
    var activeDeviceMode: String = "Robot"

    fun sendHttpAsync(endpoint: String, isPost: Boolean, payload: JSONObject? = null) {
        Thread {
            try {
                // PyCar registers its HTTP_POST handler on "/*". Force posts to "/" when in PyCar mode.
                val actualEndpoint = if (activeDeviceMode == "PyCar" && isPost) "/" else endpoint
                val url = URL("http://${targetIp}${actualEndpoint}")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                if (isPost) {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    payload?.let {
                        java.io.OutputStreamWriter(conn.outputStream).use { writer -> writer.write(it.toString()) }
                    }
                } else {
                    conn.requestMethod = "GET"
                }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
            }
        }.start()
    }
}

object RobotBleController {
    private const val TAG = "RobotBleController"
    private val SVC_UUID = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")
    private val RX_UUID = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb")
    private val IP_UUID = UUID.fromString("0000abf3-0000-1000-8000-00805f9b34fb")

    private var activeGatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    
    // Thread-safe FIFO Write Queue with Non-Blocking Handler Dispatch
    private val commandQueue = ConcurrentLinkedQueue<ByteArray>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isQueueProcessing = false
    private var isWritePending = false
    private var lastWriteAttempt = 0L

    var isConnected: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun connectToRobot(
        context: Context,
        onStatus: (String) -> Unit,
        onConnectedStateChange: (Boolean) -> Unit,
        onIpReceived: ((String) -> Unit)? = null
    ) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth Disabled")
            onConnectedStateChange(false)
            return
        }

        // GPS Check. Location must be enabled system-wide to scan for BLE on Android
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            onStatus("Location/GPS must be ON for BLE Scan!")
            onConnectedStateChange(false)
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("BLE Scanner Unavailable")
            onConnectedStateChange(false)
            return
        }

        onStatus("Scanning BLE for Device...")

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try {
                    result.device.name ?: result.scanRecord?.deviceName
                } catch (e: SecurityException) {
                    result.scanRecord?.deviceName
                }

                if (name?.contains("ESPRobot", true) == true || name?.contains("pyCar", true) == true || name?.contains("Robot", true) == true) {
                    try { scanner.stopScan(this) } catch (e: Exception) {}
                    onStatus("Found $name! Connecting...")

                    val gattCallback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                activeGatt = gatt
                                isConnected = true
                                commandQueue.clear()
                                isQueueProcessing = false
                                isWritePending = false

                                Handler(Looper.getMainLooper()).post {
                                    onStatus("Connected via BLE!")
                                    onConnectedStateChange(true)
                                }
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try { gatt.discoverServices() } catch (e: Exception) {}
                                }, 300)
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                isConnected = false
                                activeGatt = null
                                rxChar = null
                                commandQueue.clear()
                                isQueueProcessing = false
                                isWritePending = false
                                Handler(Looper.getMainLooper()).post {
                                    onStatus("BLE Disconnected")
                                    onConnectedStateChange(false)
                                }
                                try { gatt.close() } catch (e: Exception) {}
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                val service = gatt.getService(SVC_UUID)
                                rxChar = service?.getCharacteristic(RX_UUID)
                                val ipChar = service?.getCharacteristic(IP_UUID)

                                if (ipChar != null) {
                                    gatt.setCharacteristicNotification(ipChar, true)
                                    val desc = ipChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                    if (desc != null) {
                                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        try { gatt.writeDescriptor(desc) } catch (e: Exception) {}
                                    }
                                }

                                Handler(Looper.getMainLooper()).postDelayed({
                                    try { gatt.requestMtu(512) } catch (e: Exception) {}
                                }, 300)
                            }
                        }

                        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                            Log.i(TAG, "BLE MTU Negotiated: $mtu bytes")
                        }

                        // Unlock the dispatcher as soon as the ESP32 hardware acknowledges the packet
                        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                            isWritePending = false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                            handleCharacteristic(characteristic, characteristic.value)
                        }

                        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                            handleCharacteristic(characteristic, value)
                        }

                        private fun handleCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
                            if (value == null) return
                            if (characteristic.uuid == IP_UUID) {
                                val ip = String(value).replace("\u0000", "").trim()
                                if (ip != "0.0.0.0" && ip.isNotEmpty()) {
                                    Handler(Looper.getMainLooper()).post {
                                        onStatus("Device IP Acquired: $ip")
                                        onIpReceived?.invoke(ip)
                                    }
                                }
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        result.device.connectGatt(context, false, gattCallback)
                    }
                }
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, scanSettings, scanCallback)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isConnected) {
                    try { scanner.stopScan(scanCallback) } catch (e: Exception) {}
                    if (!isConnected) onStatus("BLE Scan Timeout")
                }
            }, 8000)
        } catch (e: SecurityException) {
            onStatus("Bluetooth Permission Denied")
            onConnectedStateChange(false)
        }
    }

    fun sendBleCommand(command: String): Boolean {
        if (!isConnected || rxChar == null) return false

        if (command.startsWith("claw_angle:")) {
            commandQueue.removeIf { String(it).startsWith("claw_angle:") }
        }

        commandQueue.offer(command.toByteArray())
        startQueueProcessing()
        return true
    }

    @Synchronized
    private fun startQueueProcessing() {
        if (isQueueProcessing) return
        isQueueProcessing = true
        drainQueueRunnable.run()
    }

    private val drainQueueRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (isWritePending) {
                if (System.currentTimeMillis() - lastWriteAttempt > 500) {
                    isWritePending = false // Automatically clear timeout locks
                } else {
                    mainHandler.postDelayed(this, 5)
                    return
                }
            }

            val bytes = commandQueue.poll()
            if (bytes != null && isConnected) {
                val gatt = activeGatt
                val characteristic = rxChar
                if (gatt != null && characteristic != null) {
                    try {
                        isWritePending = true
                        lastWriteAttempt = System.currentTimeMillis()

                        // Enforce WRITE_TYPE_DEFAULT so Android forces the ESP32 to send a hardware-level ACK. 
                        // This guarantees 100% transmission success and fires onCharacteristicWrite immediately!
                        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        
                        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(characteristic, bytes, type) == 0 // 0 == SUCCESS
                        } else {
                            characteristic.value = bytes
                            characteristic.writeType = type
                            gatt.writeCharacteristic(characteristic)
                        }

                        if (!success) {
                            isWritePending = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing BLE write", e)
                        isWritePending = false
                    }
                }
                mainHandler.postDelayed(this, 8)
            } else {
                isQueueProcessing = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (e: Exception) {}
        activeGatt = null
        rxChar = null
        commandQueue.clear()
        isQueueProcessing = false
        isWritePending = false
        isConnected = false
    }
}

fun getLocalWifiSubnetPrefix(context: Context): String? {
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wm.dhcpInfo ?: return null
        val ip = dhcp.ipAddress
        if (ip == 0) null else "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}."
    } catch (e: Exception) { null }
}

fun scanSubnetForWebServers(
    context: Context,
    scope: CoroutineScope,
    onProgress: (Float) -> Unit,
    onFinished: (List<String>) -> Unit
) {
    val prefix = getLocalWifiSubnetPrefix(context) ?: return onFinished(emptyList())
    scope.launch(Dispatchers.IO) {
        val sem = Semaphore(40) 
        val foundIps = mutableListOf<String>()
        var completed = 0

        val jobs = (1..254).map { host ->
            launch {
                sem.withPermit {
                    val targetIp = "${prefix}${host}"
                    try {
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress(targetIp, 80), 350)
                        socket.close()
                        synchronized(foundIps) { foundIps.add(targetIp) }
                    } catch (e: Exception) {}

                    synchronized(this) {
                        completed++
                        scope.launch(Dispatchers.Main) { onProgress(completed.toFloat() / 254f) }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        withContext(Dispatchers.Main) {
            onFinished(foundIps.sortedBy { it.substringAfterLast('.').toInt() })
        }
    }
}

suspend fun findRobotViaUDP(): String? = withContext(Dispatchers.IO) {
    var socket: DatagramSocket? = null
    try {
        socket = DatagramSocket(4210)
        socket.soTimeout = 3000 
        val buffer = ByteArray(256)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        val message = String(packet.data, 0, packet.length)
        if (message.startsWith("ROBOT_DOG_IP:")) {
            return@withContext message.removePrefix("ROBOT_DOG_IP:").trim()
        }
    } catch (e: Exception) {
    } finally {
        socket?.close()
    }
    return@withContext null
}

fun findRobotViaMDNS(context: Context, onIpFound: (String) -> Unit) {
    val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val currentListener = this
            if (serviceInfo.serviceName.contains("ESP32 Robot", ignoreCase = true) || serviceInfo.serviceName.contains("robotdog", ignoreCase = true) || serviceInfo.serviceName.contains("pyCar", ignoreCase = true)) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                        resolvedService.host?.hostAddress?.let { ip ->
                            Handler(Looper.getMainLooper()).post { onIpFound(ip) }
                            try {
                                nsdManager.stopServiceDiscovery(currentListener)
                            } catch (e: Exception) {}
                        }
                    }
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                })
            }
        }
        override fun onDiscoveryStarted(regType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { 
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { 
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
        }
    }
    nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
}

@SuppressLint("MissingPermission")
fun setupRobotViaBLE(
    context: Context,
    ssid: String,
    pass: String,
    onStatus: (String) -> Unit,
    onIpReceived: (String) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            onStatus("Disabled: Please turn ON system Location/GPS!")
            return
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is disabled")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("BLE Scanner unavailable")
            return
        }

        onStatus("Scanning... (Confirm GPS is ON)")
        var isConnecting = false
        var isPollingIp = false

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val name = try {
                        result.device.name ?: result.scanRecord?.deviceName
                    } catch (e: SecurityException) {
                        result.scanRecord?.deviceName
                    }

                    if ((name?.contains("ESPRobot", true) == true || name?.contains("pyCar", true) == true) && !isConnecting) {
                        isConnecting = true
                        try { scanner.stopScan(this) } catch (e: Exception) {}
                        onStatus("Found Device! Connecting...")

                        val gattCallback = object : BluetoothGattCallback() {
                            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                try {
                                    if (status != BluetoothGatt.GATT_SUCCESS) {
                                        isPollingIp = false
                                        Handler(Looper.getMainLooper()).post { onStatus("GATT Error $status. Retrying...") }
                                        try { gatt.close() } catch (e: Exception) {}
                                        isConnecting = false
                                        return
                                    }

                                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                                        Handler(Looper.getMainLooper()).post { onStatus("Connected. Waiting for services...") }
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            try { gatt.discoverServices() } catch (e: Exception) { }
                                        }, 600)
                                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                        isPollingIp = false
                                        if (isConnecting) {
                                            Handler(Looper.getMainLooper()).post { onStatus("Disconnected from device.") }
                                        }
                                        try { gatt.close() } catch (e: Exception) {}
                                        isConnecting = false
                                    }
                                } catch (e: Exception) {
                                    Handler(Looper.getMainLooper()).post { onStatus("Connection error occurred") }
                                }
                            }

                            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                try {
                                    if (status != BluetoothGatt.GATT_SUCCESS) return

                                    val svcUuid = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")
                                    val credsUuid = UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb")
                                    val ipUuid = UUID.fromString("0000abf3-0000-1000-8000-00805f9b34fb")

                                    val service = gatt.getService(svcUuid)
                                    if (service != null) {
                                        Handler(Looper.getMainLooper()).post { onStatus("Subscribing to IP Updates...") }
                                        val ipChar = service.getCharacteristic(ipUuid)
                                        
                                        if (ipChar != null) {
                                            gatt.setCharacteristicNotification(ipChar, true)
                                            val desc = ipChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                            if (desc != null) {
                                                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                                try { gatt.writeDescriptor(desc) } catch (e: Exception) {}
                                            }
                                            
                                            isPollingIp = true
                                            Thread {
                                                while (isPollingIp && isConnecting) {
                                                    Thread.sleep(1500)
                                                    try { gatt.readCharacteristic(ipChar) } catch (e: Exception) {}
                                                }
                                            }.start()
                                            
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                if (isPollingIp && isConnecting) {
                                                    isPollingIp = false
                                                    isConnecting = false
                                                    Handler(Looper.getMainLooper()).post { onStatus("Timeout waiting for Wi-Fi IP") }
                                                    try { gatt.disconnect() } catch (e: Exception) {}
                                                }
                                            }, 25000)
                                        } else {
                                            Handler(Looper.getMainLooper()).post { onStatus("Warning: IP Characteristic missing") }
                                        }
                                        
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            try {
                                                onStatus("Sending Wi-Fi Credentials...")
                                                val credsChar = service.getCharacteristic(credsUuid)
                                                if (credsChar != null) {
                                                    val payload = "$ssid,$pass".toByteArray()
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                        gatt.writeCharacteristic(credsChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                                                    } else {
                                                        credsChar.value = payload
                                                        gatt.writeCharacteristic(credsChar)
                                                    }
                                                    onStatus("Waiting for Robot to connect to Wi-Fi...")
                                                } else {
                                                    onStatus("Credentials Characteristic missing on device")
                                                }
                                            } catch (e: Exception) {
                                                onStatus("Permission Denied writing characteristic")
                                            }
                                        }, 800)
                                    } else {
                                        Handler(Looper.getMainLooper()).post { onStatus("Invalid Service on Device") }
                                        try { gatt.disconnect() } catch (e: Exception) {}
                                    }
                                } catch (e: Exception) {
                                    Handler(Looper.getMainLooper()).post { onStatus("Error processing services") }
                                }
                            }

                            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                                handleIpChanged(characteristic, value, gatt)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                                handleIpChanged(characteristic, characteristic.value, gatt)
                            }

                            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    handleIpChanged(characteristic, value, gatt)
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    handleIpChanged(characteristic, characteristic.value, gatt)
                                }
                            }

                            private fun handleIpChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray?, gatt: BluetoothGatt) {
                                if (value == null) return
                                if (characteristic.uuid == UUID.fromString("0000abf3-0000-1000-8000-00805f9b34fb")) {
                                    val ip = String(value).replace("\u0000", "").trim()
                                    if (ip != "0.0.0.0" && ip.isNotEmpty()) {
                                        isPollingIp = false
                                        Handler(Looper.getMainLooper()).post {
                                            onStatus("Connected to Wi-Fi!")
                                            onIpReceived(ip)
                                        }
                                        isConnecting = false
                                        try { gatt.disconnect() } catch (e: Exception) {}
                                    }
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                        } else {
                            result.device.connectGatt(context, false, gattCallback)
                        }
                    }
                } catch (e: SecurityException) {
                    Handler(Looper.getMainLooper()).post { onStatus("Permission Denied reading BLE characteristics!") }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post { onStatus("Error processing scan result") }
                }
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, scanSettings, scanCallback)
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isConnecting) {
                try { scanner.stopScan(scanCallback) } catch (e: Exception) {}
                onStatus("Scan Timed Out. Is the device near?")
            }
        }, 10000)

    } catch (e: SecurityException) {
        onStatus("Bluetooth Permission denied! Please accept prompts or enable in settings.")
    } catch (e: Exception) {
        onStatus("Bluetooth Subsystem Error: ${e.message}")
    }
}

suspend fun saveImageToGallery(context: Context, ipAddress: String, rotationZ: Int) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${ipAddress}/capture")
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
                        if (out != null) finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                }
                
                withContext(Dispatchers.Main) { Toast.makeText(context, "Image saved to Gallery!", Toast.LENGTH_SHORT).show() }
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to capture image.", Toast.LENGTH_SHORT).show() }
            }
            conn.disconnect()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Network Error. Image not saved.", Toast.LENGTH_SHORT).show() }
        }
    }
}