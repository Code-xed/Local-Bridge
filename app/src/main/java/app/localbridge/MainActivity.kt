package app.localbridge
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
  super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  setContent {
   MaterialTheme {
    var running by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Ready") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
     Text("LocalBridge", style=MaterialTheme.typography.headlineLarge)
     Text("Android ↔ iPhone • Local network transfer")
     Text(if(running) "Sharing is ON" else "Sharing is OFF")
     Text(status)
     url?.let { SelectionContainer { Text("Open in Safari: $it") } }
     Button(onClick={
      if(!running) try {
       val dir=getExternalFilesDir("Shared") ?: filesDir
       server=LocalBridgeServer(this@MainActivity,dir).also { it.start() }
       url=lanIp()?.let { "http://$it:8765" }; running=true
       status="Keep this screen open during transfers."
      } catch(e:Exception) { status="Start failed: ${e.message}" }
      else { server?.stop(); server=null; running=false; url=null; status="Stopped" }
     }) { Text(if(running) "Stop sharing" else "Start sharing") }
     Text("1. Put both phones on the same Wi-Fi (internet not needed).")
     Text("2. Start sharing, then open the URL in iPhone Safari.")
     Text("3. Select multiple files to upload or download files from Android.")
     Text("Alpha: no hotspot pairing, HTTPS, authentication, resume, or background service yet.")
    }
   }
  }
 }
 override fun onDestroy(){ server?.stop(); server=null; super.onDestroy() }
 private fun lanIp(): String? {
  return try {
   val nets = NetworkInterface.getNetworkInterfaces()
   while (nets.hasMoreElements()) {
    val network = nets.nextElement()
    if (!network.isUp || network.isLoopback) continue
    val addresses = network.inetAddresses
    while (addresses.hasMoreElements()) {
     val address = addresses.nextElement()
     if (address is java.net.Inet4Address &&
      !address.isLoopbackAddress &&
      !address.hostAddress.orEmpty().startsWith("169.254.")) {
      return address.hostAddress
     }
    }
   }
   null
  } catch (_: Exception) {
   null
  }
 }
}
