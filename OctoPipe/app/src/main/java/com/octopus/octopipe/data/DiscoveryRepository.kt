package com.octopus.octopipe.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Discovery endpoints are kept separate from playback so UI features can evolve independently. */
object DiscoveryRepository {
    private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(14,TimeUnit.SECONDS).build()
    private val youtubeHosts=listOf("https://inv.nadeko.net","https://invidious.nerdvpn.de","https://invidious.privacyredirect.com")
    private const val peerTube="https://peertube.tv"
    private fun json(url:String):JSONObject {
        val req=Request.Builder().url(url).header("User-Agent","OctoPipe Android").build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");return JSONObject(r.body?.string()?: "{}")}
    }
    private fun array(url:String)=JSONArray(json(url).toString())
    private fun youtube(block:(String)->List<ChannelItem>):List<ChannelItem>{
        var failure:Exception?=null
        for(host in youtubeHosts)try{return block(host)}catch(e:Exception){failure=e}
        throw failure?:IllegalStateException("YouTube discovery unavailable")
    }
    suspend fun searchChannels(service:ServiceProvider,q:String):List<ChannelItem> = withContext(Dispatchers.IO){
        if(service==ServiceProvider.YOUTUBE) youtube { host ->
            val url=Uri.parse(host+"/api/v1/search").buildUpon().appendQueryParameter("q",q).appendQueryParameter("type","channel").build().toString()
            val a=JSONArray(getArray(url))
            (0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null;if(o.optString("type")!="channel")return@mapNotNull null;val id=o.optString("authorId");if(id.isBlank())return@mapNotNull null;ChannelItem(id,o.optString("author","Channel"),o.optJSONArray("authorThumbnails")?.optJSONObject(0)?.optString("url").orEmpty(),service.name,"https://www.youtube.com/channel/$id")}
        } else {
            val url=Uri.parse(peerTube+"/api/v1/search/video-channels").buildUpon().appendQueryParameter("search",q).appendQueryParameter("count","20").build().toString()
            val a=json(url).optJSONArray("data")?:JSONArray()
            (0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null;val id=o.optString("id");if(id.isBlank())return@mapNotNull null;ChannelItem(id,o.optString("displayName","Channel"),o.optJSONArray("avatars")?.optJSONObject(0)?.optString("fileUrl").orEmpty(),service.name,o.optString("url"))}
        }
    }
    suspend fun channelVideos(ch:ChannelItem):List<VideoItem> = withContext(Dispatchers.IO){
        if(ch.service==ServiceProvider.YOUTUBE.name){
            var failure:Exception?=null
            for(host in youtubeHosts)try{
                val url=Uri.parse("$host/api/v1/channels/${ch.id}/videos").buildUpon().appendQueryParameter("sort_by","newest").build().toString()
                val a=JSONArray(getArray(url))
                return@withContext (0 until a.length()).mapNotNull{i->val v=a.optJSONObject(i)?:return@mapNotNull null;val id=v.optString("videoId");if(id.isBlank())return@mapNotNull null;VideoItem(id,v.optString("title","Untitled"),ch.name,"https://i.ytimg.com/vi/$id/hqdefault.jpg",v.optLong("lengthSeconds"),v.optLong("viewCount"),ch.service,"https://www.youtube.com/watch?v=$id",ch.id,ch.url)}
            }catch(e:Exception){failure=e}
            throw failure?:IllegalStateException("Could not load channel videos")
        }else{
            val url=Uri.parse("$peerTube/api/v1/video-channels/${ch.id}/videos").buildUpon().appendQueryParameter("count","30").appendQueryParameter("sort","-publishedAt").build().toString()
            val a=json(url).optJSONArray("data")?:JSONArray()
            (0 until a.length()).mapNotNull{i->val v=a.optJSONObject(i)?:return@mapNotNull null;val id=v.optString("uuid");if(id.isBlank())return@mapNotNull null;VideoItem(id,v.optString("name","Untitled"),ch.name,v.optString("thumbnailPath"),v.optLong("duration"),v.optLong("views"),ch.service,v.optString("url"),ch.id,ch.url)}
        }
    }
    suspend fun searchPlaylists(service:ServiceProvider,q:String):List<PlaylistItem> = withContext(Dispatchers.IO){
        if(service==ServiceProvider.YOUTUBE){
            var error:Exception?=null
            for(host in youtubeHosts)try{
                val url=Uri.parse(host+"/api/v1/search").buildUpon().appendQueryParameter("q",q).appendQueryParameter("type","playlist").build().toString()
                val a=JSONArray(getArray(url))
                return@withContext (0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null;if(o.optString("type")!="playlist")return@mapNotNull null;val id=o.optString("playlistId");if(id.isBlank())return@mapNotNull null;PlaylistItem(id,o.optString("title","Playlist"),o.optString("author","Creator"),"",o.optInt("videoCount"),service.name,"https://www.youtube.com/playlist?list=$id")}
            }catch(e:Exception){error=e}
            throw error?:IllegalStateException("Playlist search unavailable")
        }else{
            val url=Uri.parse(peerTube+"/api/v1/search/video-playlists").buildUpon().appendQueryParameter("search",q).appendQueryParameter("count","20").build().toString()
            val a=json(url).optJSONArray("data")?:JSONArray()
            (0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null;val id=o.optString("uuid");if(id.isBlank())return@mapNotNull null;PlaylistItem(id,o.optString("displayName","Playlist"),o.optJSONObject("owner")?.optString("displayName","Creator")?:"Creator",o.optString("thumbnailPath"),o.optInt("videoLength"),service.name,o.optString("url"))}
        }
    }
    suspend fun playlistVideos(p:PlaylistItem):List<VideoItem> = withContext(Dispatchers.IO){
        if(p.service==ServiceProvider.YOUTUBE.name){
            var error:Exception?=null
            for(host in youtubeHosts)try{
                val o=json("$host/api/v1/playlists/${p.id}");val a=o.optJSONArray("videos")?:JSONArray()
                return@withContext (0 until a.length()).mapNotNull{i->val v=a.optJSONObject(i)?:return@mapNotNull null;val id=v.optString("videoId");if(id.isBlank())return@mapNotNull null;VideoItem(id,v.optString("title","Untitled"),v.optString("author",p.author),"https://i.ytimg.com/vi/$id/hqdefault.jpg",v.optLong("lengthSeconds"),v.optLong("viewCount"),p.service,"https://www.youtube.com/watch?v=$id")}
            }catch(e:Exception){error=e}
            throw error?:IllegalStateException("Playlist could not be loaded")
        }else{
            val o=json("$peerTube/api/v1/video-playlists/${p.id}/videos");val a=o.optJSONArray("data")?:JSONArray()
            (0 until a.length()).mapNotNull{i->val v=a.optJSONObject(i)?.optJSONObject("video")?:return@mapNotNull null;val id=v.optString("uuid");if(id.isBlank())return@mapNotNull null;VideoItem(id,v.optString("name","Untitled"),v.optJSONObject("channel")?.optString("displayName",p.author)?:p.author,v.optString("thumbnailPath"),v.optLong("duration"),v.optLong("views"),p.service,v.optString("url"))}
        }
    }
    private fun getArray(url:String):String {val req=Request.Builder().url(url).header("User-Agent","OctoPipe Android").build();client.newCall(req).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");return r.body?.string()?: "[]"}}
}