package com.octopus.octopipe.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** First-party provider engine boundary. Provider payloads never escape as app-facing models. */
private interface VideoEngine {
    fun supports(service:String):Boolean
    fun search(query:String):List<VideoItem>
    fun resolve(video:VideoItem):StreamInfo
}
private object ProviderHttp {
    val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(14,TimeUnit.SECONDS).callTimeout(18,TimeUnit.SECONDS).build()
    fun get(url:String):String {
        val request=Request.Builder().url(url).header("User-Agent","OctoPipe/0.3.0 Android").header("Accept","application/json").build()
        client.newCall(request).execute().use { r -> if(!r.isSuccessful) error("Provider HTTP ${r.code}"); return r.body?.string()?:error("Empty provider response") }
    }
    fun json(url:String)=JSONObject(get(url))
    fun array(url:String)=JSONArray(get(url))
}
private object YouTubeEngine:VideoEngine {
    override fun supports(service:String)=service==ServiceProvider.YOUTUBE.name
    private val fallback=listOf("https://inv.nadeko.net","https://invidious.nerdvpn.de","https://invidious.privacyredirect.com","https://inv.tux.pizza")
    @Volatile private var hosts:List<String> = emptyList()
    private fun hosts():List<String>{
        if(hosts.isNotEmpty()) return hosts
        val found=runCatching {
            val a=ProviderHttp.array("https://api.invidious.io/instances.json?sort_by=health")
            buildList { for(i in 0 until a.length()){val row=a.optJSONArray(i)?:continue;val host=row.optString(0).removePrefix("https://").trimEnd('/');val meta=row.optJSONObject(1)?:continue;if(meta.optBoolean("api",false)&&host.contains(".")){add("https://$host")};if(size>=8)break} }
        }.getOrDefault(emptyList())
        hosts=(found+fallback).distinct().take(10);return hosts
    }
    private fun <T> retry(block:(String)->T):T {
        val errors=mutableListOf<String>()
        for(host in hosts()){try{return block(host)}catch(e:Exception){errors.add("${host.removePrefix("https://")}: ${e.message?: "failed"}")}}
        hosts=emptyList()
        error("YouTube public search providers are unavailable. Please retry. "+errors.take(2).joinToString(" | "))
    }
    override fun search(query:String):List<VideoItem> = retry { host ->
        val url=Uri.parse("$host/api/v1/search").buildUpon().appendQueryParameter("q",query).appendQueryParameter("type","video").build().toString()
        val a=ProviderHttp.array(url);val out=mutableListOf<VideoItem>()
        for(i in 0 until a.length()){
            val o=a.optJSONObject(i)?:continue
            if(o.optString("type","video")!="video")continue
            val id=o.optString("videoId");if(id.isBlank())continue
            val thumbs=o.optJSONArray("videoThumbnails")
            var thumb="https://i.ytimg.com/vi/$id/hqdefault.jpg"
            if(thumbs!=null)for(j in thumbs.length()-1 downTo 0){val u=thumbs.optJSONObject(j)?.optString("url").orEmpty();if(u.startsWith("http")){thumb=u;break}}
            out.add(VideoItem(id,o.optString("title","Untitled"),o.optString("author","Unknown channel"),thumb,o.optLong("lengthSeconds"),o.optLong("viewCount"),ServiceProvider.YOUTUBE.name,"https://www.youtube.com/watch?v=$id"))
        }
        if(out.isEmpty())error("No video results from this instance");out
    }
    override fun resolve(video:VideoItem):StreamInfo = retry { host ->
        val o=ProviderHttp.json("$host/api/v1/videos/${video.id}")
        val formats=o.optJSONArray("formatStreams");val candidates=mutableListOf<Triple<String,String,Int>>()
        if(formats!=null)for(i in 0 until formats.length()){
            val f=formats.optJSONObject(i)?:continue;val u=f.optString("url");val mime=f.optString("type").substringBefore(';')
            if(!u.startsWith("http")||!mime.startsWith("video/"))continue
            if(f.optString("container").let{it.isNotBlank()&&it!="mp4"})continue
            val h=Regex("(\\d+)p").find(f.optString("qualityLabel"))?.groupValues?.get(1)?.toIntOrNull()?:0
            candidates.add(Triple(u,if(mime.isBlank())"video/mp4" else mime,h))
        }
        val direct=candidates.maxByOrNull{it.third}
        val hls=o.optString("hlsUrl").takeIf{it.startsWith("http")}
        val play=direct?.first?:hls?:error("No compatible public stream found")
        val mime=direct?.second?:"application/x-mpegURL"
        val caps=mutableListOf<CaptionTrack>();val ca=o.optJSONArray("captions")
        if(ca!=null)for(i in 0 until ca.length()){val c=ca.optJSONObject(i)?:continue;val u=c.optString("url");if(u.startsWith("http"))caps.add(CaptionTrack(c.optString("label",c.optString("languageCode")),c.optString("languageCode","und"),u))}
        StreamInfo(video,play,mime,caps,direct?.first,direct?.second?:"video/mp4")
    }
}
private object PeerTubeEngine:VideoEngine {
    override fun supports(service:String)=service==ServiceProvider.PEERTUBE.name
    private const val base="https://peertube.tv"
    override fun search(query:String):List<VideoItem>{
        val url=Uri.parse("$base/api/v1/search/videos").buildUpon().appendQueryParameter("search",query).appendQueryParameter("start","0").appendQueryParameter("count","30").appendQueryParameter("sort","-match").build().toString()
        val a=ProviderHttp.json(url).optJSONArray("data")?:JSONArray();val out=mutableListOf<VideoItem>()
        for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;val id=o.optString("uuid").ifBlank{ o.optString("id") };if(id.isBlank())continue
            val t=o.optString("thumbnailPath");val acc=o.optJSONObject("account")
            out.add(VideoItem(id,o.optString("name","Untitled"),acc?.optString("displayName","PeerTube creator")?:"PeerTube creator",if(t.startsWith("http"))t else if(t.startsWith("/"))base+t else "",o.optLong("duration"),o.optLong("views"),ServiceProvider.PEERTUBE.name,o.optString("url","$base/videos/watch/$id")))
        };return out
    }
    override fun resolve(video:VideoItem):StreamInfo{
        val o=ProviderHttp.json("$base/api/v1/videos/${video.id}");val a=o.optJSONArray("files")?:JSONArray();val direct=mutableListOf<Triple<String,String,Int>>()
        for(i in 0 until a.length()){val f=a.optJSONObject(i)?:continue;val u=f.optString("fileUrl").ifBlank{f.optString("fileDownloadUrl")};if(u.startsWith("http"))direct.add(Triple(u,f.optString("mimeType","video/mp4"),f.optJSONObject("resolution")?.optInt("id")?:0))}
        val best=direct.maxByOrNull{it.third};val pls=o.optJSONArray("streamingPlaylists")?:JSONArray();var hls:String?=null
        for(i in 0 until pls.length()){val u=pls.optJSONObject(i)?.optString("playlistUrl");if(u?.startsWith("http")==true){hls=u;break}}
        val play=best?.first?:hls?:error("No public PeerTube stream found");val mime=best?.second?:"application/x-mpegURL"
        return StreamInfo(video,play,mime,emptyList(),best?.first,best?.second?:"video/mp4")
    }
}
object VideoRepository {
    private val engines=listOf(YouTubeEngine,PeerTubeEngine)
    private fun engine(service:String)=engines.firstOrNull{it.supports(service)}?:error("Unsupported video service: $service")
    suspend fun search(provider:ServiceProvider,q:String):List<VideoItem> = withContext(Dispatchers.IO){require(q.isNotBlank()){"Enter a video title, channel, or keyword."};engine(provider.name).search(q.trim())}
    suspend fun resolve(v:VideoItem):StreamInfo = withContext(Dispatchers.IO){engine(v.service).resolve(v)}
}
