package com.octopus.octopipe

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.octopus.octopipe.data.*
import com.octopus.octopipe.playback.OctoPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg=Color(0xFF090B10)
private val Panel=Color(0xFF141923)
private val Raised=Color(0xFF1B2330)
private val Blue=Color(0xFF55A7FF)
private val White=Color(0xFFF3F6FA)
private val Muted=Color(0xFF9AA7B8)

class MainActivity:ComponentActivity(){
 private var future:ListenableFuture<MediaController>?=null
 private var controller by mutableStateOf<MediaController?>(null)
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val token=SessionToken(this,ComponentName(this,OctoPlaybackService::class.java))
  val f=MediaController.Builder(this,token).buildAsync(); future=f
  f.addListener({try{controller=f.get()}catch(_:Exception){}},ContextCompat.getMainExecutor(this))
  setContent{OctoPipeApp(controller){openPip()}}
 }
 private fun openPip(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())}
 override fun onUserLeaveHint(){super.onUserLeaveHint();if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O&&controller?.isPlaying==true)openPip()}
 override fun onDestroy(){future?.let{MediaController.releaseFuture(it)};future=null;super.onDestroy()}
}
private enum class Tab(val label:String){HOME("Home"),EXPLORE("Explore"),DISCOVER("Discover"),SUBSCRIPTIONS("Subscriptions"),LIBRARY("Library"),SETTINGS("Settings"),PLAYER("Player")}

@Composable private fun OctoPipeApp(player:Player?,onPip:()->Unit){
 val context=androidx.compose.ui.platform.LocalContext.current
 val store=remember{LibraryStore(context)}
 val scope=rememberCoroutineScope()
 var tab by remember{mutableStateOf(Tab.HOME)}
 var provider by remember{mutableStateOf(ServiceProvider.YOUTUBE)}
 var query by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)}
 val results=remember{mutableStateListOf<VideoItem>()}
 val favorites=remember{mutableStateListOf<VideoItem>().apply{addAll(store.favorites())}}
 val history=remember{mutableStateListOf<VideoItem>().apply{addAll(store.history())}}
 val subscriptions=remember{mutableStateListOf<ChannelItem>().apply{addAll(store.subscriptions())}}
 val channelResults=remember{mutableStateListOf<ChannelItem>()}
 val playlistResults=remember{mutableStateListOf<PlaylistItem>()}
 val playlistVideos=remember{mutableStateListOf<VideoItem>()}
 var discoverMode by remember{mutableStateOf("Channels")}
 var activePlaylist by remember{mutableStateOf<PlaylistItem?>(null)}
 var current by remember{mutableStateOf<StreamInfo?>(null)}
 var segments by remember{mutableStateOf<List<SponsorSegment>>(emptyList())}
 var captionMenu by remember{mutableStateOf(false)}
 fun search(){if(query.isBlank())return;scope.launch{loading=true;error=null;try{results.clear();results.addAll(VideoRepository.search(provider,query));tab=Tab.EXPLORE}catch(e:Exception){error=e.message?:"Search failed."}finally{loading=false}}}
 fun searchDiscovery(){if(query.isBlank())return;scope.launch{loading=true;error=null;try{
  if(discoverMode=="Channels"){channelResults.clear();channelResults.addAll(DiscoveryRepository.searchChannels(provider,query))}
  else{playlistResults.clear();playlistResults.addAll(DiscoveryRepository.searchPlaylists(provider,query))}
 }catch(e:Exception){error=e.message?:"Discovery failed"}finally{loading=false}}}
 fun openPlaylist(p:PlaylistItem){scope.launch{loading=true;error=null;activePlaylist=p;try{playlistVideos.clear();playlistVideos.addAll(DiscoveryRepository.playlistVideos(p))}catch(e:Exception){error=e.message?:"Could not load playlist"}finally{loading=false}}}
 fun downloadPlaylist(){val list=playlistVideos.toList();scope.launch{loading=true;var count=0;for(v in list){try{val info=VideoRepository.resolve(v);if(info.downloadUrl!=null){DownloadHelper.enqueue(context,info);count++}}catch(_:Exception){}};error="Queued "+count+" of "+list.size+" videos";loading=false}}
 fun play(v:VideoItem){scope.launch{error=null;tab=Tab.PLAYER;try{
  val info=VideoRepository.resolve(v);current=info;store.addHistory(v)
  history.removeAll{it.id==v.id&&it.service==v.service};history.add(0,v)
  segments=if(v.service==ServiceProvider.YOUTUBE.name)SponsorBlockRepository.segments(v.id)else emptyList()
  val subtitles=info.captions.map{c->MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(c.url)).setMimeType(MimeTypes.TEXT_VTT).setLanguage(c.languageCode).build()}
  val item=MediaItem.Builder().setUri(info.streamUrl).setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).setArtist(v.author).build()).setSubtitleConfigurations(subtitles).build()
  player?.setMediaItem(item);player?.prepare();player?.play()
 }catch(e:Exception){error=e.message?:"Unable to play this video."}}}
 LaunchedEffect(player,segments,current){val p=player?:return@LaunchedEffect;val skipped=mutableSetOf<Int>();while(true){if(p.isPlaying){val pos=p.currentPosition/1000.0;segments.forEachIndexed{i,s->if(pos>=s.startSeconds&&pos<s.endSeconds&&skipped.add(i))p.seekTo((s.endSeconds*1000).toLong())};if(pos<2)skipped.clear()};delay(450)}}
 MaterialTheme{
  Surface(Modifier.fillMaxSize(),color=Bg,contentColor=White){
   BoxWithConstraints(Modifier.fillMaxSize()){
    val wide=maxWidth>=600.dp
    Row(Modifier.fillMaxSize()){
     if(wide)NavigationRail(containerColor=Bg,header={AppMark(Modifier.padding(vertical=18.dp))}){
      Tab.entries.take(4).forEach{t->NavigationRailItem(selected=tab==t,onClick={tab=t},icon={TabIcon(t)},label={Text(t.label)})}
     }
     Column(Modifier.weight(1f).fillMaxSize()){
      Header()
      Box(Modifier.weight(1f).fillMaxWidth()){
       when(tab){
        Tab.HOME->SearchPage(query,{query=it},provider,{provider=it},{search()},results,loading,error,::play,true)
        Tab.EXPLORE->SearchPage(query,{query=it},provider,{provider=it},{search()},results,loading,error,::play,false)
        Tab.DISCOVER->DiscoverPage(query,{query=it},provider,{provider=it},discoverMode,{discoverMode=it;channelResults.clear();playlistResults.clear()},{searchDiscovery()},channelResults,playlistResults,playlistVideos,activePlaylist,loading,error,{ch->
         val added=store.toggleSubscription(ch);subscriptions.removeAll{it.id==ch.id&&it.service==ch.service};if(added)subscriptions.add(0,ch)
        },::openPlaylist,::downloadPlaylist,::play)
        Tab.SUBSCRIPTIONS->SubscriptionsPage(subscriptions,{ch->
         store.toggleSubscription(ch);subscriptions.removeAll{it.id==ch.id&&it.service==ch.service}
        })
        Tab.LIBRARY->LibraryPage(favorites,history,::play)
        Tab.SETTINGS->SettingsPage(provider,{provider=it})
        Tab.PLAYER->PlayerPage(player,current,segments,error,{captionMenu=it},captionMenu,{current?.let{DownloadHelper.enqueue(context,it)}},onPip){v->
         val added=store.toggle(v);favorites.removeAll{it.id==v.id&&it.service==v.service};if(added)favorites.add(0,v)
        }
       }
      }
      if(!wide)NavigationBar(containerColor=Bg){
       Tab.entries.take(4).forEach{t->NavigationBarItem(selected=tab==t,onClick={tab=t},icon={TabIcon(t)},label={Text(t.label)})}
       if(current!=null)NavigationBarItem(selected=tab==Tab.PLAYER,onClick={tab=Tab.PLAYER},icon={Icon(Icons.Outlined.PlayArrow,null)},label={Text("Player")})
      }
     }
    }
   }
  }
 }
}
@Composable private fun Header(){
 Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically){
  AppMark();Spacer(Modifier.width(10.dp));Column{Text("OctoPipe",fontSize=21.sp,fontWeight=FontWeight.Bold,color=White);Text("Your video space",fontSize=11.sp,color=Muted)}
  Spacer(Modifier.weight(1f));Text("0.2.0",fontSize=11.sp,color=Muted)
 }
}
@Composable private fun AppMark(modifier:Modifier=Modifier){AsyncImage(model=com.octopus.octopipe.R.drawable.octopipe_icon,contentDescription="OctoPipe octopus icon",modifier=modifier.size(40.dp))}
@Composable private fun TabIcon(tab:Tab){val icon=when(tab){Tab.HOME->Icons.Outlined.Home;Tab.EXPLORE->Icons.Outlined.Explore;Tab.LIBRARY->Icons.Outlined.Bookmarks;else->Icons.Outlined.Settings};Icon(icon,contentDescription=tab.label)}

@Composable private fun SearchPage(query:String,onQuery:(String)->Unit,provider:ServiceProvider,onProvider:(ServiceProvider)->Unit,onSearch:()->Unit,results:List<VideoItem>,loading:Boolean,error:String?,onPlay:(VideoItem)->Unit,home:Boolean){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(13.dp),contentPadding=PaddingValues(top=8.dp,bottom=24.dp)){
  item{Text(if(home)"Discover" else "Explore",fontSize=28.sp,fontWeight=FontWeight.Bold,color=White);Text("Search public video services.",color=Muted,fontSize=13.sp)}
  item{
   Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
    Row(Modifier.fillMaxWidth().background(Raised,RoundedCornerShape(15.dp)).padding(horizontal=13.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
     Icon(Icons.Outlined.Search,null,tint=Muted);Spacer(Modifier.width(9.dp))
     BasicTextField(value=query,onValueChange=onQuery,modifier=Modifier.weight(1f),singleLine=true,cursorBrush=SolidColor(Blue),textStyle=MaterialTheme.typography.bodyLarge.copy(color=White),decorationBox={inner->if(query.isEmpty())Text("Search videos or channels",color=Muted,fontSize=14.sp);inner()})
    }
    Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
     ServiceProvider.entries.forEach{p->FilterChip(selected=provider==p,onClick={onProvider(p)},label={Text(p.title)})}
     Button(onClick=onSearch,enabled=query.isNotBlank()){Icon(Icons.Outlined.Search,null);Text("Search")}
    }
   }
  }
  if(home)item{Text("YouTube via public Invidious instances · PeerTube via public API",color=Muted,fontSize=12.sp,lineHeight=18.sp)}
  if(loading)item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(modifier=Modifier.size(20.dp),color=Blue,strokeWidth=2.dp);Spacer(Modifier.width(8.dp));Text("Searching…",color=Muted)}}
  if(error!=null)item{ErrorLabel(error)}
  items(results,key={it.service+it.id}){VideoCard(it,onPlay)}
 }
}
@Composable private fun VideoCard(v:VideoItem,onPlay:(VideoItem)->Unit){
 Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(15.dp)).clickable{onPlay(v)}.padding(9.dp),verticalAlignment=Alignment.CenterVertically){
  AsyncImage(model=v.thumbnail.ifBlank{null},contentDescription=null,modifier=Modifier.width(124.dp).height(76.dp).background(Raised,RoundedCornerShape(9.dp)))
  Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text(v.title,color=White,fontWeight=FontWeight.SemiBold,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
   Text(v.author,color=Muted,fontSize=11.sp,maxLines=1);Text(v.service.lowercase()+" · "+duration(v.durationSeconds),color=Blue,fontSize=10.sp)
  }
 }
}
@Composable private fun LibraryPage(favorites:List<VideoItem>,history:List<VideoItem>,onPlay:(VideoItem)->Unit){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(11.dp),contentPadding=PaddingValues(top=8.dp,bottom=24.dp)){
  item{Text("Library",fontSize=28.sp,fontWeight=FontWeight.Bold,color=White)}
  item{Heading("Saved videos","Your local favorites")};if(favorites.isEmpty())item{EmptyCard("Save videos from the player to find them here.")}
  items(favorites,key={"f"+it.service+it.id}){VideoCard(it,onPlay)}
  item{Heading("Watch history","Recently played")};if(history.isEmpty())item{EmptyCard("Videos you play will appear here.")}
  items(history,key={"h"+it.service+it.id}){VideoCard(it,onPlay)}
 }
}
@Composable private fun SettingsPage(provider:ServiceProvider,onProvider:(ServiceProvider)->Unit){
 Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
  Text("Settings",fontSize=28.sp,fontWeight=FontWeight.Bold,color=White);Heading("Default service","Choose where new searches are sent")
  ServiceProvider.entries.forEach{p->Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).clickable{onProvider(p)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){Text(p.title,color=White,fontWeight=FontWeight.SemiBold);Text(if(p==ServiceProvider.YOUTUBE)"Invidious public API instances" else "PeerTube public API",color=Muted,fontSize=12.sp)}
   Text(if(provider==p)"Selected" else "Select",color=Blue,fontSize=12.sp)
  }}
  InfoCard("Playback","Media3 · Background audio · Picture-in-Picture")
  InfoCard("Smart skipping","SponsorBlock read-only segment lookup")
  InfoCard("Downloads","Android Download Manager · App-specific storage")
  Text("OctoPipe 0.2.0 · Development build",color=Muted,fontSize=12.sp)
 }
}
@Composable private fun PlayerPage(player:Player?,info:StreamInfo?,segments:List<SponsorSegment>,error:String?,onCaptionMenu:(Boolean)->Unit,captionMenu:Boolean,onDownload:()->Unit,onPip:()->Unit,onFavorite:(VideoItem)->Unit){
 Column(Modifier.fillMaxSize().padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("Now playing",fontSize=25.sp,fontWeight=FontWeight.Bold,color=White)
  if(player!=null&&info!=null){
   AndroidView(factory={ctx->PlayerView(ctx).apply{this.player=player;useController=true;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)}},update={it.player=player},modifier=Modifier.fillMaxWidth().height(220.dp).background(Color.Black,RoundedCornerShape(12.dp)))
   Text(info.video.title,color=White,fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(info.video.author,color=Muted,fontSize=12.sp)
   Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
    OutlinedButton(onClick=onDownload,enabled=info.downloadUrl!=null){Icon(Icons.Outlined.Download,null);Text("Download")}
    OutlinedButton(onClick=onPip){Icon(Icons.Outlined.PictureInPictureAlt,null);Text("PiP")}
    OutlinedButton(onClick={onFavorite(info.video)}){Icon(Icons.Outlined.BookmarkAdd,null);Text("Save")}
   }
   if(info.captions.isNotEmpty())Box{
    OutlinedButton(onClick={onCaptionMenu(true)}){Text("Subtitles")}
    DropdownMenu(expanded=captionMenu,onDismissRequest={onCaptionMenu(false)}){
     info.captions.forEach{c->DropdownMenuItem(text={Text(c.label)},onClick={
      player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredTextLanguage(c.languageCode).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).build()
      onCaptionMenu(false)
     })}
     DropdownMenuItem(text={Text("Turn subtitles off")},onClick={
      player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
      onCaptionMenu(false)
     })
    }
   }
   if(info.downloadUrl==null)Text("Offline download unavailable for this stream format.",color=Muted,fontSize=11.sp)
   if(segments.isNotEmpty())Text("SponsorBlock active · "+segments.size+" segments",color=Blue,fontSize=12.sp)
   Text("Background audio is managed by the media session service.",color=Muted,fontSize=11.sp)
  }else EmptyCard("Select a video from Search or Explore to start playback.")
  if(error!=null)ErrorLabel(error)
 }
}
@Composable private fun Heading(title:String,subtitle:String){Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Text(title,color=White,fontWeight=FontWeight.SemiBold,fontSize=17.sp);Text(subtitle,color=Muted,fontSize=12.sp)}}
@Composable private fun InfoCard(title:String,subtitle:String){Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(15.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,color=White,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted,fontSize=12.sp)}}
@Composable private fun EmptyCard(text:String){Box(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(17.dp)){Text(text,color=Muted,fontSize=13.sp,lineHeight=18.sp)}}
@Composable private fun ErrorLabel(text:String){Text(text,color=Color(0xFFFF9292),fontSize=12.sp,lineHeight=18.sp)}
private fun duration(s:Long):String{if(s<=0)return "Live / unknown";val h=s/3600;val m=(s%3600)/60;val r=s%60;return if(h>0)"%d:%02d:%02d".format(h,m,r) else "%d:%02d".format(m,r)}
