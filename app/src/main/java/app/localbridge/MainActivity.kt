package app.localbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private var server: LocalBridgeServer? = null
    private var indexer: StorageIndex? = null
    private var indexedRootKey: String? = null
    private val allFilesPermission = mutableStateOf(false)
    private val indexCount = mutableLongStateOf(0L)
    private val indexing = mutableStateOf(false)
    private val lastIndexed = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshStorageIndex()
        val prefs = getSharedPreferences("localbridge", MODE_PRIVATE)

        setContent {
            MaterialTheme {
                var running by remember { mutableStateOf(false) }
                var url by remember { mutableStateOf<String?>(null) }
                var status by remember { mutableStateOf("Ready") }
                var shareUri by remember { mutableStateOf(prefs.getString("shared_uri", null)?.let(Uri::parse)) }
                var siteUri by remember { mutableStateOf(prefs.getString("website_uri", null)?.let(Uri::parse)) }
                var sharePath by remember { mutableStateOf(prefs.getString("share_path", "/files") ?: "/files") }
                val hasAllFiles by allFilesPermission
                val count by indexCount
                val isIndexing by indexing
                val indexedAt by lastIndexed

                val sharedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        } catch (_: Exception) { }
                        shareUri = uri
                        prefs.edit().putString("shared_uri", uri.toString()).apply()
                        refreshStorageIndex(uri)
                        status = "Shared folder updated. Indexing started."
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

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("LoBBY", style = MaterialTheme.typography.headlineLarge)
                    Text("Local network file sharing", style = MaterialTheme.typography.bodyLarge)

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("SERVER", style = MaterialTheme.typography.labelLarge)
                            Text(if (running) "Running" else "Stopped", style = MaterialTheme.typography.headlineSmall)
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (!running) {
                                        try {
                                            if (shareUri == null && !hasAllFilesAccess()) {
                                                status = "Grant All Files Access or choose a shared folder first."
                                                return@Button
                                            }
                                            val activeIndex = indexer
                                            if (activeIndex == null) {
                                                status = "Storage index is not ready. Select storage and try again."
                                                return@Button
                                            }
                                            prefs.edit().putString("share_path", sharePath).apply()
                                            server = LocalBridgeServer(this@MainActivity, shareUri, siteUri, sharePath, activeIndex).also { it.start() }
                                            url = lanIp()?.let { "http://$it:8765" }
                                            running = true
                                            status = "Server is running. Keep LoBBY open during transfers."
                                        } catch (e: Exception) {
                                            status = "Start failed: ${e.message}"
                                        }
                                    } else {
                                        server?.stop(); server = null; running = false; url = null
                                        status = "Server stopped"
                                    }
                                }
                            ) { Text(if (running) "Stop server" else "Start server") }
                            url?.let {
                                Text("Connection address", style = MaterialTheme.typography.titleSmall)
                                SelectionContainer { Text(it, style = MaterialTheme.typography.titleMedium) }
                                Text("Open this address on the other device connected to the same Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("STORAGE", style = MaterialTheme.typography.labelLarge)
                            Text(if (hasAllFiles) "All Files Access granted" else "All Files Access not granted", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (shareUri != null) "Using selected folder: ${DocumentFile.fromTreeUri(this@MainActivity, shareUri!!)?.name ?: "Custom folder"}"
                                else if (hasAllFiles) "Sharing the shared internal storage root"
                                else "Choose a folder with SAF, or grant All Files Access to share shared internal storage."
                            )
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !running,
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                                        try { startActivity(intent) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                                    } else {
                                        status = "Use the folder picker on this Android version."
                                    }
                                }
                            ) { Text("Manage All Files Access") }
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !running, onClick = { sharedPicker.launch(shareUri) }) {
                                Text("Choose a specific shared folder")
                            }
                            HorizontalDivider()
                            Text("Index", style = MaterialTheme.typography.titleMedium)
                            Text(if (isIndexing) "Scanning files…" else "${count} indexed files and folders")
                            if (indexedAt != null && !isIndexing) Text("Last scan completed", style = MaterialTheme.typography.bodySmall)
                            if (!isIndexing && (hasAllFiles || shareUri != null)) {
                                TextButton(onClick = { indexer?.refresh(""); status = "Index refresh queued" }) { Text("Refresh index") }
                            }
                        }
                    }

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("SERVER SETTINGS", style = MaterialTheme.typography.labelLarge)
                            Text("File share URL path", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = sharePath,
                                onValueChange = { sharePath = it },
                                enabled = !running,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("/files") }
                            )
                            Text("Example: /files or /my-share", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                            Text("Custom website", style = MaterialTheme.typography.titleMedium)
                            Text(siteUri?.lastPathSegment ?: "Built-in transfer page")
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !running, onClick = { websitePicker.launch(siteUri) }) {
                                Text("Choose website folder")
                            }
                            if (siteUri != null) Text("Place index.html in the selected folder. Its CSS, JavaScript and assets are served from the same folder.", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running,
                        onClick = {
                            siteUri = null; shareUri = null; sharePath = "/files"
                            prefs.edit().remove("website_uri").remove("shared_uri").putString("share_path", "/files").apply()
                            refreshStorageIndex(null)
                            status = "Defaults restored"
                        }
                    ) { Text("Restore defaults") }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Text("Both devices must use the same local Wi-Fi. Internet access is not required.", style = MaterialTheme.typography.bodySmall)
                    Text("HTTP only. No authentication or HTTPS yet. Use only on a trusted network.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorageIndex()
    }

    private fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun refreshStorageIndex(selectedUri: Uri? = getSharedPreferences("localbridge", MODE_PRIVATE).getString("shared_uri", null)?.let(Uri::parse)) {
        val granted = hasAllFilesAccess()
        allFilesPermission.value = granted
        val root: DocumentFile? = when {
            selectedUri != null -> DocumentFile.fromTreeUri(this, selectedUri)
            granted -> DocumentFile.fromFile(Environment.getExternalStorageDirectory())
            else -> null
        }
        val key = selectedUri?.toString() ?: if (granted) "all-shared-storage" else null
        if (key == indexedRootKey) return
        indexer?.close()
        indexer = null
        indexedRootKey = key
        indexCount.longValue = 0L
        lastIndexed.value = null
        if (root != null && key != null) {
            val fileRoot = if (selectedUri == null && granted) Environment.getExternalStorageDirectory() else null
            indexer = StorageIndex(this, root, fileRoot, key) { scanning, total, timestamp ->
                runOnUiThread {
                    indexing.value = scanning
                    indexCount.longValue = total
                    if (timestamp != null) lastIndexed.value = timestamp
                }
            }.also { it.start() }
        }
    }

    override fun onDestroy() {
        server?.stop(); server = null
        indexer?.close(); indexer = null
        super.onDestroy()
    }

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
