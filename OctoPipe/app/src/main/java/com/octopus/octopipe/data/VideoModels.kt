package com.octopus.octopipe.data

data class VideoItem(val id:String,val title:String,val author:String,val thumbnail:String,val durationSeconds:Long,val views:Long,val service:String,val watchUrl:String)
data class CaptionTrack(val label:String,val languageCode:String,val url:String)
data class SponsorSegment(val startSeconds:Double,val endSeconds:Double,val category:String)
data class StreamInfo(val video:VideoItem,val streamUrl:String,val mimeType:String,val captions:List<CaptionTrack>,val downloadUrl:String?=streamUrl,val downloadMimeType:String=mimeType)
enum class ServiceProvider(val title:String){YOUTUBE("YouTube"),PEERTUBE("PeerTube")}
