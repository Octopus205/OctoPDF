package com.octopus.octopipe.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object DownloadHelper {
    fun enqueue(context:Context,info:StreamInfo):Long {
        val url=info.downloadUrl?:error("A direct video file is not available for offline download.")
        return enqueueFile(context,url,info.downloadMimeType,info.video.title,"video")
    }
    fun enqueueAudio(context:Context,info:StreamInfo):Long {
        val url=info.audioDownloadUrl?:error("A separate audio stream is not available for this video.")
        return enqueueFile(context,url,info.audioMimeType,info.video.title,"audio")
    }
    private fun enqueueFile(context:Context,url:String,mimeType:String,title:String,kind:String):Long {
        require(url.startsWith("http://")||url.startsWith("https://")){"Invalid download URL."}
        val mime=mimeType.lowercase()
        require(!mime.contains("mpegurl")&&!url.substringBefore('?').endsWith(".m3u8",true)){"Adaptive playlists cannot be saved as a single offline file."}
        val ext=when {
            mime.contains("webm")->".webm"
            mime.contains("mp4")->if(kind=="audio")".m4a" else ".mp4"
            mime.contains("mpeg")->".mp3"
            mime.contains("ogg")->".ogg"
            mime.contains("opus")->".opus"
            mime.startsWith("audio/")->".m4a"
            else->if(kind=="audio")".m4a" else ".mp4"
        }
        val safe=title.replace(Regex("[^A-Za-z0-9._ -]"),"_").trim().take(65).ifBlank{"OctoPipe"}
        val request=DownloadManager.Request(Uri.parse(url))
            .setTitle("$title ($kind)")
            .setDescription("Downloading $kind with OctoPipe")
            .setMimeType(mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"$safe-$kind-${System.currentTimeMillis()}$ext")
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}
