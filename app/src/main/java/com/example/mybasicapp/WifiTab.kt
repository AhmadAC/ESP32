// app/src/main/java/com/example/mybasicapp/WifiTab.kt
package com.example.mybasicapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun WifiTab(
    ipAddress: String,
    hasLocationPermission: Boolean,
    onSaveWifi: (String, String) -> Unit,
    onForceAp: () -> Unit,
    onUseWifi: () -> Unit,
    onBleIpReceived: (String) -> Unit
) {
    var ssidList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun getPhoneLocalWifiNetworks(context: Context): List<String> {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                wm.scanResults.mapNotNull { it.SSID }.filter { it.isNotEmpty() }.distinct()
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState())) {
        
        CardContainer(title = "Wi-Fi Network Setup") {
            HtmlButton(
                text = if (isScanning) "Scanning Networks..." else "Scan Wi-Fi Networks",
                color = BtnGray,
                modifier = Modifier.fillMaxWidth()
            ) {
                isScanning = true
                scope.launch(Dispatchers.IO) {
                    val robotNetworks = mutableListOf<String>()
                    try {
                        val url = URL("http://${ipAddress}/scan")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 4000
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = JSONArray(resp)
                        for (i in 0 until arr.length()) robotNetworks.add(arr.getString(i))
                        conn.disconnect()
                    } catch (e: Exception) {}

                    val phoneNetworks = getPhoneLocalWifiNetworks(context)
                    val mergedList = (robotNetworks + phoneNetworks).distinct().sorted()

                    withContext(Dispatchers.Main) {
                        ssidList = mergedList
                        if (mergedList.isNotEmpty()) selectedSsid = mergedList[0]
                        isScanning = false
                        Toast.makeText(context, "Found ${mergedList.size} networks", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
            
            LabeledDropdown("Target Wi-Fi SSID", selectedSsid, ssidList) { selectedSsid = it }
            
            Spacer(modifier = Modifier.height(15.dp))
            
            OutlinedTextField(
                value = wifiPassword,
                onValueChange = { wifiPassword = it },
                label = { Text("Wi-Fi Password", color = PrimaryColor) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryColor, unfocusedBorderColor = BtnGray,
                    focusedTextColor = TextColor, unfocusedTextColor = TextColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(15.dp))
            
            HtmlButton("Save & Connect Wi-Fi", BtnGreen, Modifier.fillMaxWidth()) {
                if (selectedSsid.isEmpty()) {
                    Toast.makeText(context, "Select or enter an SSID first!", Toast.LENGTH_SHORT).show()
                } else {
                    onSaveWifi(selectedSsid, wifiPassword)
                    Toast.makeText(context, "Credentials Saved! Robot Rebooting...", Toast.LENGTH_LONG).show()
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        CardContainer(title = "Quick Boot Mode Switch") {
            Row(modifier = Modifier.fillMaxWidth()) {
                HtmlButton("Force AP Mode", BtnOrange, Modifier.weight(1f).padding(end = 4.dp)) { 
                    onForceAp()
                    Toast.makeText(context, "Forcing AP Mode... Rebooting...", Toast.LENGTH_LONG).show()
                }
                HtmlButton("Use Saved Wi-Fi", BtnGreen, Modifier.weight(1f).padding(start = 4.dp)) { 
                    onUseWifi()
                    Toast.makeText(context, "Switching to Wi-Fi... Rebooting...", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}