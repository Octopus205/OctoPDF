package com.octopus.octopipe.data
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
object DownloadHelper {
 fun enqueue(context:Context,info:StreamInfo):Long {
  val url=info.downloadUrl?:error("This video only has an adaptive streaming playlist; a complete offline file is not available.")
  require(url.startsWith("http://")||url.startsWith("https://")){"Invalid download URL."}
  val mime=info.downloadMimeType.lowercase()
  require(!mime.contains("mpegurl")&&!url.substringBefore('?').endsWith(".m3u8",true)){"This is a playlist, not a complete video file."}
  val ext=when{mime.contains("webm")->".webm";mime.contains("mp4")->".mp4";mime.startsWith("audio/")->".m4a";else->".mp4"}
  val name=info.video.title.replace(Regex("[^A-Za-z0-9._ -]"),"_").trim().take(65).ifBlank{"OctoPipe-video"}
  val request=DownloadManager.Request(Uri.parse(url)).setTitle(info.video.title).setDescription("Downloading with OctoPipe").setMimeType(mime).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(true).setAllowedOverRoaming(false).setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,name+"_"+System.currentTimeMillis()+ext)
  return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
 }
}
