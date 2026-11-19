package com.octopus.octopipe.data
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object VideoRepository {
 private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
 suspend fun search(provider:ServiceProvider,q:String):List<VideoItem> = withContext(Dispatchers.IO) {
  require(q.isNotBlank()) { "Enter something to search for." }
  if(provider==ServiceProvider.YOUTUBE) NewPipeEngine.search(q) else searchPeerTube(q)
 }
 suspend fun resolve(v:VideoItem):StreamInfo = withContext(Dispatchers.IO) {
  if(v.service==ServiceProvider.YOUTUBE.name) NewPipeEngine.resolve(v) else resolvePeerTube(v)
 }
 private fun searchPeerTube(q:String):List<VideoItem> {
  val base="https://peertube.tv"
  val url=Uri.parse("$base/api/v1/search/videos").buildUpon().appendQueryParameter("search",q).appendQueryParameter("start","0").appendQueryParameter("count","25").appendQueryParameter("sort","-match").build().toString()
  val a=JSONObject(get(url)).optJSONArray("data") ?: JSONArray()
  return (0 until a.length()).mapNotNull { i -> val o=a.optJSONObject(i) ?: return@mapNotNull null
   val id=o.optString("uuid"); if(id.isBlank()) return@mapNotNull null
   val account=o.optJSONObject("account"); val thumb=o.optString("thumbnailPath")
   VideoItem(id,o.optString("name","Untitled"),account?.optString("displayName","PeerTube creator") ?: "PeerTube creator",if(thumb.startsWith("http")) thumb else "$base$thumb",o.optLong("duration"),o.optLong("views"),ServiceProvider.PEERTUBE.name,o.optString("url","$base/videos/watch/$id"))
  }
 }
 private fun resolvePeerTube(v:VideoItem):StreamInfo {
  val o=JSONObject(get("https://peertube.tv/api/v1/videos/"+v.id))
  val p=o.optJSONArray("streamingPlaylists") ?: JSONArray()
  for(i in 0 until p.length()) { val u=p.optJSONObject(i)?.optString("playlistUrl").orEmpty(); if(u.isNotBlank()) return StreamInfo(v,u,"application/x-mpegURL",emptyList()) }
  val a=o.optJSONArray("files") ?: JSONArray()
  for(i in 0 until a.length()) { val f=a.optJSONObject(i) ?: continue; val u=f.optString("fileUrl"); if(u.isNotBlank()) return StreamInfo(v,u,f.optString("mimeType","video/mp4"),emptyList()) }
  error("This PeerTube video has no public playable file.")
 }
 private fun get(url:String):String {
  val req=Request.Builder().url(url).header("User-Agent","OctoPipe/0.2.0 Android").header("Accept","application/json").build()
  client.newCall(req).execute().use { r -> if(!r.isSuccessful) error("Provider returned HTTP "+r.code); return r.body?.string() ?: error("Empty provider response.") }
 }
}
