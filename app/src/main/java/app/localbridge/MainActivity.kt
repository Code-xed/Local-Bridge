package app.localbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.NetworkInterface

private val LobbyBg = Color(0xFFF4F5F3)
private val LobbySurface = Color(0xFFFCFDFC)
private val LobbyInk = Color(0xFF202923)
private val LobbyMuted = Color(0xFF68736C)
private val LobbyGreen = Color(0xFF326B50)

class MainActivity : ComponentActivity() {
    private var server: LocalBridgeServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("localbridge", MODE_PRIVATE)
        setContent {
            val colors = lightColorScheme(
                primary = LobbyGreen,
                onPrimary = Color.White,
                background = LobbyBg,
                onBackground = LobbyInk,
                surface = LobbySurface,
                onSurface = LobbyInk,
                secondary = Color(0xFF718A78),
                outline = Color(0xFFDCE3DD)
            )
            MaterialTheme(colorScheme = colors) {
                var running by remember { mutableStateOf(false) }
                var url by remember { mutableStateOf<String?>(null) }
                var status by remember { mutableStateOf("Ready when you are") }
                var shareUri by remember { mutableStateOf(prefs.getString("shared_uri", null)?.let(Uri::parse)) }
                var siteUri by remember { mutableStateOf(prefs.getString("website_uri", null)?.let(Uri::parse)) }
                var sharePath by remember { mutableStateOf(prefs.getString("share_path", "/files") ?: "/files") }
                var page by remember { mutableStateOf("home") }

                val sharedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
                        shareUri = uri
                        prefs.edit().putString("shared_uri", uri.toString()).apply()
                        status = "Shared folder updated"
                    }
                }
                val websitePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                        siteUri = uri
                        prefs.edit().putString("website_uri", uri.toString()).apply()
                        status = "Website folder updated"
                    }
                }

                Box(Modifier.fillMaxSize().background(LobbyBg)) {
                    if (page == "home") {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("LoBBY", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = LobbyInk)
                                Text("Your files. Your local network.", color = LobbyMuted, fontSize = 13.sp)
                            }
                            Surface(shape = CircleShape, color = if (running) Color(0xFFE2F1E7) else Color(0xFFE9ECE9)) {
                                Text(if (running) "  LIVE  " else "  IDLE  ", modifier = Modifier.padding(vertical = 8.dp), color = if (running) LobbyGreen else LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(shape = RoundedCornerShape(26.dp), color = LobbySurface, modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(26.dp))) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("LOCAL SERVER", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                Text(if (running) "Sharing is on" else "Ready to share", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = LobbyInk)
                                Text(if (running) "Keep LoBBY open while another device is transferring files." else "Start the server to make your selected folder available on your LAN.", color = LobbyMuted, fontSize = 14.sp)
                                if (url != null) Surface(color = Color(0xFFF0F4F0), shape = RoundedCornerShape(14.dp)) {
                                    Text(url!!, modifier = Modifier.fillMaxWidth().padding(13.dp), color = LobbyGreen, fontWeight = FontWeight.SemiBold)
                                }
                                Button(onClick = {
                                    if (!running) try {
                                        prefs.edit().putString("share_path", sharePath).apply()
                                        server = LocalBridgeServer(this@MainActivity, shareUri, siteUri, sharePath).also { it.start() }
                                        url = lanIp()?.let { "http://$it:8765" }
                                        running = true
                                        status = "Server is running. Connect using the address above."
                                    } catch (e: Exception) { status = "Start failed: ${e.message}" }
                                    else { server?.stop(); server = null; running = false; url = null; status = "Server stopped" }
                                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                                    Text(if (running) "Stop sharing" else "Start sharing", fontWeight = FontWeight.SemiBold)
                                }
                                Text(status, color = LobbyMuted, fontSize = 12.sp)
                            }
                        }

                        Text("STORAGE", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Shared folder", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(shareUri?.lastPathSegment ?: "Default app storage · Shared", color = LobbyMuted, fontSize = 13.sp)
                                OutlinedButton(enabled = !running, onClick = { sharedPicker.launch(shareUri) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("Choose shared folder") }
                                OutlinedTextField(value = sharePath, onValueChange = { sharePath = it }, enabled = !running, singleLine = true, label = { Text("Share URL path") }, supportingText = { Text("Example: /files") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                            }
                        }
                        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Pluggable website", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(siteUri?.lastPathSegment ?: "No custom website selected", color = LobbyMuted, fontSize = 13.sp)
                                OutlinedButton(enabled = !running, onClick = { websitePicker.launch(siteUri) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("Choose website folder") }
                                Text("The website is an independent, replaceable component. Place index.html and its assets in the selected folder.", color = LobbyMuted, fontSize = 12.sp)
                            }
                        }
                        Text("Stop sharing before changing storage or server settings.", color = LobbyMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    } else {
                        InfoPage(page, Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 112.dp))
                    }

                    // Floating navigation pill belongs to the Android app, not the served website.
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).shadow(12.dp, CircleShape),
                        shape = CircleShape,
                        color = Color(0xFFFCFDFC)
                    ) {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            NavPillItem("Home", page == "home") { page = "home" }
                            NavPillItem("Privacy", page == "privacy") { page = "privacy" }
                            NavPillItem("Credits", page == "credits") { page = "credits" }
                            NavPillItem("Support", page == "support") { page = "support" }
                        }
                    }

                }
            }
        }
    }

    override fun onDestroy() { server?.stop(); server = null; super.onDestroy() }

    private fun lanIp(): String? {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces()
            var found: String? = null
            while (nets.hasMoreElements() && found == null) {
                val network = nets.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements() && found == null) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.hostAddress.orEmpty().startsWith("169.254.")) {
                        found = address.hostAddress
                    }
                }
            }
            found
        } catch (_: Exception) {
            null
        }
    }
}


@Composable
private fun InfoPage(page: String, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LoBBY", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = LobbyInk)
        Text(when (page) { "privacy" -> "Privacy policy"; "credits" -> "Copyright & credits"; else -> "Support the project" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = LobbyInk)
        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (page) {
                    "privacy" -> {
                        InfoHeading("Local-first operation")
                        Text("LoBBY is designed to share files directly between devices on your local network. The app does not require an account or a cloud storage service for LAN transfers.")
                        InfoHeading("Files and permissions")
                        Text("LoBBY accesses the folder you select through Android's Storage Access Framework, plus its own app storage when using the default folder. Files are served to devices that can reach your phone and the displayed server port.")
                        InfoHeading("Network and data")
                        Text("The current alpha server uses HTTP without encryption or authentication. Anyone on a network who can reach the server may be able to access the shared folder while sharing is enabled. Use trusted private networks and stop sharing when finished.")
                        InfoHeading("Analytics and third parties")
                        Text("This codebase contains no analytics or advertising integration. Android and included open-source libraries remain subject to their own platform and license terms. This policy must be updated if future versions add telemetry or external services.")
                    }
                    "credits" -> {
                        Text("© 2026 LoBBY contributors. All rights reserved to their respective authors.")
                        InfoHeading("Open-source components")
                        Text("NanoHTTPD · BSD 3-Clause License\nAndroidX and Jetpack Compose · Apache License 2.0\nAndroidX DocumentFile · Apache License 2.0")
                        InfoHeading("Project")
                        Text("LoBBY is a local-network file sharing and transfer project. Third-party names and trademarks belong to their respective owners. See THIRD_PARTY_NOTICES.md and dependency license files for details.")
                    }
                    else -> {
                        Text("LoBBY is an independent project. If it saves you time, you can support continued development.")
                        Text("The Buy Me a Coffee destination will be connected before public release.", color = LobbyMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPillItem(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = CircleShape, colors = ButtonDefaults.textButtonColors(
        containerColor = if (selected) Color(0xFFE7EFE8) else Color.Transparent,
        contentColor = if (selected) LobbyGreen else LobbyMuted
    ), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun InfoHeading(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontWeight = FontWeight.SemiBold, color = LobbyGreen)
    Spacer(Modifier.height(4.dp))
}
