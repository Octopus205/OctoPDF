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
    val client=OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build()
    fun get(url:String):String {
        val request=Request.Builder().url(url).header("User-Agent","OctoPipe/0.3.0 Android").header("Accept","application/json").build()
        client.newCall(request).execute().use { r -> if(!r.isSuccessful) error("Provider HTTP ${r.code}"); return r.body?.string()?:error("Empty provider response") }
    }
    fun json(url:String)=JSONObject(get(url))
    fun array(url:String)=JSONArray(get(url))
}
private object YouTubeEngine:VideoEngine {
    override fun supports(service:String)=service==ServiceProvider.YOUTUBE.name
    private val fallback=listOf("https://inv.nadeko.net","https://inv.tux.pizza","https://invidious.nerdvpn.de","https://invidious.privacyredirect.com")
    @Volatile private var lastGoodHost:String?=null
    private fun <T> retry(block:(String)->T):T {
        val errors=mutableListOf<String>()
        val ordered=(listOfNotNull(lastGoodHost)+fallback).distinct().take(3)
        for(host in ordered){try{val result=block(host);lastGoodHost=host;return result}catch(e:Exception){errors.add("${host.removePrefix("https://")}: ${e.message?: "failed"}")}}
        error("Video service is taking too long or unavailable. Please retry. "+errors.take(2).joinToString(" | "))
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
            out.add(VideoItem(id,o.optString("title","Untitled"),o.optString("author","Unknown channel"),thumb,o.optLong("lengthSeconds"),o.optLong("viewCount"),ServiceProvider.YOUTUBE.name,"https://www.youtube.com/watch?v=$id",o.optString("authorId").takeIf{it.isNotBlank()},o.optString("authorUrl").takeIf{it.isNotBlank()}?.let{if(it.startsWith("http"))it else "https://www.youtube.com$it"}))
        }
        if(out.isEmpty())error("No video results from this instance");out
    }
    override fun resolve(video:VideoItem):StreamInfo = retry { host ->
        val o=ProviderHttp.json("$host/api/v1/videos/${video.id}")
        val formats=o.optJSONArray("formatStreams");val candidates=mutableListOf<VideoVariant>()
        if(formats!=null)for(i in 0 until formats.length()){
            val f=formats.optJSONObject(i)?:continue;val u=f.optString("url");val mime=f.optString("type").substringBefore(';')
            if(!u.startsWith("http")||!mime.startsWith("video/"))continue
            if(f.optString("container").let{it.isNotBlank()&&it!="mp4"})continue
            val h=Regex("(\\d+)p").find(f.optString("qualityLabel"))?.groupValues?.get(1)?.toIntOrNull()?:0
            candidates.add(VideoVariant(if(h>0)"$h p".replace(" ","") else "Auto",u,if(mime.isBlank())"video/mp4" else mime,h,f.optInt("bitrate")))
        }
        val variants=candidates.distinctBy{it.height}.sortedByDescending{it.height}
        val direct=variants.firstOrNull()
        val hls=o.optString("hlsUrl").takeIf{it.startsWith("http")}
        val play=direct?.url?:hls?:error("No compatible public stream found")
        val mime=direct?.mimeType?:"application/x-mpegURL"
        val caps=mutableListOf<CaptionTrack>();val ca=o.optJSONArray("captions")
        if(ca!=null)for(i in 0 until ca.length()){val c=ca.optJSONObject(i)?:continue;val u=c.optString("url");if(u.startsWith("http"))caps.add(CaptionTrack(c.optString("label",c.optString("languageCode")),c.optString("languageCode","und"),u))}
        val adaptive=o.optJSONArray("adaptiveFormats")
        var audioUrl:String?=null
        var audioMime="audio/mp4"
        if(adaptive!=null)for(i in 0 until adaptive.length()){
            val f=adaptive.optJSONObject(i)?:continue
            val type=f.optString("type").substringBefore(';')
            val u=f.optString("url")
            if(type.startsWith("audio/")&&u.startsWith("http")){
                if(audioUrl==null){audioUrl=u;audioMime=type}
                if(f.optLong("bitrate")>=128000){audioUrl=u;audioMime=type;break}
            }
        }
        StreamInfo(video,play,mime,caps,direct?.url,direct?.mimeType?:"video/mp4",audioUrl,audioMime,variants)
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
            val channel=o.optJSONObject("channel");out.add(VideoItem(id,o.optString("name","Untitled"),acc?.optString("displayName","PeerTube creator")?:"PeerTube creator",if(t.startsWith("http"))t else if(t.startsWith("/"))base+t else "",o.optLong("duration"),o.optLong("views"),ServiceProvider.PEERTUBE.name,o.optString("url","$base/videos/watch/$id"),channel?.optString("id")?.takeIf{it.isNotBlank()},channel?.optString("url")?.takeIf{it.isNotBlank()}))
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
    private data class Cached<T>(val value:T,val savedAt:Long)
    private val streamCache=java.util.concurrent.ConcurrentHashMap<String,Cached<StreamInfo>>()
    private val searchCache=java.util.concurrent.ConcurrentHashMap<String,Cached<List<VideoItem>>>()
    private fun engine(service:String)=engines.firstOrNull{it.supports(service)}?:error("Unsupported video service: $service")
    suspend fun search(provider:ServiceProvider,q:String):List<VideoItem> = withContext(Dispatchers.IO){
        require(q.isNotBlank()){"Enter a video title, channel, or keyword."}
        val key=provider.name+":"+q.trim().lowercase()
        searchCache[key]?.takeIf{System.currentTimeMillis()-it.savedAt<90_000}?.let{return@withContext it.value}
        engine(provider.name).search(q.trim()).also{searchCache[key]=Cached(it,System.currentTimeMillis())}
    }
    suspend fun resolve(v:VideoItem):StreamInfo = withContext(Dispatchers.IO){
        val key=v.service+":"+v.id
        streamCache[key]?.takeIf{System.currentTimeMillis()-it.savedAt<180_000}?.let{return@withContext it.value}
        engine(v.service).resolve(v).also{streamCache[key]=Cached(it,System.currentTimeMillis())}
    }
}
