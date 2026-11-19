package com.octopus.octopipe.data
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
object DownloadHelper {
 fun enqueue(context:Context,info:StreamInfo):Long {
  val manager=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
  val ext=if(info.mimeType.contains("mpegurl",true))".m3u8" else if(info.mimeType.contains("webm",true))".webm" else ".mp4"
  val name=info.video.title.replace(Regex("[^A-Za-z0-9._ -]"),"_").trim().take(65).ifBlank{"OctoPipe-video"}
  val req=DownloadManager.Request(Uri.parse(info.streamUrl)).setTitle(info.video.title).setDescription("Downloading with OctoPipe")
   .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(true).setAllowedOverRoaming(false)
   .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,name+"_"+System.currentTimeMillis()+ext)
  return manager.enqueue(req)
 }
}
