// app/src/main/java/com/example/mybasicapp/NetworkUtils.kt
package com.example.mybasicapp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL

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
        val sem = Semaphore(40) // Run up to 40 threads simultaneously
        val foundIps = mutableListOf<String>()
        var completed = 0

        val jobs = (1..254).map { host ->
            launch {
                sem.withPermit {
                    val targetIp = "${prefix}${host}"
                    try {
                        // Quick TCP connection to port 80 to check if it's a web server
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
        
        // Wait for all 254 checks to complete
        jobs.forEach { it.join() }
        
        withContext(Dispatchers.Main) {
            // Sort by the last octet (e.g., 192.168.1.5 before 192.168.1.10)
            onFinished(foundIps.sortedBy { it.substringAfterLast('.').toInt() })
        }
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