// app/src/main/java/com/cooper/basicapk/MainActivity.kt
package com.cooper.basicapk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQ_CODE = 101

    private lateinit var btnAutoAP: Button
    private lateinit var etIpAddress: EditText
    private lateinit var btnConnect: Button
    private lateinit var webViewCam: WebView
    private lateinit var btnPushToTalk: Button

    private var espIp = "192.168.4.1"
    private var detectedGateway = "192.168.4.1"
    private var isRecording = false
    private var udpSocket: DatagramSocket? = null

    // Audio config matching ESP32S3 specifications
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val espUdpPort = 5000 

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnAutoAP = findViewById(R.id.btnAutoAP)
        etIpAddress = findViewById(R.id.etIpAddress)
        btnConnect = findViewById(R.id.btnConnect)
        webViewCam = findViewById(R.id.webViewCam)
        btnPushToTalk = findViewById(R.id.btnPushToTalk)

        // Bind application to Wi-Fi to avoid cellular data intercepting AP traffic
        forceWiFiBinding()

        // Setup WebView
        webViewCam.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webViewCam.webViewClient = WebViewClient()

        // Auto AP Gateway Discovery Action
        btnAutoAP.setOnClickListener {
            etIpAddress.setText(detectedGateway)
            Toast.makeText(this, "Set to Gateway: $detectedGateway", Toast.LENGTH_SHORT).show()
        }

        // Connection Action
        btnConnect.setOnClickListener {
            espIp = etIpAddress.text.toString().trim()
            if (espIp.isNotEmpty()) {
                val camUrl = "http://$espIp:81/"
                webViewCam.loadUrl(camUrl)
                Toast.makeText(this, "Connecting to $espIp", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup Robot Action Buttons
        setupActionButton(R.id.btnForward, "forward")
        setupActionButton(R.id.btnBackward, "backward")
        setupActionButton(R.id.btnLeft, "left_wave")
        setupActionButton(R.id.btnRight, "right_wave")
        setupActionButton(R.id.btnSit, "sit")
        setupActionButton(R.id.btnStand, "stand")
        setupActionButton(R.id.btnCrawl, "crawl")
        setupActionButton(R.id.btnLeap, "leap_forward")
        setupActionButton(R.id.btnStop, "stop")

        // Setup Walkie-Talkie Button (Push To Talk)
        btnPushToTalk.setOnTouchListener { v, event ->
            if (checkPermissions()) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.setBackgroundColor(android.graphics.Color.parseColor("#10b981")) // Green
                        startStreamingAudio()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.setBackgroundColor(android.graphics.Color.parseColor("#475569")) // Gray
                        stopStreamingAudio()
                    }
                }
            } else {
                requestPermissions()
            }
            true
        }
    }

    // --- NETWORK BINDING & DISCOVERY ---

    private fun forceWiFiBinding() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
            
        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                
                // 1. Force the app to use Wi-Fi instead of Cellular (Crucial for AP mode with no internet)
                connectivityManager.bindProcessToNetwork(network)
                Log.d("WIFI", "App strictly bound to Wi-Fi network.")

                // 2. Extract the Gateway IP (which is the ESP32 when connected to its AP)
                val linkProperties = connectivityManager.getLinkProperties(network)
                val gateway = linkProperties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                
                if (gateway != null) {
                    detectedGateway = gateway
                    Log.d("WIFI", "Detected ESP32 Gateway: $detectedGateway")
                }
            }
        })
    }

    // --- HTTP CONTROL LOGIC ---

    private fun setupActionButton(buttonId: Int, actionName: String) {
        findViewById<Button>(buttonId).setOnClickListener {
            sendRobotAction(actionName)
        }
    }

    private fun sendRobotAction(action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://$espIp/action")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                val json = JSONObject()
                json.put("action", action)
                
                conn.outputStream.write(json.toString().toByteArray())
                val responseCode = conn.responseCode
                Log.d("HTTP_ROBOT", "Action: $action | Response: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("HTTP_ROBOT", "Error sending action: ${e.message}")
            }
        }
    }

    // --- WALKIE TALKIE LOGIC (UDP Streaming) ---

    @SuppressLint("MissingPermission")
    private fun startStreamingAudio() {
        if (isRecording) return
        isRecording = true
        espIp = etIpAddress.text.toString().trim()

        Thread {
            try {
                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufSize
                )

                udpSocket = DatagramSocket()
                val espAddress = InetAddress.getByName(espIp)

                val buffer = ByteArray(minBufSize)
                audioRecord.startRecording()
                Log.d("AUDIO", "Started UDP audio stream to $espIp:$espUdpPort")

                while (isRecording) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val packet = DatagramPacket(buffer, bytesRead, espAddress, espUdpPort)
                        udpSocket?.send(packet)
                    }
                }

                audioRecord.stop()
                audioRecord.release()
                udpSocket?.close()
                Log.d("AUDIO", "Stopped UDP audio stream")

            } catch (e: Exception) {
                Log.e("AUDIO", "Error streaming audio: ${e.message}")
                isRecording = false
                udpSocket?.close()
            }
        }.start()
    }

    private fun stopStreamingAudio() {
        isRecording = false
    }

    // --- PERMISSIONS ---

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQ_CODE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        udpSocket?.close()
    }
}