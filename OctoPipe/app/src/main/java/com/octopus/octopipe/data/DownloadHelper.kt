package com.octopus.octopipe.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object DownloadHelper {
    private val executor=Executors.newSingleThreadExecutor()
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).build()
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
        val fileName="$safe-$kind-${System.currentTimeMillis()}$ext"
        val tree=context.getSharedPreferences("octopipe_settings",Context.MODE_PRIVATE).getString("download_tree_uri",null)
        if(!tree.isNullOrBlank()){
            val root=DocumentFile.fromTreeUri(context,Uri.parse(tree))?:error("Selected download folder is no longer available.")
            val target=root.createFile(mime,fileName)?:error("Could not create a file in the selected folder.")
            executor.execute{
                try{
                    val request=Request.Builder().url(url).header("User-Agent","OctoPipe Android").build()
                    client.newCall(request).execute().use{response->
                        if(!response.isSuccessful)error("Download HTTP ${response.code}")
                        val input=response.body?.byteStream()?:error("Empty download response")
                        val output=context.contentResolver.openOutputStream(target.uri,"w")?:error("Cannot write to selected folder")
                        input.use{source->output.use{sink->source.copyTo(sink)}}
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post{Toast.makeText(context,"Saved to selected folder",Toast.LENGTH_LONG).show()}
                }catch(e:Exception){
                    target.delete()
                    android.os.Handler(android.os.Looper.getMainLooper()).post{Toast.makeText(context,"Download failed: ${e.message}",Toast.LENGTH_LONG).show()}
                }
            }
            return System.currentTimeMillis()
        }
        val request=DownloadManager.Request(Uri.parse(url))
            .setTitle("$title ($kind)")
            .setDescription("Downloading $kind with OctoPipe")
            .setMimeType(mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,fileName)
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}
