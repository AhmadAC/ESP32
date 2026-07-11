// app/src/main/java/com/example/mybasicapp/CameraTab.kt
package com.example.mybasicapp

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
fun CameraTab(ipAddress: String, onFlipCamera: () -> Unit) {
    var camActive by remember { mutableStateOf(false) }
    var camRotation by remember { mutableStateOf(0) }
    var testMode by remember { mutableStateOf(1) } // Default to V1 Flex Box
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
                    .aspectRatio(4f / 3f)
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
                                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setOnTouchListener { _, _ -> false } 
                            }
                        },
                        update = { view ->
                            val stateKey = "${ipAddress}_${camRotation}_${testMode}"
                            val currentStateKey = view.tag as? String
                            
                            // Re-render HTML view only when IP, rotation, or testing strategy changes
                            if (currentStateKey != stateKey) {
                                view.tag = stateKey
                                
                                val isVerticalRotation = camRotation % 180 != 0
                                // Scale down slightly when rotated vertically so it doesn't clip the edges
                                val scaleTransform = if (isVerticalRotation) "scale(0.75)" else "scale(1.0)"
                                
                                when (testMode) {
                                    1, 4 -> {
                                        // THE PERFECT FIX: Standard HTML5 with Viewport bounds & Object-Fit
                                        val html = """
                                            <!DOCTYPE html>
                                            <html>
                                            <head>
                                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                                                <style>
                                                    html, body { 
                                                        width: 100%; 
                                                        height: 100%; 
                                                        margin: 0; 
                                                        padding: 0; 
                                                        background-color: black; 
                                                        display: flex; 
                                                        justify-content: center; 
                                                        align-items: center; 
                                                        overflow: hidden; 
                                                    }
                                                    img { 
                                                        width: 100vw; 
                                                        height: 100vh; 
                                                        object-fit: contain; 
                                                        transform: rotate(${camRotation}deg) $scaleTransform; 
                                                    }
                                                </style>
                                            </head>
                                            <body>
                                                <img src="http://${ipAddress}:81/" />
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    }
                                    2 -> {
                                        // V2: Raw Native browser loads direct endpoint URL bypass
                                        view.loadUrl("http://${ipAddress}:81/")
                                    }
                                    3 -> {
                                        // V3: Iframe Container Integration
                                        val html = """
                                            <!DOCTYPE html>
                                            <html>
                                            <body style="margin:0;padding:0;background-color:black;overflow:hidden;display:flex;justify-content:center;align-items:center;width:100vw;height:100vh;">
                                                <iframe src="http://${ipAddress}:81/" style="width:100vw;height:100vh;border:none;margin:0;padding:0;transform:rotate(${camRotation}deg) $scaleTransform;" />
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    }
                                    5 -> {
                                        // V5: CSS DIV background viewport container stretch
                                        val html = """
                                            <!DOCTYPE html>
                                            <html>
                                            <body style="margin:0;padding:0;background-color:black;overflow:hidden;">
                                                <div style="width:100vw;height:100vh;background-image:url('http://${ipAddress}:81/');background-position:center;background-repeat:no-repeat;background-size:contain;transform:rotate(${camRotation}deg) $scaleTransform;"></div>
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    }
                                }
                            }
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

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))
            
            Text("Beta Stream Engine Option:", color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HtmlButton("V1: Flex", if (testMode == 1) BtnGreen else BtnGray, Modifier.weight(1f).padding(2.dp)) { testMode = 1 }
                HtmlButton("V2: Raw", if (testMode == 2) BtnGreen else BtnGray, Modifier.weight(1f).padding(2.dp)) { testMode = 2 }
                HtmlButton("V3: Iframe", if (testMode == 3) BtnGreen else BtnGray, Modifier.weight(1f).padding(2.dp)) { testMode = 3 }
            }
            Spacer(modifier = Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HtmlButton("V4: Legacy", if (testMode == 4) BtnGreen else BtnGray, Modifier.weight(1.2f).padding(2.dp)) { testMode = 4 }
                HtmlButton("V5: DivBg", if (testMode == 5) BtnGreen else BtnGray, Modifier.weight(1f).padding(2.dp)) { testMode = 5 }
            }
        }
    }
}
