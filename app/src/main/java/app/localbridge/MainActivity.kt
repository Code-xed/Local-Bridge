package app.localbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.graphics.Bitmap
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.IOException

private val LobbyBg = Color(0xFFF4F5F3)
private val LobbySurface = Color(0xFFFCFDFC)
private val LobbyInk = Color(0xFF202923)
private val LobbyMuted = Color(0xFF68736C)
private val LobbyGreen = Color(0xFF326B50)

private data class SharedItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long
)

class MainActivity : ComponentActivity() {
    private var server: LocalBridgeServer? = null
    private lateinit var discovery: LobbyDiscovery
    private var incomingShareHandler: ((Intent) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("localbridge", MODE_PRIVATE)
        discovery = LobbyDiscovery(this)
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
                var selectedFiles by remember { mutableStateOf(readSharedItems(intent)) }
                var selectedDevice by remember { mutableStateOf<LobbyDiscovery.Device?>(null) }
                var transferStatus by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                var page by remember { mutableStateOf(if (selectedFiles.isNotEmpty()) "send" else "home") }
                // All Files Access exists only on Android 11 (API 30) and newer.
                // Older versions use the Storage Access Framework (SAF).
                var allFilesMode by remember {
                    mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && prefs.getBoolean("all_files_mode", false))
                }
                var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess()) }
                var folderDialog by remember { mutableStateOf(false) }
                var browserFolder by remember { mutableStateOf(File(prefs.getString("file_root", "/storage/emulated/0") ?: "/storage/emulated/0")) }
                var qrDialog by remember { mutableStateOf(false) }
                var nearbyDevices by remember { mutableStateOf<List<LobbyDiscovery.Device>>(emptyList()) }

                androidx.compose.runtime.DisposableEffect(Unit) {
                    incomingShareHandler = { incomingIntent ->
                        val incoming = readSharedItems(incomingIntent)
                        if (incoming.isNotEmpty()) {
                            selectedFiles = incoming
                            selectedDevice = null
                            transferStatus = "Files ready to send"
                            page = "send"
                        }
                    }
                    onDispose { incomingShareHandler = null }
                }

                androidx.compose.runtime.DisposableEffect(page) {
                    if (page == "home" || page == "send") {
                        discovery.startDiscovery { devices ->
                            runOnUiThread { nearbyDevices = devices }
                        }
                    }
                    onDispose { discovery.stopDiscovery() }
                }

                androidx.compose.runtime.DisposableEffect(this@MainActivity) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allFilesGranted = hasAllFilesAccess()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                val sharedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
                        shareUri = uri
                        prefs.edit().putString("shared_uri", uri.toString()).apply()
                        status = "Shared folder updated"
                    }
                }
                val sendFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    if (uris.isNotEmpty()) {
                        uris.forEach { uri ->
                            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                        }
                        selectedFiles = (selectedFiles + uris.map { describeSharedItem(it) }).distinctBy { it.uri.toString() }
                        transferStatus = "${selectedFiles.size} file(s) ready"
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
                    if (page == "home" || page == "settings" || page == "send") {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (page == "home") {
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
                                Text("RECEIVE ON THIS DEVICE", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                Text(if (running) "LoBBY is on" else "Turn on LoBBY to receive files", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = LobbyInk)
                                Text(if (running) "This device is discoverable and ready to receive files from nearby LoBBY devices and browsers." else "Make this device discoverable on your local network so nearby devices can send files here.", color = LobbyMuted, fontSize = 14.sp)
                                if (url != null) {
                                    Surface(color = Color(0xFFF0F4F0), shape = RoundedCornerShape(14.dp)) {
                                        Text(url!!, modifier = Modifier.fillMaxWidth().clickable {
                                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { status = "No browser available to open the link" }
                                        }.padding(13.dp), color = LobbyGreen, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { qrDialog = true }, modifier = Modifier.weight(1f)) { Text("Show QR code") }
                                        OutlinedButton(onClick = {
                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("LoBBY address", url))
                                            status = "Address copied"
                                        }, modifier = Modifier.weight(1f)) { Text("Copy link") }
                                    }
                                }
                                Button(onClick = {
                                    if (!running) try {
                                        if (allFilesMode && !allFilesGranted) throw IllegalStateException("Grant All Files Access before starting")
                                        if (allFilesMode && (!browserFolder.exists() || !browserFolder.isDirectory)) throw IllegalStateException("Selected folder is unavailable")
                                        prefs.edit().putString("share_path", sharePath).apply()
                                        val nativeRoot = if (allFilesMode && allFilesGranted) browserFolder else null
                                        server = LocalBridgeServer(this@MainActivity, if (allFilesMode) null else shareUri, siteUri, sharePath, nativeRoot).also { it.start() }
                                        discovery.advertise(8765, "LoBBY-${android.os.Build.MODEL}", sharePath)
                                        url = lanIp()?.let { "http://$it:8765" }
                                        running = true
                                        status = "Receive is active. Nearby LoBBY discovery is enabled."
                                    } catch (e: Exception) { status = "Start failed: ${e.message}" }
                                    else { server?.stop(); server = null; discovery.stopAdvertising(); running = false; url = null; status = "Server stopped" }
                                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                                    Text(if (running) "Turn off LoBBY" else "Turn on LoBBY", fontWeight = FontWeight.SemiBold)
                                }
                                Text(status, color = LobbyMuted, fontSize = 12.sp)
                            }
                        }

                        }

                        if (page == "home") {
                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("NEARBY DEVICES", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                                        Text("Scanning", color = LobbyGreen, fontSize = 11.sp)
                                    }
                                    if (nearbyDevices.isEmpty()) {
                                        Text("No nearby LoBBY devices yet. Turn on LoBBY on the other device and keep both devices on the same Wi-Fi or hotspot.", color = LobbyMuted, fontSize = 13.sp)
                                    } else {
                                        nearbyDevices.forEach { device ->
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    selectedDevice = device
                                                    transferStatus = if (selectedFiles.isEmpty()) "Choose files to send to ${device.name}" else "${selectedFiles.size} file(s) ready for ${device.name}"
                                                    page = "send"
                                                },
                                                color = Color(0xFFF2F6F2),
                                                shape = RoundedCornerShape(15.dp)
                                            ) {
                                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(device.name.removePrefix("LoBBY-"), color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                                        Text("LoBBY · Available · ${device.host}", color = LobbyMuted, fontSize = 12.sp)
                                                    }
                                                    Text("Send  →", color = LobbyGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }
                                    Text("Tap a device to choose files and send them. Devices shown here have LoBBY sharing turned on.", color = LobbyMuted, fontSize = 11.sp)
                                }
                            }
                        }

                        if (page == "send") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { page = "home" }) { Text("← Home") }
                                Spacer(Modifier.weight(1f))
                                Text("SEND FILES", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                            }
                            Text("Send files", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = LobbyInk)
                            Text("Choose files, then select a nearby LoBBY device.", color = LobbyMuted, fontSize = 13.sp)

                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("FILES (${selectedFiles.size})", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        TextButton(onClick = { sendFilePicker.launch(arrayOf("*/*")) }, enabled = !sending) { Text("+ Add files") }
                                    }
                                    if (selectedFiles.isEmpty()) {
                                        Text("No files selected. Add files here, or use Android's Share menu and choose LoBBY.", color = LobbyMuted, fontSize = 13.sp)
                                    } else {
                                        selectedFiles.forEachIndexed { index, item ->
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(item.name, color = LobbyInk, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                                    Text("${formatFileSize(item.size)} · ${item.mimeType}", color = LobbyMuted, fontSize = 11.sp)
                                                }
                                                TextButton(enabled = !sending, onClick = { selectedFiles = selectedFiles.filterIndexed { i, _ -> i != index } }) { Text("Remove") }
                                            }
                                            if (index != selectedFiles.lastIndex) HorizontalDivider(color = Color(0xFFE7ECE7))
                                        }
                                    }
                                }
                            }

                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("SEND TO", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    if (nearbyDevices.isEmpty()) {
                                        Text("Searching for LoBBY devices… Turn on LoBBY on the receiving device.", color = LobbyMuted, fontSize = 13.sp)
                                    } else {
                                        nearbyDevices.forEach { device ->
                                            val isSelected = selectedDevice?.baseUrl == device.baseUrl
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().clickable(enabled = !sending) {
                                                    selectedDevice = device
                                                    transferStatus = "Ready to send to ${device.name.removePrefix("LoBBY-")}"
                                                },
                                                color = if (isSelected) Color(0xFFE7F0E8) else Color(0xFFF5F7F5),
                                                shape = RoundedCornerShape(13.dp)
                                            ) {
                                                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(device.name.removePrefix("LoBBY-"), color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                                        Text("${device.host}:${device.port}", color = LobbyMuted, fontSize = 11.sp)
                                                    }
                                                    Text(if (isSelected) "Selected ✓" else "Select", color = LobbyGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                    Button(
                                        enabled = !sending && selectedFiles.isNotEmpty() && selectedDevice != null,
                                        onClick = {
                                            val target = selectedDevice ?: return@Button
                                            sending = true
                                            transferStatus = "Connecting to ${target.name.removePrefix("LoBBY-")}…"
                                            sendFilesToDevice(target, selectedFiles) { message, complete ->
                                                runOnUiThread {
                                                    transferStatus = message
                                                    sending = !complete
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(vertical = 14.dp)
                                    ) {
                                        Text(if (sending) "Sending…" else "Send ${selectedFiles.size} file(s)", fontWeight = FontWeight.SemiBold)
                                    }
                                    if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (transferStatus.isNotBlank()) Text(transferStatus, color = LobbyMuted, fontSize = 12.sp)
                                    selectedDevice?.let { target ->
                                        OutlinedButton(
                                            enabled = !sending,
                                            onClick = {
                                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.baseUrl))) }
                                                catch (_: Exception) { transferStatus = "No browser available to open this device" }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(13.dp)
                                        ) { Text("Open this device in browser instead") }
                                    }
                                    Text("Both devices must be on the same local network. Files are sent through LoBBY's existing HTTP transfer API.", color = LobbyMuted, fontSize = 11.sp)
                                }
                            }
                        }

                        if (page == "settings") {
                        Text("STORAGE", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Shared folder", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = allFilesMode,
                                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !running,
                                        onClick = { allFilesMode = true; prefs.edit().putBoolean("all_files_mode", true).apply() },
                                        label = { Text("All Files Access (Android 11+)") }
                                    )
                                    FilterChip(selected = !allFilesMode, onClick = { if (!running) { allFilesMode = false; prefs.edit().putBoolean("all_files_mode", false).apply() } }, label = { Text("SAF") })
                                }
                                if (allFilesMode) {
                                    Text(if (allFilesGranted) browserFolder.absolutePath else "All Files Access permission required", color = LobbyMuted, fontSize = 12.sp)
                                    OutlinedButton(enabled = !running, onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                                            try {
                                                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                                            } catch (_: Exception) {
                                                try {
                                                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                                } catch (_: Exception) {
                                                    status = "All Files Access settings are unavailable. Use SAF instead."
                                                }
                                            }
                                        } else if (hasAllFilesAccess()) {
                                            folderDialog = true
                                        } else {
                                            status = "Use SAF on Android versions below 11."
                                        }
                                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text(if (allFilesGranted) "Browse internal storage" else "Grant All Files Access") }
                                    if (allFilesGranted) OutlinedButton(enabled = !running, onClick = { folderDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Select this folder / Change folder") }
                                } else {
                                    Text(shareUri?.lastPathSegment ?: "Choose a folder using Android's folder picker", color = LobbyMuted, fontSize = 13.sp)
                                    OutlinedButton(enabled = !running, onClick = { sharedPicker.launch(shareUri) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("Choose shared folder (SAF)") }
                                }
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
                            NavPillItem("Settings", page == "settings") { page = "settings" }
                            NavPillItem("About", page == "about") { page = "about" }
                            NavPillItem("Help", page == "help") { page = "help" }
                        }
                    }

                    if (folderDialog) {
                        val entries = remember(browserFolder) { browserFolder.listFiles()?.filter { it.isDirectory && it.canRead() }?.sortedBy { it.name.lowercase() } ?: emptyList() }
                        AlertDialog(
                            onDismissRequest = { folderDialog = false },
                            title = { Text("Choose shared folder") },
                            text = {
                                Column(Modifier.heightIn(max = 420.dp)) {
                                    Text(browserFolder.absolutePath, color = LobbyGreen, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { browserFolder.parentFile?.let { browserFolder = it } }) { Text("Up") }
                                        TextButton(onClick = {
                                            prefs.edit().putString("file_root", browserFolder.absolutePath).putBoolean("all_files_mode", true).apply()
                                            allFilesMode = true; shareUri = null; folderDialog = false; status = "Shared folder selected"
                                        }) { Text("Select this folder") }
                                    }
                                    androidx.compose.foundation.lazy.LazyColumn {
                                        items(entries.size) { index ->
                                            val entry = entries[index]
                                            Text("📁  ${entry.name}", Modifier.fillMaxWidth().clickable { browserFolder = entry }.padding(vertical = 10.dp))
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { folderDialog = false }) { Text("Done") } }
                        )
                    }
                    if (qrDialog && url != null) {
                        val bitmap = remember(url) { createQrBitmap(url!!) }
                        Dialog(onDismissRequest = { qrDialog = false }) {
                            Surface(shape = RoundedCornerShape(24.dp), color = LobbySurface) {
                                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Browser connection QR", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    Image(bitmap.asImageBitmap(), contentDescription = "QR code for ${url}", modifier = Modifier.size(240.dp))
                                    Text(url!!, color = LobbyGreen, textAlign = TextAlign.Center, fontSize = 13.sp)
                                    Text("Connect the other device to the same Wi-Fi or manually created hotspot first. This QR opens the browser fallback, not a Wi-Fi join QR.", color = LobbyMuted, textAlign = TextAlign.Center, fontSize = 12.sp)
                                    TextButton(onClick = { qrDialog = false }) { Text("Close") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShareHandler?.invoke(intent)
    }

    private fun readSharedItems(source: Intent?): List<SharedItem> {
        if (source == null) return emptyList()
        val uris = mutableListOf<Uri>()
        when (source.action) {
            Intent.ACTION_SEND -> {
                (source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(uris::add)
                source.data?.let(uris::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streams = source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                streams?.let(uris::addAll)
            }
        }
        source.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
        }
        return uris.distinctBy { it.toString() }.map { describeSharedItem(it) }
    }

    private fun describeSharedItem(uri: Uri): SharedItem {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) { }
        name = name.replace("/", "_").replace("\\", "_").takeUnless { it == "." || it == ".." }.orEmpty().ifBlank { "shared-file" }
        return SharedItem(uri, name, contentResolver.getType(uri) ?: "application/octet-stream", size)
    }

    private fun sendFilesToDevice(
        device: LobbyDiscovery.Device,
        files: List<SharedItem>,
        onProgress: (String, Boolean) -> Unit
    ) {
        Thread {
            try {
                files.forEachIndexed { index, item ->
                    onProgress("Sending ${index + 1} of ${files.size}: ${item.name}", false)
                    var stagedFile: File? = null
                    try {
                        val uploadSize = if (item.size >= 0) item.size else {
                            stagedFile = File.createTempFile("lobby-share-", ".tmp", cacheDir)
                            contentResolver.openInputStream(item.uri)?.use { input ->
                                stagedFile!!.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                            } ?: throw IOException("Cannot open ${item.name}")
                            stagedFile!!.length()
                        }
                        val encodedName = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                        val endpoint = "${device.baseUrl}${device.sharePath.trimEnd('/')}/$encodedName"
                        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            requestMethod = "PUT"
                            doOutput = true
                            connectTimeout = 15_000
                            readTimeout = 60_000
                            setRequestProperty("Content-Type", item.mimeType)
                            setRequestProperty("Connection", "close")
                            setFixedLengthStreamingMode(uploadSize)
                        }
                        try {
                            val input = stagedFile?.inputStream() ?: contentResolver.openInputStream(item.uri)
                                ?: throw IOException("Cannot open ${item.name}")
                            input.use { source ->
                                connection.outputStream.use { output ->
                                    val buffer = ByteArray(256 * 1024)
                                    while (true) {
                                        val count = source.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            val response = connection.responseCode
                            if (response !in 200..299) {
                                val detail = try { connection.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                                throw IOException("${item.name}: HTTP $response${detail?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}")
                            }
                        } finally {
                            connection.disconnect()
                        }
                    } finally {
                        stagedFile?.delete()
                    }
                }
                onProgress("Transfer complete · ${files.size} file(s) sent", true)
            } catch (error: Exception) {
                onProgress("Transfer failed: ${error.message ?: "Connection error"}", true)
            }
        }.start()
    }

    /** Returns false below Android 11 without invoking an API unavailable on those versions. */
    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    override fun onDestroy() { incomingShareHandler = null; server?.stop(); server = null; discovery.stop(); super.onDestroy() }

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


private fun formatFileSize(size: Long): String {
    if (size < 0) return "Size unavailable"
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

@Composable
private fun InfoPage(page: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current

    fun openLink(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("LoBBY", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = LobbyInk)
        Text(if (page == "help") "Help & troubleshooting" else "About LoBBY", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = LobbyInk)
        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (page == "help") {
                    InfoHeading("Connect devices")
                    Text("For now, connect both devices to the same Wi-Fi network or to the same manually created hotspot. Internet access is not required.")
                    InfoHeading("Device discovery")
                    Text("Turn on LoBBY on the receiving device so it can advertise its service. On the sending device, tap the discovered device or share files to LoBBY from Android’s Share menu.")
                    InfoHeading("Can't find another device?")
                    Text("Check that both devices are on the same network, that the router allows communication between clients, and that local-network access is not blocked by a guest network or firewall.")
                    InfoHeading("Browser compatibility")
                    Text("If the other device does not have a LoBBY app, use the displayed local address or scan the connection QR code in a browser on the same network.")
                    InfoHeading("Hotspot setup")
                    Text("You can create a hotspot manually in Android Settings and connect the other device. Automatic temporary hotspot setup and QR-based Wi-Fi joining are planned connection options, not yet enabled in this build.")
                } else {
                    InfoHeading("The project")
                    Text("LoBBY by Code-xed is a local-first file sharing project. Its direction is a shared, cross-platform transfer protocol with platform-specific networking adapters for Android, iOS and desktop systems.")
                    Text("Android builds provide a local HTTP server, native file selection and Share Sheet intake, mDNS discovery, and native sending through the same HTTP API used by the browser interface.", color = LobbyMuted)
                    Button(onClick = { openLink("https://github.com/Code-xed/Local-Bridge") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("View LoBBY on GitHub") }

                    InfoHeading("Privacy and network access")
                    Text("LoBBY does not require an account or cloud service for local transfers. The current browser server uses HTTP without encryption or authentication. Only run it on a trusted network, share folders you intend to expose, and stop sharing when finished.")
                    Text("This codebase contains no analytics or advertising integration. Android and included open-source libraries remain subject to their own platform and license terms.", color = LobbyMuted, fontSize = 13.sp)

                    InfoHeading("Copyright and license")
                    Text("Copyright © 2026 Code-xed. LoBBY's original source code and project documentation are distributed under the Apache License, Version 2.0.")
                    OutlinedButton(onClick = { openLink("https://www.apache.org/licenses/LICENSE-2.0") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Read Apache License 2.0") }

                    InfoHeading("Open-source acknowledgements")
                    FossCredit("NanoHTTPD 2.3.1", "BSD 3-Clause", "https://github.com/NanoHttpd/nanohttpd")
                    FossCredit("AndroidX Core KTX 1.15.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/core")
                    FossCredit("AndroidX DocumentFile 1.0.1", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/documentfile")
                    FossCredit("AndroidX Activity Compose 1.10.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/activity")
                    FossCredit("Jetpack Compose UI and Material 3", "Apache-2.0", "https://developer.android.com/jetpack/compose")
                    FossCredit("ZXing Core 3.5.3", "Apache-2.0", "https://github.com/zxing/zxing")
                    Text("See THIRD_PARTY_NOTICES.md for additional dependency notices.", color = LobbyMuted, fontSize = 12.sp)

                    InfoHeading("Support LoBBY")
                    Text("LoBBY is free to use. If you want to support its continued development, contributions are entirely optional and do not unlock features.")
                    Button(onClick = { openLink("https://buymeacoffee.com/code.xed") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("☕  Buy Me a Coffee") }
                }
            }
        }
    }
}

@Composable
private fun FossCredit(name: String, license: String, url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().clickable {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }.padding(vertical = 5.dp)) {
        Text(name, fontWeight = FontWeight.Medium, color = LobbyInk)
        Text("$license  ·  View project ↗", color = LobbyGreen, fontSize = 12.sp)
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

private fun createQrBitmap(value: String): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640)
    val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    for (x in 0 until 640) for (y in 0 until 640) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bitmap
}
