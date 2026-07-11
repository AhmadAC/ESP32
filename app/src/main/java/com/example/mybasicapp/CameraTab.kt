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
                            val stateKey = "${ipAddress}_${camRotation}"
                            val currentStateKey = view.tag as? String
                            
                            // Re-render HTML view only when IP or rotation changes
                            if (currentStateKey != stateKey) {
                                view.tag = stateKey
                                
                                // JS-Scale: Dynamically calculates box size and scales video flawlessly
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
