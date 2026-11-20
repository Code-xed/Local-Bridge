package app.localbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.graphics.Bitmap
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.provider.OpenableColumns
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
import java.io.IOException
import java.util.concurrent.Executors

private val LobbyBg = Color(0xFFF4F5F3)
private val LobbySurface = Color(0xFFFCFDFC)
private val LobbyInk = Color(0xFF202923)
private val LobbyMuted = Color(0xFF68736C)
private val LobbyGreen = Color(0xFF326B50)

data class SharedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long
)

class MainActivity : ComponentActivity() {
    private var server: LocalBridgeServer? = null
    private lateinit var discovery: LobbyDiscovery
    private val transferExecutor = Executors.newSingleThreadExecutor()
    private var incomingShareHandler: ((List<SharedFile>) -> Unit)? = null
    private var deferredSharedFiles: List<SharedFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("localbridge", MODE_PRIVATE)
        discovery = LobbyDiscovery(this)
        val initialSharedFiles = extractSharedFiles(intent)
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
                var page by remember { mutableStateOf(if (initialSharedFiles.isNotEmpty()) "send" else "home") }
                var allFilesMode by remember { mutableStateOf(prefs.getBoolean("all_files_mode", false)) }
                var allFilesGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
                var folderDialog by remember { mutableStateOf(false) }
                var browserFolder by remember { mutableStateOf(File(prefs.getString("file_root", "/storage/emulated/0") ?: "/storage/emulated/0")) }
                var qrDialog by remember { mutableStateOf(false) }
                var nearbyDevices by remember { mutableStateOf<List<LobbyDiscovery.Device>>(emptyList()) }
                var incomingFiles by remember { mutableStateOf(initialSharedFiles) }
                var sending by remember { mutableStateOf(false) }
                var sendStatus by remember { mutableStateOf("") }

                androidx.compose.runtime.DisposableEffect(Unit) {
                    incomingShareHandler = { files ->
                        incomingFiles = files
                        if (files.isNotEmpty()) {
                            sendStatus = "${files.size} file${if (files.size == 1) "" else "s"} ready to send"
                            page = "send"
                        }
                    }
                    if (deferredSharedFiles.isNotEmpty()) {
                        incomingFiles = deferredSharedFiles
                        page = "send"
                        deferredSharedFiles = emptyList()
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
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allFilesGranted = Environment.isExternalStorageManager()
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
                        if (page == "send") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("SEND FILES", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                    Text("Choose a nearby device", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = LobbyInk)
                                }
                                TextButton(onClick = { page = "home" }) { Text("Back") }
                            }
                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("SELECTED FILES", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                                        Text("${incomingFiles.size} selected", color = LobbyGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    if (incomingFiles.isEmpty()) {
                                        Text("No shared files received. Return to your file manager and use Share → LoBBY.", color = LobbyMuted, fontSize = 13.sp)
                                    } else {
                                        incomingFiles.forEachIndexed { index, file ->
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Surface(color = Color(0xFFEAF0EB), shape = RoundedCornerShape(12.dp)) {
                                                    Text("FILE", modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp), color = LobbyGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Column(Modifier.weight(1f)) {
                                                    Text(file.name, color = LobbyInk, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                                    Text("${formatBytes(file.size)}  ·  ${file.mimeType}", color = LobbyMuted, fontSize = 11.sp, maxLines = 1)
                                                }
                                                TextButton(enabled = !sending, onClick = { incomingFiles = incomingFiles.filterIndexed { itemIndex, _ -> itemIndex != index } }) { Text("Remove") }
                                            }
                                        }
                                    }
                                }
                            }
                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("NEARBY LOBBY DEVICES", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                                        Text("Scanning", color = LobbyGreen, fontSize = 11.sp)
                                    }
                                    if (nearbyDevices.isEmpty()) {
                                        Text("No receiving devices found. On the other device, turn on LoBBY sharing and keep both devices on the same Wi-Fi or hotspot.", color = LobbyMuted, fontSize = 13.sp)
                                    } else {
                                        nearbyDevices.forEach { device ->
                                            Surface(color = Color(0xFFF3F6F3), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    Text(device.name, color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                                    Text("${device.host}:${device.port}", color = LobbyMuted, fontSize = 12.sp)
                                                    Button(
                                                        enabled = incomingFiles.isNotEmpty() && !sending,
                                                        onClick = {
                                                            sending = true
                                                            sendStatus = "Connecting to ${device.name}…"
                                                            val filesToSend = incomingFiles.toList()
                                                            transferExecutor.execute {
                                                                try {
                                                                    filesToSend.forEachIndexed { fileIndex, file ->
                                                                        sendSharedFile(file, device) { sentBytes ->
                                                                            runOnUiThread { sendStatus = "Sending ${fileIndex + 1}/${filesToSend.size}: ${file.name} · ${formatBytes(sentBytes)}" }
                                                                        }
                                                                    }
                                                                    runOnUiThread {
                                                                        sending = false
                                                                        sendStatus = "Successfully sent ${filesToSend.size} file${if (filesToSend.size == 1) "" else "s"} to ${device.name}."
                                                                        incomingFiles = emptyList()
                                                                    }
                                                                } catch (error: Exception) {
                                                                    runOnUiThread { sending = false; sendStatus = "Transfer failed: ${error.message ?: "Unknown error"}" }
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) { Text(if (sending) "Sending…" else "Send to this device") }
                                                }
                                            }
                                        }
                                    }
                                    if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (sendStatus.isNotBlank()) Text(sendStatus, color = LobbyMuted, fontSize = 12.sp)
                                    Text("Transfers use the same HTTP upload endpoint as the LoBBY browser interface.", color = LobbyMuted, fontSize = 11.sp)
                                }
                            }
                        }

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
                                Text("LOBBY SHARING", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                Text(if (running) "LoBBY sharing is on" else "Share files to this device", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = LobbyInk)
                                Text(if (running) "This device is discoverable. Other LoBBY devices on your local network can send files here." else "Turn on LoBBY sharing to make this device discoverable and receive files over your local network.", color = LobbyMuted, fontSize = 14.sp)
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
                                        status = "LoBBY sharing is on. Nearby discovery is enabled."
                                    } catch (e: Exception) { status = "Start failed: ${e.message}" }
                                    else { server?.stop(); server = null; discovery.stopAdvertising(); running = false; url = null; status = "LoBBY sharing is off" }
                                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                                    Text(if (running) "Turn off LoBBY sharing" else "Turn on LoBBY sharing", fontWeight = FontWeight.SemiBold)
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
                                        Text("No LoBBY devices found yet. Turn on LoBBY sharing on the other device.", color = LobbyMuted, fontSize = 13.sp)
                                    } else {
                                        nearbyDevices.forEach { device ->
                                            Column(Modifier.fillMaxWidth().clickable {
                                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(device.baseUrl))) } catch (_: Exception) { status = "No browser available" }
                                            }.padding(vertical = 6.dp)) {
                                                Text(device.name, color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                                Text("${device.host}:${device.port}  ·  Open browser interface", color = LobbyMuted, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    Text("Tap a device here to open its browser interface, or share a file to LoBBY to send through the same HTTP transfer API.", color = LobbyMuted, fontSize = 11.sp)
                                }
                            }
                        }

                        if (page == "settings") {
                        Text("STORAGE", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Shared folder", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = allFilesMode, onClick = { if (!running) { allFilesMode = true; prefs.edit().putBoolean("all_files_mode", true).apply() } }, label = { Text("All Files Access") })
                                    FilterChip(selected = !allFilesMode, onClick = { if (!running) { allFilesMode = false; prefs.edit().putBoolean("all_files_mode", false).apply() } }, label = { Text("SAF") })
                                }
                                if (allFilesMode) {
                                    Text(if (allFilesGranted) browserFolder.absolutePath else "All Files Access permission required", color = LobbyMuted, fontSize = 12.sp)
                                    OutlinedButton(enabled = !running, onClick = {
                                        if (!Environment.isExternalStorageManager()) {
                                            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                                            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                                        } else folderDialog = true
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
                            NavPillItem("Home", page == "home" || page == "send") { page = "home" }
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
        val files = extractSharedFiles(intent)
        if (incomingShareHandler != null) incomingShareHandler?.invoke(files) else deferredSharedFiles = files
    }

    override fun onDestroy() {
        incomingShareHandler = null
        server?.stop(); server = null; discovery.stop()
        transferExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun extractSharedFiles(source: Intent?): List<SharedFile> {
        if (source == null) return emptyList()
        val candidates = mutableListOf<Uri>()
        when (source.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val sharedUri = source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                sharedUri?.let(candidates::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val sharedUris = source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                sharedUris?.let(candidates::addAll)
            }
        }
        source.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(candidates::add)
        }
        return candidates.distinctBy { it.toString() }.mapNotNull { uri ->
            try {
                var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
                var size = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }?.let { name = it }
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
                SharedFile(uri, name, contentResolver.getType(uri) ?: source.type ?: "application/octet-stream", size)
            } catch (_: Exception) { null }
        }
    }

    private fun sendSharedFile(file: SharedFile, device: LobbyDiscovery.Device, onProgress: (Long) -> Unit) {
        val safePath = device.sharePath.takeIf {
            it.matches(Regex("/[A-Za-z0-9_/-]+")) && !it.contains("//") && !it.split('/').any { part -> part == "." || part == ".." }
        } ?: "/files"
        val encodedName = Uri.encode(file.name)
        // NanoHTTPD's existing PUT handler expects a known request length. Stage only
        // providers that do not report a size; ordinary content URIs stream directly.
        val stagedFile = if (file.size < 0) {
            val temporary = File.createTempFile("lobby-share-", ".upload", cacheDir)
            try {
                (contentResolver.openInputStream(file.uri) ?: throw IOException("Cannot open ${file.name}")).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }
                temporary
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        } else null
        val uploadSize = stagedFile?.length() ?: file.size
        val connection = (URL("${device.baseUrl}${safePath.trimEnd('/')}/$encodedName").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", file.mimeType)
            setFixedLengthStreamingMode(uploadSize)
        }
        try {
            val input = stagedFile?.inputStream() ?: contentResolver.openInputStream(file.uri)
                ?: throw IOException("Cannot open ${file.name}")
            input.use { source ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        onProgress(total)
                    }
                    output.flush()
                }
            }
            val response = connection.responseCode
            if (response !in 200..299) {
                val detail = try { connection.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                throw IOException("HTTP $response${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
        } finally {
            connection.disconnect()
            stagedFile?.delete()
        }
    }

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


private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Size unavailable"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do { value /= 1024.0; unit++ } while (value >= 1024.0 && unit < units.lastIndex)
    return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
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
                    Text("Keep LoBBY open on both devices. On the receiving Android device, turn on LoBBY sharing so it advertises its HTTP service on the local network.")
                    InfoHeading("Can't find another device?")
                    Text("Check that both devices are on the same network, that the router allows communication between clients, and that local-network access is not blocked by a guest network or firewall.")
                    InfoHeading("Browser compatibility")
                    Text("If the other device does not have a LoBBY app, use the displayed local address or scan the connection QR code in a browser on the same network.")
                    InfoHeading("Hotspot setup")
                    Text("You can create a hotspot manually in Android Settings and connect the other device. Automatic temporary hotspot setup and QR-based Wi-Fi joining are planned connection options, not yet enabled in this build.")
                } else {
                    InfoHeading("The project")
                    Text("LoBBY by Code-xed is a local-first file sharing project. Its direction is a shared, cross-platform transfer protocol with platform-specific networking adapters for Android, iOS and desktop systems.")
                    Text("The Android app and browser interface use the same HTTP transfer API. Native clients reuse the existing upload and download endpoints rather than introducing a separate transfer protocol.", color = LobbyMuted)
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
