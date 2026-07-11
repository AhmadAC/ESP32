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
    var testMode by remember { mutableStateOf(4) } // Defaulting to V4 so you immediately get your working stream back
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
                                // NO MORE LAYOUT PARAMS HACK! Letting Compose handle the sizing.
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
                            
                            if (currentStateKey != stateKey) {
                                view.tag = stateKey
                                
                                when (testMode) {
                                    1 -> {
                                        // V1 (Absolute): Pins the image exactly to the center using absolute positioning.
                                        val html = """
                                            <html>
                                            <body style="margin:0;background:black;overflow:hidden;">
                                                <img src="http://${ipAddress}:81/" style="position:absolute;top:0;left:0;right:0;bottom:0;margin:auto;max-width:100%;max-height:100%;transform:rotate(${camRotation}deg);" />
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    }
                                    2 -> {
                                        // V2 (Table): Uses old-school CSS tables. The most foolproof centering method in WebViews.
                                        val html = """
                                            <html style="width:100%;height:100%;">
                                            <body style="margin:0;background:black;width:100%;height:100%;display:table;">
                                                <div style="display:table-cell;vertical-align:middle;text-align:center;">
                                                    <img src="http://${ipAddress}:81/" style="max-width:100%;max-height:100%;transform:rotate(${camRotation}deg);" />
                                                </div>
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    }
                                    3 -> {
                                        // V3 (Fit): Forces the image to contain itself within the strict bounds of the body.
                                        val html = """
                                            <html style="width:100%;height:100%;">
                                            <body style="margin:0;background:black;width:100%;height:100%;overflow:hidden;">
                                                <img src="http://${ipAddress}:81/" style="width:100%;height:100%;object-fit:contain;transform:rotate(${camRotation}deg);" />
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    }
                                    4 -> {
                                        // V4 EXACT ORIGINAL: Reverted literally to exactly what you had in the first screenshot.
                                        val html = """
                                            <html style="width:100%;height:100%;">
                                            <body style="background:black;margin:0;padding:0;display:flex;align-items:center;justify-content:center;width:100%;height:100%;">
                                                <img src="http://${ipAddress}:81/" style="width:100%;height:auto;transform:rotate(${camRotation}deg);" />
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                    }
                                    5 -> {
                                        // V5 (JS-Scale): Uses Javascript to force calculate the exact size of the box and scales the video dynamically.
                                        val html = """
                                            <html>
                                            <body style="margin:0;background:black;overflow:hidden;text-align:center;">
                                                <img id="cam" src="http://${ipAddress}:81/" style="transform:rotate(${camRotation}deg);" />
                                                <script>
                                                    setInterval(function(){
                                                        var img = document.getElementById('cam');
                                                        var maxW = window.innerWidth;
                                                        var maxH = window.innerHeight;
                                                        var ratio = Math.min(maxW / img.naturalWidth, maxH / img.naturalHeight);
                                                        if (ratio > 0) {
                                                            img.style.width = (img.naturalWidth * ratio) + 'px';
                                                            img.style.height = (img.naturalHeight * ratio) + 'px';
                                                            img.style.marginTop = Math.max(0, (maxH - (img.naturalHeight * ratio)) / 2) + 'px';
                                                        } else {
                                                            img.style.maxWidth = '100%';
                                                            img.style.maxHeight = '100%';
                                                        }
                                                    }, 500);
                                                </script>
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
                HtmlButton("V1: Absolute", if (testMode == 1) BtnGreen else BtnGray, Modifier.weight(1f).padding(2.dp)) { testMode = 1 }
                HtmlButton("V2: Table", if (testMode == 2) BtnGreen else BtnGray, Modifier.weight(1f).padding(2.dp)) { testMode = 2 }
                HtmlButton("V3: Fit", if (testMode == 3) BtnGreen else BtnGray, Modifier.weight(1f).padding(2.dp)) { testMode = 3 }
            }
            Spacer(modifier = Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HtmlButton("V4: Legacy", if (testMode == 4) BtnGreen else BtnGray, Modifier.weight(1.2f).padding(2.dp)) { testMode = 4 }
                HtmlButton("V5: JS-Scale", if (testMode == 5) BtnGreen else BtnGray, Modifier.weight(1.2f).padding(2.dp)) { testMode = 5 }
            }
        }
    }
}
