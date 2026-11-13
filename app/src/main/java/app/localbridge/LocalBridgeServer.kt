package app.localbridge
import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalBridgeServer(ctx:Context, private val root:File):NanoHTTPD("0.0.0.0",8765) {
 private val html=ctx.assets.open("index.html").bufferedReader().use{it.readText()}
 init { root.mkdirs() }
 override fun serve(s:IHTTPSession):Response {
  return try {
   val p=s.uri.substringBefore('?')
   val r=when {
    s.method==Method.GET && (p=="/"||p=="/index.html") -> newFixedLengthResponse(Response.Status.OK,"text/html; charset=utf-8",html)
    s.method==Method.GET && p=="/api/files" -> listing()
    s.method==Method.OPTIONS -> newFixedLengthResponse(Response.Status.NO_CONTENT,"text/plain","")
    s.method==Method.PUT && p.startsWith("/files/") -> put(s,p)
    s.method==Method.GET && p.startsWith("/files/") -> get(p)
    s.method==Method.HEAD && p.startsWith("/files/") -> head(p)
    s.method==Method.DELETE && p.startsWith("/files/") -> delete(p)
    else -> newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","Not found")
   }
   r.addHeader("Access-Control-Allow-Origin","*")
   r.addHeader("Access-Control-Allow-Methods","GET, PUT, HEAD, DELETE, OPTIONS")
   r.addHeader("Access-Control-Allow-Headers","Content-Type, Depth")
   r.addHeader("DAV","1"); r
  } catch(e:Exception){newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain",e.message?: "Error")}
 }
 private fun name(p:String)=java.net.URLDecoder.decode(p.removePrefix("/files/"),"UTF-8").also {
  require(it.isNotBlank() && it!="." && it!=".." && !it.contains("/") && !it.contains("\\"))
 }
 private fun put(s:IHTTPSession,p:String):Response {
  val n=name(p); val parsed=HashMap<String,String>(); s.parseBody(parsed)
  val body=parsed["content"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST,"text/plain","Missing body")
  val dst=File(root,n); val tmp=File(root,".$n.part")
  File(body).inputStream().use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output,1024*1024) } }
  if(dst.exists()) dst.delete()
  if(!tmp.renameTo(dst)){tmp.copyTo(dst,true);tmp.delete()}
  return newFixedLengthResponse(Response.Status.CREATED,"application/json","{\\"ok\\":true}")
 }
 private fun get(p:String):Response { val f=File(root,name(p)); if(!f.isFile)return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","")
  return newChunkedResponse(Response.Status.OK,"application/octet-stream",FileInputStream(f))
 }
 private fun head(p:String):Response { val f=File(root,name(p)); if(!f.isFile)return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","")
  return newFixedLengthResponse(Response.Status.OK,"application/octet-stream","").apply{addHeader("Content-Length",f.length().toString())}
 }
 private fun delete(p:String):Response { val f=File(root,name(p)); return if(f.delete())newFixedLengthResponse(Response.Status.NO_CONTENT,"text/plain","") else newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","") }
 private fun listing():Response {
  val items=root.listFiles().orEmpty().filter{it.isFile&&!it.name.endsWith(".part")}
  val array=org.json.JSONArray()
  items.forEach { array.put(org.json.JSONObject().put("name",it.name).put("size",it.length())) }
  return newFixedLengthResponse(Response.Status.OK,"application/json",array.toString())
 }

}
