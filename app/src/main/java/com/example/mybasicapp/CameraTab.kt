// app/src/main/java/com/example/mybasicapp/CameraTab.kt
package com.example.mybasicapp

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
fun CameraTab(ipAddress: String, onFlipCamera: () -> Unit) {
    var camActive by remember { mutableStateOf(false) }
    var camRotation by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())) {
        CardContainer(title = "Live Camera Stream") {
            HtmlButton(
                text = if (camActive) "Turn Camera OFF" else "Turn Camera ON",
                color = if (camActive) BtnRed else BtnBlue,
                modifier = Modifier.fillMaxWidth()
            ) {
                camActive = !camActive
            }

            Spacer(modifier = Modifier.height(15.dp))

            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(4f/3f).background(Color.Black, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (camActive) {
                    // Initialize the WebView exactly ONCE per IP Address to prevent the 800ms 
                    // MainScreen polling loop from constantly interrupting the MJPEG socket stream.
                    val webView = remember(ipAddress) {
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            webViewClient = WebViewClient()
                            setBackgroundColor(android.graphics.Color.BLACK)
                            setOnTouchListener { _, _ -> false } 

                            // The timestamp ?t= forces the Chromium engine to open a fresh TCP socket 
                            // and explicitly bypass the internal HTTP cache
                            val html = "<html><body style='background:black;margin:0;padding:0;display:flex;align-items:center;justify-content:center;height:100%;overflow:hidden;'><img id='stream' src='http://${ipAddress}:81/?t=${System.currentTimeMillis()}' style='width:100%;height:100%;object-fit:contain;transition:transform 0.2s;' /></body></html>"
                            loadDataWithBaseURL("http://${ipAddress}/", html, "text/html", "UTF-8", null)
                        }
                    }

                    // CRITICAL FIX: Ensures the TCP socket from the WebView to the ESP32 (Port 81) is cleanly terminated.
                    // If the socket isn't closed on disposal, the ESP32 connection pool maxes out at 3 and displays black screens!
                    DisposableEffect(webView) {
                        onDispose {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        }
                    }

                    AndroidView(
                        factory = { webView },
                        update = { view ->
                            // Only update visual transform rotations dynamically so we don't drop the live socket
                            val scale = if (camRotation % 180 != 0) "scale(0.75)" else "scale(1)"
                            view.evaluateJavascript("if(document.getElementById('stream')) document.getElementById('stream').style.transform = 'rotate(${camRotation}deg) $scale';", null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("Camera is OFF", color = BtnGray)
                }
            }

            Spacer(modifier = Modifier.height(15.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Rotate 90°", BtnOrange, Modifier.weight(1f).padding(end = 4.dp)) { 
                    camRotation = (camRotation + 90) % 360 
                }
                HtmlButton("Flip Camera", BtnPurple, Modifier.weight(1f).padding(start = 4.dp)) { 
                    onFlipCamera() 
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            HtmlButton("Save HD Picture", BtnGreen, Modifier.fillMaxWidth()) {
                coroutineScope.launch { saveImageToGallery(context, ipAddress, camRotation) }
            }
        }
    }
}