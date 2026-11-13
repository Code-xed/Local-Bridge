package app.localbridge

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private var server: LocalBridgeServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("localbridge", MODE_PRIVATE)
        setContent {
            MaterialTheme {
                var running by remember { mutableStateOf(false) }
                var url by remember { mutableStateOf<String?>(null) }
                var status by remember { mutableStateOf("Ready") }
                var shareUri by remember { mutableStateOf(prefs.getString("shared_uri", null)?.let(Uri::parse)) }
                var siteUri by remember { mutableStateOf(prefs.getString("website_uri", null)?.let(Uri::parse)) }
                var sharePath by remember { mutableStateOf(prefs.getString("share_path", "/files") ?: "/files") }
                val sharedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
                        shareUri = uri
                        prefs.edit().putString("shared_uri", uri.toString()).apply()
                        status = "Shared folder updated"
                    }
                }
                val websitePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                        siteUri = uri
                        prefs.edit().putString("website_uri", uri.toString()).apply()
                        status = "Website folder updated"
                    }
                }
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("LocalBridge", style = MaterialTheme.typography.headlineLarge)
                    Text("Android ↔ iPhone • Local network transfer")
                    Text(if (running) "Sharing is ON" else "Sharing is OFF")
                    url?.let { SelectionContainer { Text("Open in Safari: $it") } }
                    Button(onClick = {
                        if (!running) try {
                            prefs.edit().putString("share_path", sharePath).apply()
                            server = LocalBridgeServer(this@MainActivity, shareUri, siteUri, sharePath).also { it.start() }
                            url = lanIp()?.let { "http://$it:8765" }
                            running = true
                            status = "Keep this screen open during transfers."
                        } catch (e: Exception) { status = "Start failed: ${e.message}" }
                        else { server?.stop(); server = null; running = false; url = null; status = "Stopped" }
                    }) { Text(if (running) "Stop sharing" else "Start sharing") }

                    HorizontalDivider()
                    Text("Storage & website", style = MaterialTheme.typography.titleLarge)
                    Text("Shared folder", style = MaterialTheme.typography.titleMedium)
                    Text(shareUri?.lastPathSegment ?: "Default app storage (Android/data/app.localbridge)")
                    OutlinedButton(enabled = !running, onClick = { sharedPicker.launch(shareUri) }) { Text("Choose shared folder") }

                    Text("File share URL path", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = sharePath, onValueChange = { sharePath = it }, enabled = !running, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("/files") })
                    Text("Example: /files or /my-share", style = MaterialTheme.typography.bodySmall)

                    Text("Custom static website", style = MaterialTheme.typography.titleMedium)
                    Text(siteUri?.lastPathSegment ?: "Built-in LocalBridge page")
                    OutlinedButton(enabled = !running, onClick = { websitePicker.launch(siteUri) }) { Text("Choose website folder") }
                    if (siteUri != null) Text("Place an index.html in the selected folder. CSS, JS and assets are served from the same folder.", style = MaterialTheme.typography.bodySmall)

                    OutlinedButton(enabled = !running, onClick = {
                        siteUri = null; shareUri = null; sharePath = "/files"
                        prefs.edit().remove("website_uri").remove("shared_uri").putString("share_path", "/files").apply()
                        status = "Defaults restored."
                    }) { Text("Restore defaults") }
                    Text(status)
                    Text("Both phones can use the same local Wi-Fi. Internet is not required.", style = MaterialTheme.typography.bodySmall)
                    Text("Stop sharing before changing folders or the URL path.", style = MaterialTheme.typography.bodySmall)
                    Text("Alpha: no authentication, HTTPS, transfer resume, or background service yet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    override fun onDestroy() { server?.stop(); server = null; super.onDestroy() }

    private fun lanIp(): String? {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces()
            while (nets.hasMoreElements()) {
                val network = nets.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress && !address.hostAddress.orEmpty().startsWith("169.254.")) return address.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
