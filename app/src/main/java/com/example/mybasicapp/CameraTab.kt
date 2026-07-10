// app/src/main/java/com/example/mybasicapp/CameraTab.kt
package com.example.mybasicapp

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f) // Set a stable 4:3 boundary to prevent view collapsing
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (camActive) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE // Prevent caching the multipart stream
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setOnTouchListener { _, _ -> false } 
                            }
                        },
                        update = { view ->
                            // Use the view tag to prevent reloading the HTML feed on every UI recomposition
                            val currentIp = view.tag as? String
                            if (currentIp != ipAddress) {
                                view.tag = ipAddress
                                val html = """
                                    <html>
                                    <head>
                                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                                    </head>
                                    <body style='background:black;margin:0;padding:0;display:flex;align-items:center;justify-content:center;height:100vh;width:100vw;overflow:hidden;'>
                                        <img id='stream' src='http://${ipAddress}:81/' style='max-width:100%;max-height:100%;object-fit:contain;transition:transform 0.2s;' />
                                    </body>
                                    </html>
                                """.trimIndent()
                                view.loadDataWithBaseURL("http://${ipAddress}/", html, "text/html", "UTF-8", null)
                            }
                            
                            // Dynamically adapt bounding boxes if rotated to prevent clipping
                            val js = """
                                var img = document.getElementById('stream');
                                if(img) {
                                    img.style.transform = 'rotate(${camRotation}deg)';
                                    if (${camRotation} % 180 !== 0) {
                                        img.style.maxWidth = '100vh';
                                        img.style.maxHeight = '100vw';
                                    } else {
                                        img.style.maxWidth = '100%';
                                        img.style.maxHeight = '100%';
                                    }
                                }
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
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