package com.octopus.octopipe.data

data class VideoItem(
    val id:String,
    val title:String,
    val author:String,
    val thumbnail:String,
    val durationSeconds:Long,
    val views:Long,
    val service:String,
    val watchUrl:String,
    val channelId:String?=null,
    val channelUrl:String?=null
)
data class ChannelItem(
    val id:String,
    val name:String,
    val thumbnail:String,
    val service:String,
    val url:String,
    val subscribers:Long=0
)
data class PlaylistItem(
    val id:String,
    val title:String,
    val author:String,
    val thumbnail:String,
    val videoCount:Int,
    val service:String,
    val url:String
)
data class CaptionTrack(val label:String,val languageCode:String,val url:String)
data class VideoVariant(val label:String,val url:String,val mimeType:String,val height:Int,val bitrate:Int=0)
data class SponsorSegment(val startSeconds:Double,val endSeconds:Double,val category:String)
data class StreamInfo(
    val video:VideoItem,
    val streamUrl:String,
    val mimeType:String,
    val captions:List<CaptionTrack>,
    val downloadUrl:String?=streamUrl,
    val downloadMimeType:String=mimeType,
    val audioDownloadUrl:String?=null,
    val audioMimeType:String="audio/mp4",
    val variants:List<VideoVariant> = emptyList()
)
enum class ServiceProvider(val title:String){YOUTUBE("YouTube"),PEERTUBE("PeerTube")}
