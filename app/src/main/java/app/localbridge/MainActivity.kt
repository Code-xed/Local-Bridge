package app.localbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private val server: LocalBridgeServer? get() = LobbyServerService.activeServer
    private lateinit var discovery: LobbyDiscovery
    private var incomingShareHandler: ((Intent) -> Unit)? = null
    private var activeTransferOperation: TransferOperation? = null
    private var activePairingOperation: TransferOperation? = null
    private var activeBenchmarkOperation: TransferOperation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("localbridge", MODE_PRIVATE)
        discovery = LobbyDiscovery(this)
        LobbyNotifications.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 901)
        }
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
                var running by remember { mutableStateOf(LobbyServerService.activeServer != null) }
                var url by remember { mutableStateOf<String?>(null) }
                var status by remember { mutableStateOf("Ready when you are") }
                var shareUri by remember { mutableStateOf(prefs.getString("shared_uri", null)?.let(Uri::parse)) }
                var siteUri by remember { mutableStateOf(prefs.getString("website_uri", null)?.let(Uri::parse)) }
                var sharePath by remember { mutableStateOf(prefs.getString("share_path", "/files") ?: "/files") }
                var selectedFiles by remember { mutableStateOf(readSharedItems(intent)) }
                var selectedDevice by remember { mutableStateOf<LobbyDiscovery.Device?>(null) }
                var transferStatus by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                var transferProgress by remember { mutableStateOf<TransferProgress?>(null) }
                var transferOperation by remember { mutableStateOf<TransferOperation?>(null) }
                var pairingOperation by remember { mutableStateOf<TransferOperation?>(null) }
                var pairingCode by remember { mutableStateOf<String?>(null) }
                var pairingDeviceName by remember { mutableStateOf("") }
                var pairingStatus by remember { mutableStateOf("") }
                var pairingInProgress by remember { mutableStateOf(false) }
                var incomingTransfers by remember { mutableStateOf<List<IncomingTransferInfo>>(emptyList()) }
                var pairingRequests by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
                var historyEntries by remember { mutableStateOf(TransferHistoryStore.load(this@MainActivity)) }
                var selectedHistoryTransferId by remember { mutableStateOf(intent.getStringExtra("lobby_transfer_id")) }
                var serverRevision by remember { mutableIntStateOf(0) }
                val securityPrefs = getSharedPreferences("lobby_security", MODE_PRIVATE)
                var autoAcceptTrusted by remember { mutableStateOf(securityPrefs.getBoolean("auto_accept_trusted", false)) }
                var receiveWithoutConfirmation by remember { mutableStateOf(securityPrefs.getBoolean("receive_without_confirmation", false)) }
                var performanceProfile by remember { mutableStateOf(TransferPerformance.loadProfile(this@MainActivity)) }
                var performanceTuning by remember { mutableStateOf(TransferPerformance.loadTuning(this@MainActivity)) }
                var benchmarkStatus by remember { mutableStateOf("No benchmark run yet") }
                var benchmarkRunning by remember { mutableStateOf(false) }
                var benchmarkOperation by remember { mutableStateOf<TransferOperation?>(null) }
                var benchmarkPeer by remember { mutableStateOf<PairBenchmark?>(null) }
                var benchmarkDevice by remember { mutableStateOf<LobbyDiscovery.Device?>(null) }
                var renamePeer by remember { mutableStateOf<LobbyIdentity.TrustedPeer?>(null) }
                var renameDeviceName by remember { mutableStateOf("") }
                var page by remember {
                    mutableStateOf(when {
                        selectedFiles.isNotEmpty() -> "send"
                        intent.getBooleanExtra("lobby_open_inbox", false) -> "inbox"
                        else -> "home"
                    })
                }
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
                        incomingIntent.getStringExtra("lobby_transfer_id")?.let { selectedHistoryTransferId = it }
                        if (incomingIntent.hasExtra("lobby_open_inbox")) {
                            if (incomingIntent.getBooleanExtra("lobby_open_inbox", false)) {
                                historyEntries = TransferHistoryStore.load(this@MainActivity)
                                page = "inbox"
                            } else if (incoming.isEmpty()) {
                                page = "home"
                            }
                        }
                    }
                    onDispose { incomingShareHandler = null }
                }

                androidx.compose.runtime.LaunchedEffect(running, page, serverRevision) {
                    while (running) {
                        val activeServer = this@MainActivity.server
                        incomingTransfers = activeServer?.pendingTransfers().orEmpty()
                        pairingRequests = activeServer?.pendingPairRequests().orEmpty()
                        historyEntries = TransferHistoryStore.load(this@MainActivity)
                        kotlinx.coroutines.delay(250)
                    }
                }

                androidx.compose.runtime.LaunchedEffect(selectedHistoryTransferId, historyEntries) {
                    if (selectedHistoryTransferId != null && historyEntries.any { it.id == selectedHistoryTransferId }) {
                        page = "inbox"
                    }
                }

                androidx.compose.runtime.DisposableEffect(page) {
                    if (page == "home" || page == "send" || page == "settings") {
                        discovery.startDiscovery { devices ->
                            val ownDeviceId = try { LobbyIdentity.get(this@MainActivity).deviceId } catch (_: Exception) { null }
                            runOnUiThread { nearbyDevices = devices.filterNot { ownDeviceId != null && it.deviceId == ownDeviceId } }
                        }
                    }
                    onDispose { discovery.stopDiscovery() }
                }

                androidx.compose.runtime.DisposableEffect(this@MainActivity) {
                    LobbyServerService.uiListener = { runOnUiThread { serverRevision++ } }
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            allFilesGranted = hasAllFilesAccess()
                            historyEntries = TransferHistoryStore.load(this@MainActivity)
                            running = this@MainActivity.server != null
                            if (running) url = lanIp()?.let { "http://$it:8765" }
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose {
                        lifecycle.removeObserver(observer)
                        LobbyServerService.uiListener = null
                    }
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
                    if (page == "home" || page == "settings" || page == "send" || page == "inbox") {
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

                        if (pairingRequests.isNotEmpty()) {
                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("PAIRING REQUESTS", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    pairingRequests.forEach { request ->
                                        Text(request.optString("name", "LoBBY device"), color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                        Text("Verification code: ${request.optString("code")} · Compare this code with the sender before accepting.", color = LobbyMuted, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = {
                                                val result = server?.decidePairingLocally(request.optString("nonce"), true) ?: "expired"
                                                status = if (result == "accepted") "Device paired successfully" else "Pairing failed: $result"
                                                serverRevision++
                                            }, modifier = Modifier.weight(1f)) { Text("Accept & trust") }
                                            OutlinedButton(onClick = {
                                                server?.decidePairingLocally(request.optString("nonce"), false)
                                                serverRevision++
                                            }, modifier = Modifier.weight(1f)) { Text("Decline") }
                                        }
                                        if (request != pairingRequests.last()) HorizontalDivider(color = Color(0xFFE7ECE7))
                                    }
                                }
                            }
                        }

                        if (incomingTransfers.isNotEmpty()) {
                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("INCOMING TRANSFERS", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    incomingTransfers.forEach { request ->
                                        Text(request.senderName, color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                        Text(request.files.joinToString(" · ") { it.name }, color = LobbyInk, fontSize = 13.sp, maxLines = 3)
                                        Text("${request.files.size} file(s) · ${formatFileSize(request.totalBytes)}", color = LobbyMuted, fontSize = 12.sp)
                                        if (request.status == "pending") {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = {
                                                    val result = server?.decideTransferLocally(request.id, true) ?: "expired"
                                                    status = if (result == "accepted") "Transfer accepted" else "Could not accept: $result"
                                                    serverRevision++
                                                }, modifier = Modifier.weight(1f)) { Text("Accept") }
                                                OutlinedButton(onClick = {
                                                    server?.decideTransferLocally(request.id, false)
                                                    status = "Transfer declined"
                                                    serverRevision++
                                                }, modifier = Modifier.weight(1f)) { Text("Decline") }
                                            }
                                        } else {
                                            val receivedFraction = if (request.totalBytes > 0L) (request.receivedBytes.toFloat() / request.totalBytes).coerceIn(0f, 1f) else 0f
                                            Text("${request.status.replaceFirstChar { it.uppercase() }} · ${formatFileSize(request.receivedBytes)} / ${formatFileSize(request.totalBytes)}", color = LobbyGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            LinearProgressIndicator(progress = receivedFraction, modifier = Modifier.fillMaxWidth())
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(formatSpeed(request.speedBytesPerSecond), color = LobbyGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                Text(request.etaSeconds?.let { "${formatDuration(it)} left" } ?: "Calculating ETA…", color = LobbyMuted, fontSize = 11.sp)
                                            }
                                            request.files.forEachIndexed { fileIndex, file ->
                                                val fileReceived = request.fileReceivedBytes.getOrNull(fileIndex) ?: 0L
                                                val fileFraction = if (file.size > 0L) (fileReceived.toFloat() / file.size).coerceIn(0f, 1f) else if (request.status == "receiving") 0f else 1f
                                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text(file.name, color = LobbyMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                                        Text("${(fileFraction * 100).toInt()}% · ${formatFileSize(fileReceived)} / ${formatFileSize(file.size)}", color = LobbyMuted, fontSize = 10.sp)
                                                    }
                                                    LinearProgressIndicator(progress = fileFraction, modifier = Modifier.fillMaxWidth())
                                                }
                                            }
                                        }
                                        if (request != incomingTransfers.last()) HorizontalDivider(color = Color(0xFFE7ECE7))
                                    }
                                }
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
                                        prefs.edit().putString("share_path", sharePath)
                                            .putString("shared_uri", shareUri?.toString())
                                            .putString("website_uri", siteUri?.toString())
                                            .putBoolean("all_files_mode", allFilesMode)
                                            .putString("file_root", browserFolder.absolutePath)
                                            .apply()
                                        val serviceIntent = Intent(this@MainActivity, LobbyServerService::class.java)
                                        androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                        url = lanIp()?.let { "http://$it:8765" }
                                        running = true
                                        status = "Starting LoBBY receiving service…"
                                    } catch (e: Exception) { status = "Start failed: ${e.message}" }
                                    else {
                                        stopService(Intent(this@MainActivity, LobbyServerService::class.java))
                                        running = false
                                        url = null
                                        status = "Server stopped"
                                    }
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
                            Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable { page = "inbox" }) {
                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("RECEIVED & HISTORY", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        Text("${historyEntries.count { it.status == "completed" }} received file(s) · ${historyEntries.size} history item(s)", color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text("View →", color = LobbyGreen, fontWeight = FontWeight.SemiBold)
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
                                                        Text(if (device.deviceId?.let { LobbyIdentity.findTrustedPeer(this@MainActivity, it) != null } == true) "Paired · Trusted" else "Not paired yet", color = if (device.deviceId?.let { LobbyIdentity.findTrustedPeer(this@MainActivity, it) != null } == true) LobbyGreen else LobbyMuted, fontSize = 10.sp)
                                                    }
                                                    Text(if (isSelected) "Selected ✓" else "Select", color = LobbyGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                    val selectedPeer = selectedDevice
                                    val selectedPeerTrusted = selectedPeer?.deviceId?.let { LobbyIdentity.findTrustedPeer(this@MainActivity, it) != null } == true
                                    if (selectedPeer != null && !selectedPeerTrusted) {
                                        OutlinedButton(
                                            enabled = !pairingInProgress && !sending,
                                            onClick = {
                                                pairingInProgress = true
                                                pairingCode = null
                                                pairingStatus = "Waiting for the receiving device to display and approve the same verification code…"
                                                pairingOperation = NativeTransferClient(this@MainActivity).beginPairing(
                                                    selectedPeer,
                                                    onCode = { code, name, remoteId -> runOnUiThread {
                                                        pairingCode = code
                                                        pairingDeviceName = name
                                                        selectedDevice = selectedPeer.copy(deviceId = remoteId)
                                                        nearbyDevices = nearbyDevices.map { if (it.baseUrl == selectedPeer.baseUrl) it.copy(deviceId = remoteId) else it }
                                                    } },
                                                    onFinished = { success, message -> runOnUiThread {
                                                        pairingInProgress = false
                                                        pairingStatus = message
                                                        if (!success) pairingCode = null
                                                        transferStatus = message
                                                    } }
                                                )
                                                activePairingOperation = pairingOperation
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp)
                                        ) { Text(if (pairingInProgress) "Pairing…" else "Pair this device (optional)") }
                                        Text("Pairing saves this device as trusted for future automatic acceptance. You can send without pairing.", color = LobbyMuted, fontSize = 11.sp)
                                    }
                                    Button(
                                        enabled = !sending && selectedFiles.isNotEmpty() && selectedPeer != null,
                                        onClick = {
                                            val target = selectedPeer ?: return@Button
                                            sending = true
                                            transferProgress = null
                                            transferStatus = "Requesting permission from ${target.name.removePrefix("LoBBY-")}…"
                                            val payloads = selectedFiles.map { TransferPayload(it.uri, it.name, it.mimeType, it.size) }
                                            transferOperation = NativeTransferClient(this@MainActivity).startTransfer(target, payloads, performanceTuning) { progress ->
                                                runOnUiThread {
                                                    transferProgress = progress
                                                    transferStatus = progress.status
                                                    if (progress.finished) sending = false
                                                }
                                            }
                                            activeTransferOperation = transferOperation
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(vertical = 14.dp)
                                    ) { Text(if (sending) "Sending…" else "Send ${selectedFiles.size} file(s)", fontWeight = FontWeight.SemiBold) }
                                    transferProgress?.let { progress ->
                                        val fraction = if (progress.error != null) {
                                            if (progress.totalBytes > 0L) (progress.transferredBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) else 0f
                                        } else if (progress.finished) 1f else if (progress.totalBytes > 0L) (progress.transferredBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) else 0f
                                        LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
                                        Text("${(fraction * 100).toInt()}% · ${formatFileSize(progress.transferredBytes)} / ${formatFileSize(progress.totalBytes)}", color = LobbyInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        if (progress.currentFile.isNotBlank() && !progress.finished) {
                                            Text("Current file: ${progress.currentFile}", color = LobbyMuted, fontSize = 11.sp, maxLines = 1)
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(formatSpeed(progress.speedBytesPerSecond), color = LobbyGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            val etaLabel = when {
                                                progress.error != null -> "Failed"
                                                progress.etaSeconds != null -> "${formatDuration(progress.etaSeconds)} left"
                                                progress.transferredBytes <= 0L -> "Waiting to start"
                                                else -> "Estimating ETA…"
                                            }
                                            Text(etaLabel, color = LobbyMuted, fontSize = 12.sp)
                                        }
                                        progress.fileBytes.forEachIndexed { index, transferred ->
                                            val item = selectedFiles.getOrNull(index) ?: return@forEachIndexed
                                            val fileTotal = progress.fileTotalBytes.getOrNull(index) ?: item.size
                                            val itemFraction = if (fileTotal > 0L) (transferred.toFloat() / fileTotal).coerceIn(0f, 1f) else 1f
                                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(item.name, color = LobbyMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                                    Text("${(itemFraction * 100).toInt()}% · ${formatFileSize(transferred)} / ${formatFileSize(fileTotal)}", color = LobbyMuted, fontSize = 11.sp)
                                                }
                                                LinearProgressIndicator(progress = itemFraction, modifier = Modifier.fillMaxWidth())
                                            }
                                        }
                                    }
                                    if (sending) OutlinedButton(onClick = { transferOperation?.cancel(); transferStatus = "Cancelling transfer…" }, modifier = Modifier.fillMaxWidth()) { Text("Cancel transfer") }
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
                                    Text("Both devices must be on the same local network. Native transfers use signed device identity, receiver approval and parallel HTTP chunk uploads. Browser sharing remains available separately.", color = LobbyMuted, fontSize = 11.sp)
                                }
                            }
                        }

                        if (page == "settings") {
                        Text("TRANSFER PERFORMANCE", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Performance profile", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                Text("No artificial bandwidth cap. The engineering target is a 60 MB/s median on capable modern devices; older devices use the fastest stable configuration their Wi-Fi, CPU and storage can sustain. This is a target, not a guarantee.", color = LobbyMuted, fontSize = 12.sp)
                                TransferProfile.values().forEach { preset ->
                                    FilterChip(
                                        selected = performanceProfile == preset,
                                        onClick = {
                                            performanceProfile = preset
                                            performanceTuning = if (preset == TransferProfile.CUSTOM) performanceTuning else TransferTuning.preset(preset)
                                            TransferPerformance.save(this@MainActivity, performanceProfile, performanceTuning)
                                        },
                                        label = { Text(preset.title) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (performanceProfile == TransferProfile.CUSTOM) {
                                    Text("Custom limits", color = LobbyGreen, fontWeight = FontWeight.SemiBold)
                                    Text("Maximum simultaneous files: ${performanceTuning.maxParallelFiles}", fontSize = 12.sp)
                                    Slider(value = performanceTuning.maxParallelFiles.toFloat(), onValueChange = { value ->
                                        performanceTuning = performanceTuning.copy(maxParallelFiles = value.toInt()).normalized()
                                        TransferPerformance.save(this@MainActivity, TransferProfile.CUSTOM, performanceTuning)
                                    }, valueRange = 1f..8f, steps = 6)
                                    Text("Connections per file: ${performanceTuning.connectionsPerFile}", fontSize = 12.sp)
                                    Slider(value = performanceTuning.connectionsPerFile.toFloat(), onValueChange = { value ->
                                        performanceTuning = performanceTuning.copy(connectionsPerFile = value.toInt()).normalized()
                                        TransferPerformance.save(this@MainActivity, TransferProfile.CUSTOM, performanceTuning)
                                    }, valueRange = 1f..8f, steps = 6)
                                    Text("Maximum total connections: ${performanceTuning.maxConnections}", fontSize = 12.sp)
                                    Slider(value = performanceTuning.maxConnections.toFloat(), onValueChange = { value ->
                                        performanceTuning = performanceTuning.copy(maxConnections = value.toInt()).normalized()
                                        TransferPerformance.save(this@MainActivity, TransferProfile.CUSTOM, performanceTuning)
                                    }, valueRange = 1f..24f, steps = 22)
                                    Text("Chunk size: ${performanceTuning.chunkSizeMiB} MiB", fontSize = 12.sp)
                                    Slider(value = performanceTuning.chunkSizeMiB.toFloat(), onValueChange = { value ->
                                        performanceTuning = performanceTuning.copy(chunkSizeMiB = value.toInt()).normalized()
                                        TransferPerformance.save(this@MainActivity, TransferProfile.CUSTOM, performanceTuning)
                                    }, valueRange = 1f..32f, steps = 30)
                                    Text("Per-worker buffer: ${performanceTuning.bufferKiB} KiB", fontSize = 12.sp)
                                    Slider(value = performanceTuning.bufferKiB.toFloat(), onValueChange = { value ->
                                        performanceTuning = performanceTuning.copy(bufferKiB = value.toInt()).normalized()
                                        TransferPerformance.save(this@MainActivity, TransferProfile.CUSTOM, performanceTuning)
                                    }, valueRange = 64f..1024f, steps = 14)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Adaptive optimization", fontWeight = FontWeight.Medium)
                                        Text("Adjust worker usage within the selected limits.", color = LobbyMuted, fontSize = 11.sp)
                                    }
                                    Switch(checked = performanceTuning.adaptive, onCheckedChange = {
                                        performanceTuning = performanceTuning.copy(adaptive = it)
                                        if (performanceProfile != TransferProfile.CUSTOM) performanceProfile = TransferProfile.CUSTOM
                                        TransferPerformance.save(this@MainActivity, performanceProfile, performanceTuning)
                                    })
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Use saved pair benchmark profiles", fontWeight = FontWeight.Medium)
                                        Text("Apply the best saved configuration for this sender-receiver pair.", color = LobbyMuted, fontSize = 11.sp)
                                    }
                                    Switch(checked = performanceTuning.automaticBenchmark, onCheckedChange = {
                                        performanceTuning = performanceTuning.copy(automaticBenchmark = it)
                                        if (performanceProfile != TransferProfile.CUSTOM) performanceProfile = TransferProfile.CUSTOM
                                        TransferPerformance.save(this@MainActivity, performanceProfile, performanceTuning)
                                    })
                                }
                                HorizontalDivider(color = Color(0xFFE7ECE7))
                                Text("Benchmark a nearby receiver", fontWeight = FontWeight.SemiBold)
                                Text("The probe runs signed parallel HTTP uploads with generated test data and writes temporary samples into the receiver's app cache. It measures network plus receiver temporary-storage throughput, not sender source-disk speed, encryption overhead, final SAF copy speed or a guaranteed sustained rate. It uses a bounded test matrix to limit temporary storage use.", color = LobbyMuted, fontSize = 11.sp)
                                if (benchmarkDevice == null && nearbyDevices.isNotEmpty()) benchmarkDevice = nearbyDevices.first()
                                if (nearbyDevices.isEmpty()) {
                                    Text("Turn on LoBBY on another device and keep this page open to discover it.", color = LobbyMuted, fontSize = 12.sp)
                                } else {
                                    nearbyDevices.forEach { peer ->
                                        FilterChip(
                                            selected = benchmarkDevice?.baseUrl == peer.baseUrl,
                                            onClick = { benchmarkDevice = peer },
                                            label = { Text(peer.name.removePrefix("LoBBY-")) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                val benchmarkPeerTrusted = benchmarkDevice?.deviceId?.let {
                                    LobbyIdentity.findTrustedPeer(this@MainActivity, it) != null
                                } == true
                                if (benchmarkDevice != null && !benchmarkPeerTrusted) {
                                    Text("Pair with this device from the Send screen before benchmarking.", color = LobbyMuted, fontSize = 11.sp)
                                }
                                Button(
                                    enabled = !benchmarkRunning && benchmarkDevice != null && benchmarkPeerTrusted,
                                    onClick = {
                                        val peer = benchmarkDevice ?: return@Button
                                        benchmarkRunning = true
                                        benchmarkPeer = null
                                        benchmarkStatus = "Preparing network and receiver-storage tests…"
                                        benchmarkOperation = NativeTransferClient(this@MainActivity).runBenchmark(
                                            peer,
                                            tuningInput = performanceTuning,
                                            onProgress = { message -> runOnUiThread { benchmarkStatus = message } },
                                            onFinished = { result, message -> runOnUiThread {
                                                benchmarkRunning = false
                                                benchmarkPeer = result
                                                benchmarkStatus = message
                                                if (result != null) {
                                                    val tuned = performanceTuning.copy(
                                                        maxParallelFiles = result.optimalParallelFiles,
                                                        connectionsPerFile = (result.optimalConnections / result.optimalParallelFiles).coerceAtLeast(1),
                                                        maxConnections = result.optimalConnections,
                                                        chunkSizeMiB = result.chunkSizeMiB
                                                    ).normalized()
                                                    if (performanceProfile == TransferProfile.MAXIMUM_THROUGHPUT) {
                                                        performanceTuning = tuned
                                                        TransferPerformance.save(this@MainActivity, performanceProfile, performanceTuning)
                                                    }
                                                }
                                            } }
                                        )
                                        activeBenchmarkOperation = benchmarkOperation
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(13.dp)
                                ) { Text(if (benchmarkRunning) "Benchmarking…" else "Run network + storage benchmark") }
                                if (benchmarkRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(benchmarkStatus, color = LobbyMuted, fontSize = 12.sp)
                                benchmarkPeer?.let { result ->
                                    Text("Best measured median: %.1f MB/s · %d connection(s) · %d parallel file(s)".format(result.bestMedianMBps, result.optimalConnections, result.optimalParallelFiles), color = LobbyGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                                TransferPerformance.loadBenchmarks(this@MainActivity).takeLast(5).asReversed().forEach { result ->
                                    Text("${result.peerId.take(12)} · ${result.direction} · %.1f MB/s · %d workers".format(result.bestMedianMBps, result.optimalConnections), color = LobbyMuted, fontSize = 11.sp)
                                }
                            }
                        }

                        Text("RECEIVING & DEVICE TRUST", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Surface(color = LobbySurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Receive without confirmation", fontWeight = FontWeight.SemiBold)
                                        Text("Automatically accept transfers from any device on your local network, paired or not.", color = LobbyMuted, fontSize = 11.sp)
                                    }
                                    Switch(checked = receiveWithoutConfirmation, onCheckedChange = {
                                        receiveWithoutConfirmation = it
                                        securityPrefs.edit().putBoolean("receive_without_confirmation", it).apply()
                                    })
                                }
                                if (receiveWithoutConfirmation) {
                                    Text("WARNING: Anyone on your local network may send files that will be accepted automatically. Turn this off when you want approval for each transfer.", color = Color(0xFFB45309), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Automatically accept trusted devices", fontWeight = FontWeight.SemiBold)
                                        Text("Automatically accept paired devices even when asking other senders each time.", color = LobbyMuted, fontSize = 11.sp)
                                    }
                                    Switch(checked = autoAcceptTrusted, onCheckedChange = {
                                        autoAcceptTrusted = it
                                        securityPrefs.edit().putBoolean("auto_accept_trusted", it).apply()
                                    })
                                }
                                Text(
                                    when {
                                        receiveWithoutConfirmation -> "All incoming devices are auto-accepted"
                                        autoAcceptTrusted -> "Trusted devices are auto-accepted; others require approval"
                                        else -> "Ask every time"
                                    },
                                    color = LobbyGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                )
                                val trustedPeers = LobbyIdentity.trustedPeers(this@MainActivity)
                                if (trustedPeers.isEmpty()) Text("No paired devices yet. Pairing is optional and can be started from the Send screen.", color = LobbyMuted, fontSize = 12.sp)
                                trustedPeers.forEach { peer ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(peer.name, color = LobbyInk, fontWeight = FontWeight.Medium)
                                            Text(peer.deviceId, color = LobbyMuted, fontSize = 10.sp)
                                        }
                                        TextButton(onClick = { renamePeer = peer; renameDeviceName = peer.name }) { Text("Rename") }
                                        TextButton(onClick = { LobbyIdentity.removeTrustedPeer(this@MainActivity, peer.deviceId); serverRevision++ }) { Text("Revoke") }
                                    }
                                }
                                if (trustedPeers.size > 1) {
                                    TextButton(onClick = { LobbyIdentity.removeAllTrustedPeers(this@MainActivity); serverRevision++ }) {
                                        Text("Revoke all trusted devices")
                                    }
                                }
                            }
                        }

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

                        if (page == "inbox") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { page = "home" }) { Text("← Home") }
                                Spacer(Modifier.weight(1f))
                                Text("RECEIVED", color = LobbyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            Text("Received & transfer history", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = LobbyInk)
                            Text("Completed files are saved in your configured shared folder.", color = LobbyMuted, fontSize = 12.sp)
                            if (historyEntries.isEmpty()) {
                                Surface(color = LobbySurface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text("No transfers yet. Accepted and completed transfers will appear here.", modifier = Modifier.padding(18.dp), color = LobbyMuted, fontSize = 13.sp)
                                }
                            } else {
                                historyEntries.forEach { entry ->
                                    Surface(color = LobbySurface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { selectedHistoryTransferId = entry.id }) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Text(entry.name, color = LobbyInk, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                            Text("${entry.sender} · ${formatFileSize(entry.size)}", color = LobbyMuted, fontSize = 12.sp)
                                            Text("${entry.status.uppercase()} · ${java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))}", color = if (entry.status == "completed") LobbyGreen else LobbyMuted, fontSize = 11.sp)
                                            if (entry.status == "completed" && entry.uri.isNotBlank()) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(onClick = { openHistoryEntry(entry, false) }, modifier = Modifier.weight(1f)) { Text("Open") }
                                                    OutlinedButton(onClick = { openHistoryEntry(entry, true) }, modifier = Modifier.weight(1f)) { Text("Share") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
                            NavPillItem("Inbox ${historyEntries.count { it.status == "completed" }}", page == "inbox") { historyEntries = TransferHistoryStore.load(this@MainActivity); page = "inbox" }
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
                    if (pairingCode != null) {
                        AlertDialog(
                            onDismissRequest = { pairingOperation?.cancel(); pairingCode = null },
                            title = { Text("Verify device pairing") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("${pairingDeviceName} is requesting a trusted pairing.")
                                    Text(pairingCode!!, color = LobbyGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                                    Text("The receiving device must show exactly the same six-digit code. Accept on that device only after comparing the codes.", color = LobbyMuted, fontSize = 12.sp)
                                    Text(pairingStatus, color = LobbyMuted, fontSize = 12.sp)
                                }
                            },
                            confirmButton = { TextButton(onClick = { pairingCode = null }) { Text("Close") } },
                            dismissButton = { TextButton(onClick = { pairingOperation?.cancel(); pairingCode = null; pairingStatus = "Pairing cancelled" }) { Text("Cancel pairing") } }
                        )
                    }
                    selectedHistoryTransferId?.let { transferId ->
                        val transferEntries = historyEntries.filter { it.id == transferId }
                        if (transferEntries.isNotEmpty()) {
                            AlertDialog(
                                onDismissRequest = { selectedHistoryTransferId = null },
                                title = { Text("Transfer details") },
                                text = {
                                    Column(
                                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text("${transferEntries.first().sender} · ${formatFileSize(transferEntries.sumOf { it.size.coerceAtLeast(0L) })} · ${transferEntries.size} file(s)", color = LobbyMuted, fontSize = 12.sp)
                                        transferEntries.forEach { entry ->
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(entry.name, color = LobbyInk, fontWeight = FontWeight.SemiBold)
                                                Text("${entry.status.uppercase()} · ${formatFileSize(entry.size)}", color = LobbyMuted, fontSize = 11.sp)
                                                if (entry.status == "completed" && entry.uri.isNotBlank()) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        OutlinedButton(onClick = { openHistoryEntry(entry, false) }, modifier = Modifier.weight(1f)) { Text("Open") }
                                                        OutlinedButton(onClick = { openHistoryEntry(entry, true) }, modifier = Modifier.weight(1f)) { Text("Share") }
                                                    }
                                                }
                                            }
                                            HorizontalDivider(color = Color(0xFFE7ECE7))
                                        }
                                    }
                                },
                                confirmButton = { TextButton(onClick = { selectedHistoryTransferId = null }) { Text("Close") } }
                            )
                        }
                    }
                    renamePeer?.let { peer ->
                        AlertDialog(
                            onDismissRequest = { renamePeer = null },
                            title = { Text("Rename trusted device") },
                            text = {
                                OutlinedTextField(
                                    value = renameDeviceName,
                                    onValueChange = { renameDeviceName = it.take(48) },
                                    singleLine = true,
                                    label = { Text("Device name") }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    LobbyIdentity.renameTrustedPeer(this@MainActivity, peer.deviceId, renameDeviceName)
                                    renamePeer = null
                                    serverRevision++
                                }) { Text("Save") }
                            },
                            dismissButton = { TextButton(onClick = { renamePeer = null }) { Text("Cancel") } }
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

    /** Returns false below Android 11 without invoking an API unavailable on those versions. */
    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    private fun openHistoryEntry(entry: TransferHistoryEntry, share: Boolean) {
        try {
            val storedUri = Uri.parse(entry.uri)
            val contentUri = if (storedUri.scheme == "file") {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    File(storedUri.path ?: throw IOException("File path is unavailable"))
                )
            } else storedUri
            val action = if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW
            val intent = Intent(action).apply {
                if (share) {
                    type = entry.mimeType.ifBlank { "application/octet-stream" }
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                } else {
                    setDataAndType(contentUri, entry.mimeType.ifBlank { "application/octet-stream" })
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(if (share) Intent.createChooser(intent, "Share ${entry.name}") else intent)
        } catch (error: Exception) {
            android.widget.Toast.makeText(this, "Could not open file: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        incomingShareHandler = null
        activeTransferOperation?.cancel()
        activePairingOperation?.cancel()
        activeBenchmarkOperation?.cancel()
        discovery.stop()
        super.onDestroy()
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


private fun formatSpeed(bytesPerSecond: Double): String {
    if (bytesPerSecond <= 0.0) return "Starting…"
    val mb = bytesPerSecond / 1_000_000.0
    return if (mb >= 1.0) "%.1f MB/s".format(mb) else "%.0f KB/s".format(bytesPerSecond / 1024.0)
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remaining = safe % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${remaining}s"
        else -> "${remaining}s"
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
                    Text("Android builds provide local HTTP and mDNS discovery, secure paired native transfers with receiver approval, parallel chunk scheduling and a browser compatibility interface. The browser upload routes remain a separate legacy path without native pairing or encryption.", color = LobbyMuted)
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
