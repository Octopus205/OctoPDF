package com.octopus.octopipe

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.content.pm.ActivityInfo
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
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
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.octopus.octopipe.data.*
import com.octopus.octopipe.playback.OctoPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BgState=mutableStateOf(Color(0xFF080B14))
private val PanelState=mutableStateOf(Color(0xFF111827))
private val RaisedState=mutableStateOf(Color(0xFF19243A))
private val BlueState=mutableStateOf(Color(0xFF69D5FF))
private val WhiteState=mutableStateOf(Color(0xFFF5F7FF))
private val MutedState=mutableStateOf(Color(0xFF9BAAC2))
private val Bg get()=BgState.value
private val Panel get()=PanelState.value
private val Raised get()=RaisedState.value
private val Blue get()=BlueState.value
private val Violet=Color(0xFF8C7BFF)
private val Mint=Color(0xFF72F0C1)
private val White get()=WhiteState.value
private val Muted get()=MutedState.value
private fun applyPalette(dark:Boolean){
 if(dark){BgState.value=Color(0xFF080B14);PanelState.value=Color(0xFF111827);RaisedState.value=Color(0xFF19243A);BlueState.value=Color(0xFF69D5FF);WhiteState.value=Color(0xFFF5F7FF);MutedState.value=Color(0xFF9BAAC2)}
 else{BgState.value=Color(0xFFF2F5FA);PanelState.value=Color(0xFFFFFFFF);RaisedState.value=Color(0xFFE5ECF5);BlueState.value=Color(0xFF087EBD);WhiteState.value=Color(0xFF172338);MutedState.value=Color(0xFF617189)}
}

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
private enum class Tab(val label:String){HOME("Home"),EXPLORE("Explore"),DISCOVER("Discover"),SUBSCRIPTIONS("Subscriptions"),CHANNEL("Channel"),LIBRARY("Library"),SETTINGS("Settings"),PLAYER("Player")}

@Composable private fun OctoPipeApp(player:Player?,onPip:()->Unit){
 val context=androidx.compose.ui.platform.LocalContext.current
 val store=remember{LibraryStore(context)}
 val prefs=remember{context.getSharedPreferences("octopipe_settings",android.content.Context.MODE_PRIVATE)}
 var darkMode by remember{mutableStateOf(prefs.getBoolean("dark_mode",true))}
 val drawerState=rememberDrawerState(DrawerValue.Closed)
 val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null){runCatching{context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)};prefs.edit().putString("download_tree_uri",uri.toString()).apply()}}
 LaunchedEffect(darkMode){applyPalette(darkMode)}
 val scope=rememberCoroutineScope()
 var tab by remember{mutableStateOf(Tab.HOME)}
 var provider by remember{mutableStateOf(runCatching{ServiceProvider.valueOf(prefs.getString("provider",ServiceProvider.YOUTUBE.name)!!)}.getOrDefault(ServiceProvider.YOUTUBE))}
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
 var activeChannel by remember{mutableStateOf<ChannelItem?>(null)}
 var channelReturnTab by remember{mutableStateOf(Tab.DISCOVER)}
 val channelVideos=remember{mutableStateListOf<VideoItem>()}
 var current by remember{mutableStateOf<StreamInfo?>(null)}
 var segments by remember{mutableStateOf<List<SponsorSegment>>(emptyList())}
 var captionMenu by remember{mutableStateOf(false)}
 var qualityMenu by remember{mutableStateOf(false)}
 var playerFullscreen by remember{mutableStateOf(false)}
 var selectedQuality by remember{mutableStateOf("Auto")}
 var resizeMode by remember{mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)}
 fun changeProvider(p:ServiceProvider){provider=p;prefs.edit().putString("provider",p.name).apply()}
 fun search(){if(query.isBlank())return;scope.launch{loading=true;error=null;try{results.clear();results.addAll(VideoRepository.search(provider,query));tab=Tab.EXPLORE}catch(e:Exception){error=e.message?:"Search failed."}finally{loading=false}}}
 fun searchDiscovery(){if(query.isBlank())return;scope.launch{loading=true;error=null;try{
  if(discoverMode=="Channels"){channelResults.clear();channelResults.addAll(DiscoveryRepository.searchChannels(provider,query))}
  else{playlistResults.clear();playlistResults.addAll(DiscoveryRepository.searchPlaylists(provider,query))}
 }catch(e:Exception){error=e.message?:"Discovery failed"}finally{loading=false}}}
 fun openPlaylist(p:PlaylistItem){if(p.id.isBlank()){activePlaylist=null;playlistVideos.clear();return};scope.launch{loading=true;error=null;activePlaylist=p;try{playlistVideos.clear();playlistVideos.addAll(DiscoveryRepository.playlistVideos(p))}catch(e:Exception){error=e.message?:"Could not load playlist"}finally{loading=false}}}
 fun openChannel(ch:ChannelItem){channelReturnTab=tab;activeChannel=ch;tab=Tab.CHANNEL;scope.launch{loading=true;error=null;try{channelVideos.clear();channelVideos.addAll(DiscoveryRepository.channelVideos(ch))}catch(e:Exception){error=e.message?:"Could not load channel"}finally{loading=false}}}
 fun downloadPlaylist(){val list=playlistVideos.toList();scope.launch{loading=true;var count=0;for(v in list){try{val info=VideoRepository.resolve(v);if(info.downloadUrl!=null){DownloadHelper.enqueue(context,info);count++}}catch(_:Exception){}};error="Queued "+count+" of "+list.size+" videos";loading=false}}
 fun openVideoChannel(v:VideoItem){val id=v.channelId?:return;val url=v.channelUrl?:if(v.service==ServiceProvider.YOUTUBE.name)"https://www.youtube.com/channel/$id" else "https://peertube.tv/video-channels/$id";openChannel(ChannelItem(id,v.author,"",v.service,url))}
 fun play(v:VideoItem){scope.launch{loading=true;error=null;tab=Tab.PLAYER;try{
  val info=VideoRepository.resolve(v);current=info;selectedQuality=info.variants.firstOrNull()?.label?:"Auto";store.addHistory(v)
  history.removeAll{it.id==v.id&&it.service==v.service};history.add(0,v)
  segments=emptyList()
  val subtitles=info.captions.map{c->MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(c.url)).setMimeType(MimeTypes.TEXT_VTT).setLanguage(c.languageCode).build()}
  val item=MediaItem.Builder().setUri(info.streamUrl).setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).setArtist(v.author).build()).setSubtitleConfigurations(subtitles).build()
  player?.setMediaItem(item);player?.prepare();player?.play()
  if(v.service==ServiceProvider.YOUTUBE.name)scope.launch{segments=SponsorBlockRepository.segments(v.id)}
 }catch(e:Exception){error=e.message?:"Unable to play this video."}finally{loading=false}}}
 LaunchedEffect(player,segments,current){val p=player?:return@LaunchedEffect;val skipped=mutableSetOf<Int>();while(true){if(p.isPlaying){val pos=p.currentPosition/1000.0;segments.forEachIndexed{i,s->if(pos>=s.startSeconds&&pos<s.endSeconds&&skipped.add(i))p.seekTo((s.endSeconds*1000).toLong())};if(pos<2)skipped.clear()};delay(450)}}
 MaterialTheme{
  ModalNavigationDrawer(drawerState=drawerState,drawerContent={ModalDrawerSheet(drawerContainerColor=Panel){SettingsDrawer(provider,::changeProvider,darkMode,{darkMode=it;prefs.edit().putBoolean("dark_mode",it).apply()},{scope.launch{drawerState.close()}},{folderPicker.launch(null)},{prefs.edit().remove("download_tree_uri").apply()})}}){
  Surface(Modifier.fillMaxSize(),color=Bg,contentColor=White){
   BoxWithConstraints(Modifier.fillMaxSize()){
    val wide=maxWidth>=600.dp
    Row(Modifier.fillMaxSize()){
     if(wide&&!playerFullscreen)NavigationRail(containerColor=Bg,header={AppMark(Modifier.padding(vertical=18.dp))}){
      Tab.entries.filter{it!=Tab.PLAYER&&it!=Tab.CHANNEL}.forEach{t->NavigationRailItem(selected=tab==t,onClick={tab=t},icon={TabIcon(t)},label={Text(t.label)})}
     }
     Column(Modifier.weight(1f).fillMaxSize()){
      if(!playerFullscreen)Header{scope.launch{drawerState.open()}}
      Box(Modifier.weight(1f).fillMaxWidth()){
       when(tab){
        Tab.HOME->SearchPage(query,{query=it},provider,::changeProvider,{search()},results,loading,error,::play,true,{tab=Tab.DISCOVER},::openVideoChannel)
        Tab.EXPLORE->SearchPage(query,{query=it},provider,::changeProvider,{search()},results,loading,error,::play,false,{},::openVideoChannel)
        Tab.DISCOVER->DiscoverPage(query,{query=it},provider,::changeProvider,discoverMode,{discoverMode=it;channelResults.clear();playlistResults.clear()},{searchDiscovery()},channelResults,playlistResults,playlistVideos,activePlaylist,loading,error,{ch->
         val added=store.toggleSubscription(ch);subscriptions.removeAll{it.id==ch.id&&it.service==ch.service};if(added)subscriptions.add(0,ch)
        },::openPlaylist,::downloadPlaylist,::play,::openChannel)
        Tab.SUBSCRIPTIONS->SubscriptionsPage(subscriptions,{ch->
         store.toggleSubscription(ch);subscriptions.removeAll{it.id==ch.id&&it.service==ch.service}
        },::openChannel)
        Tab.CHANNEL->ChannelPage(activeChannel,channelVideos,subscriptions.any{it.id==activeChannel?.id&&it.service==activeChannel?.service},loading,error,{ch->
         val added=store.toggleSubscription(ch);subscriptions.removeAll{it.id==ch.id&&it.service==ch.service};if(added)subscriptions.add(0,ch)
        },::play){tab=channelReturnTab}
        Tab.LIBRARY->LibraryPage(favorites,history,::play)
        Tab.SETTINGS->SettingsPage(provider,{provider=it})
        Tab.PLAYER->PlayerPage(player,current,segments,loading,error,{captionMenu=it},captionMenu,{current?.let{DownloadHelper.enqueue(context,it)}},{current?.let{DownloadHelper.enqueueAudio(context,it)}},onPip,qualityMenu,{qualityMenu=it},{label->
         val info=current?:return@PlayerPage
         val variant=info.variants.firstOrNull{it.label==label}
         val target=variant?.url?:info.streamUrl
         val pos=player?.currentPosition?:0L;val wasPlaying=player?.isPlaying==true
         selectedQuality=label;player?.setMediaItem(MediaItem.Builder().setUri(target).setMediaMetadata(MediaMetadata.Builder().setTitle(info.video.title).setArtist(info.video.author).build()).setSubtitleConfigurations(info.captions.map{cap->MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(cap.url)).setMimeType(MimeTypes.TEXT_VTT).setLanguage(cap.languageCode).build()}).build(),pos);player?.prepare();if(wasPlaying)player?.play()
        },selectedQuality,playerFullscreen,{playerFullscreen=it},{resizeMode=when(resizeMode){AspectRatioFrameLayout.RESIZE_MODE_FIT->AspectRatioFrameLayout.RESIZE_MODE_ZOOM;AspectRatioFrameLayout.RESIZE_MODE_ZOOM->AspectRatioFrameLayout.RESIZE_MODE_FILL;else->AspectRatioFrameLayout.RESIZE_MODE_FIT}},resizeMode){v->
         val added=store.toggle(v);favorites.removeAll{it.id==v.id&&it.service==v.service};if(added)favorites.add(0,v)
        }
       }
      }
      if(!wide&&!playerFullscreen)NavigationBar(containerColor=Bg,contentColor=White,tonalElevation=0.dp){
       listOf(Tab.HOME,Tab.DISCOVER,Tab.SUBSCRIPTIONS,Tab.LIBRARY).forEach{t->
        NavigationBarItem(selected=tab==t,onClick={tab=t},icon={TabIcon(t)},label={Text(t.label,fontSize=10.sp)},alwaysShowLabel=true,
         colors=NavigationBarItemDefaults.colors(selectedIconColor=Blue,selectedTextColor=Blue,indicatorColor=Raised,unselectedIconColor=Muted,unselectedTextColor=Muted))
       }
      }
     }
    }
   }
  }
 }
 }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsDrawer(provider:ServiceProvider,onProvider:(ServiceProvider)->Unit,dark:Boolean,onTheme:(Boolean)->Unit,onClose:()->Unit,onFolder:()->Unit,onClearFolder:()->Unit){
 LazyColumn(Modifier.fillMaxHeight().widthIn(min=280.dp,max=340.dp).padding(horizontal=16.dp),contentPadding=PaddingValues(top=28.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  item{Row(verticalAlignment=Alignment.CenterVertically){AppMark(Modifier.size(42.dp));Spacer(Modifier.width(10.dp));Column{Text("OctoPipe",fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=White);Text("Control center",fontSize=11.sp,color=Muted)};Spacer(Modifier.weight(1f));IconButton(onClick=onClose){Icon(Icons.Outlined.Close,null,tint=Muted)}}}
  item{HorizontalDivider(color=Raised)}
  item{Text("APPEARANCE",fontSize=10.sp,color=Blue,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)}
  item{Text("Theme",color=White,fontWeight=FontWeight.SemiBold)}
  item{SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
   SegmentedButton(selected=dark,onClick={onTheme(true)},shape=SegmentedButtonDefaults.itemShape(0,2)){Icon(Icons.Outlined.DarkMode,null);Spacer(Modifier.width(5.dp));Text("Dark")}
   SegmentedButton(selected=!dark,onClick={onTheme(false)},shape=SegmentedButtonDefaults.itemShape(1,2)){Icon(Icons.Outlined.LightMode,null);Spacer(Modifier.width(5.dp));Text("Light")}
  }}
  item{HorizontalDivider(color=Raised)}
  item{Text("VIDEO SERVICES",fontSize=10.sp,color=Blue,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)}
  item{Text("Default search provider",color=White,fontWeight=FontWeight.SemiBold)}
  items(ServiceProvider.entries){p->NavigationDrawerItem(label={Text(p.title)},selected=provider==p,onClick={onProvider(p)},icon={Icon(if(p==ServiceProvider.YOUTUBE)Icons.Outlined.SmartDisplay else Icons.Outlined.Public,null)},modifier=Modifier.padding(vertical=2.dp))}
  item{HorizontalDivider(color=Raised)}
  item{Text("DOWNLOADS",fontSize=10.sp,color=Blue,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)}
  item{NavigationDrawerItem(label={Text("Choose download folder")},selected=false,onClick=onFolder,icon={Icon(Icons.Outlined.FolderOpen,null)})}
  item{TextButton(onClick=onClearFolder){Text("Use default app folder")}}
  item{Text("Selected: "+if(androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("octopipe_settings",android.content.Context.MODE_PRIVATE).getString("download_tree_uri",null)!=null)"Custom folder" else "App Downloads",fontSize=11.sp,color=Muted)}
  item{HorizontalDivider(color=Raised)}
  item{Text("PLAYBACK",fontSize=10.sp,color=Blue,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)}
  item{InfoCard("Video quality","Choose Auto or an available resolution from the player")}
  item{InfoCard("Screen fit","Fit, Zoom and Fill controls are available in the player")}
  item{InfoCard("Background playback","Media3 playback service and Picture-in-Picture")}
  item{HorizontalDivider(color=Raised)}
  item{Text("LIBRARY & PRIVACY",fontSize=10.sp,color=Blue,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)}
  item{InfoCard("Subscriptions","Saved locally on this device")}
  item{InfoCard("History & favorites","Stored locally in OctoPipe")}
  item{Text("OctoPipe · Experimental build",fontSize=11.sp,color=Muted)}
 }
}
@Composable private fun Header(onSettings:()->Unit){
 Row(Modifier.fillMaxWidth().background(Bg).padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
  Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Blue,Violet)),RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center){AppMark(Modifier.size(34.dp))}
  Spacer(Modifier.width(11.dp));Column(verticalArrangement=Arrangement.spacedBy(1.dp)){Text("OCTO",fontSize=10.sp,fontWeight=FontWeight.Black,color=Blue,letterSpacing=2.sp);Text("pipe",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=White,lineHeight=20.sp)}
  Spacer(Modifier.weight(1f));Box(Modifier.background(Panel,RoundedCornerShape(30.dp)).padding(horizontal=10.dp,vertical=6.dp)){Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(6.dp).background(Mint,RoundedCornerShape(50)));Spacer(Modifier.width(6.dp));Text("BETA",fontSize=9.sp,color=Mint,fontWeight=FontWeight.Bold)}}
  IconButton(onClick=onSettings){Icon(Icons.Outlined.Tune,null,tint=Muted)}
 }
}
@Composable private fun AppMark(modifier:Modifier=Modifier){AsyncImage(model=com.octopus.octopipe.R.drawable.octopipe_icon,contentDescription="OctoPipe octopus icon",modifier=modifier.size(40.dp))}
@Composable private fun TabIcon(tab:Tab){val icon=when(tab){Tab.HOME->Icons.Outlined.Home;Tab.EXPLORE->Icons.Outlined.Explore;Tab.DISCOVER->Icons.Outlined.VideoLibrary;Tab.SUBSCRIPTIONS->Icons.Outlined.Subscriptions;Tab.CHANNEL->Icons.Outlined.Groups;Tab.LIBRARY->Icons.Outlined.Bookmarks;else->Icons.Outlined.Settings};Icon(icon,contentDescription=tab.label)}

@Composable private fun SearchPage(query:String,onQuery:(String)->Unit,provider:ServiceProvider,onProvider:(ServiceProvider)->Unit,onSearch:()->Unit,results:List<VideoItem>,loading:Boolean,error:String?,onPlay:(VideoItem)->Unit,home:Boolean,onDiscover:()->Unit={},onChannel:(VideoItem)->Unit={}){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(15.dp),contentPadding=PaddingValues(top=12.dp,bottom=28.dp)){
  if(home)item{
   Box(Modifier.fillMaxWidth().heightIn(min=196.dp).background(Brush.linearGradient(listOf(Color(0xFF182A4A),Color(0xFF28245A),Color(0xFF142F40))),RoundedCornerShape(26.dp)).padding(19.dp)){
    Box(Modifier.align(Alignment.TopEnd).size(142.dp).background(Brush.radialGradient(listOf(Color(0x5569D5FF),Color.Transparent)),RoundedCornerShape(100.dp)))
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.AutoAwesome,null,tint=Mint);Spacer(Modifier.width(6.dp));Text("A UNIVERSE OF VIDEO",fontSize=10.sp,color=Mint,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)}
     Text("Find your\nnext obsession.",fontSize=31.sp,lineHeight=34.sp,fontWeight=FontWeight.ExtraBold,color=White)
     Text("One search. Endless rabbit holes.",fontSize=12.sp,color=Color(0xFFD0D9EA))
     Spacer(Modifier.height(2.dp))
     OutlinedButton(onClick=onDiscover,colors=ButtonDefaults.outlinedButtonColors(contentColor=White),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0x6679D9FF)),shape=RoundedCornerShape(14.dp)){
      Text("Explore creators & playlists");Spacer(Modifier.width(7.dp));Icon(Icons.Outlined.ArrowForward,null)
     }
    }
   }
  }else{
   item{Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Text("Search",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=White);Text("Find something worth watching.",color=Muted,fontSize=13.sp)}}
  }
  item{
   Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
    Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(17.dp)).padding(start=14.dp,end=7.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically){
     Icon(Icons.Outlined.Search,null,tint=Blue);Spacer(Modifier.width(10.dp))
     BasicTextField(value=query,onValueChange=onQuery,modifier=Modifier.weight(1f),singleLine=true,cursorBrush=SolidColor(Blue),textStyle=MaterialTheme.typography.bodyLarge.copy(color=White),decorationBox={inner->if(query.isEmpty())Text("Videos, topics, creators…",color=Muted,fontSize=13.sp);inner()})
     Button(onClick=onSearch,enabled=query.isNotBlank(),shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(horizontal=15.dp,vertical=10.dp),colors=ButtonDefaults.buttonColors(containerColor=Blue,contentColor=Bg)){Icon(Icons.Outlined.ArrowForward,null)}
    }
    Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
     ServiceProvider.entries.forEach{p->FilterChip(selected=provider==p,onClick={onProvider(p)},label={Text(p.title,fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF203A54),selectedLabelColor=Blue,containerColor=Panel,labelColor=Muted))}
     Spacer(Modifier.weight(1f))
     Text(if(provider==ServiceProvider.YOUTUBE)"PUBLIC INDEX" else "FEDIVERSE",fontSize=8.sp,color=Muted,letterSpacing=1.sp)
    }
   }
  }
  if(home&&results.isEmpty()&&!loading&&error==null)item{
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){
    MiniFeature("Creators",Icons.Outlined.Groups,Color(0xFF8C7BFF),onDiscover,Modifier.weight(1f))
    MiniFeature("Playlists",Icons.Outlined.QueueMusic,Blue,onDiscover,Modifier.weight(1f))
   }
  }
  if(results.isNotEmpty())item{Row(verticalAlignment=Alignment.CenterVertically){Text(if(home)"Picked for your search" else "Search results",fontSize=17.sp,fontWeight=FontWeight.Bold,color=White);Spacer(Modifier.weight(1f));Text(results.size.toString()+" results",fontSize=11.sp,color=Muted)}}
  if(loading)item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(modifier=Modifier.size(20.dp),color=Blue,strokeWidth=2.dp);Spacer(Modifier.width(8.dp));Text("Finding videos…",color=Muted)}}
  if(error!=null)item{ErrorLabel(error)}
  items(results,key={it.service+it.id}){VideoCard(it,onPlay,onChannel)}
  if(home&&results.isEmpty()&&!loading)item{Text("Search across public video services, then save, follow or download what you love.",color=Muted,fontSize=11.sp,lineHeight=17.sp)}
 }
}
@Composable private fun MiniFeature(title:String,icon:androidx.compose.ui.graphics.vector.ImageVector,tint:Color,onClick:()->Unit,modifier:Modifier=Modifier){
 Row(modifier.background(Panel,RoundedCornerShape(15.dp)).clickable(onClick=onClick).padding(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=tint);Spacer(Modifier.width(8.dp));Text(title,color=White,fontSize=12.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.weight(1f));Icon(Icons.Outlined.ChevronRight,null,tint=Muted)}
}
@Composable private fun VideoCard(v:VideoItem,onPlay:(VideoItem)->Unit,onChannel:(VideoItem)->Unit={}){
 Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(19.dp)).clickable{onPlay(v)}.padding(9.dp)){
  Box(Modifier.fillMaxWidth().height(174.dp).background(Raised,RoundedCornerShape(13.dp))){
   AsyncImage(model=v.thumbnail.ifBlank{null},contentDescription=null,modifier=Modifier.fillMaxWidth().height(174.dp).background(Raised,RoundedCornerShape(13.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
   Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xDD080B14),RoundedCornerShape(7.dp)).padding(horizontal=7.dp,vertical=4.dp)){Text(duration(v.durationSeconds),fontSize=10.sp,color=White,fontWeight=FontWeight.SemiBold)}
   Box(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0xCC162A43),RoundedCornerShape(7.dp)).padding(horizontal=7.dp,vertical=4.dp)){Text(v.service.uppercase(),fontSize=8.sp,color=Blue,fontWeight=FontWeight.Bold,letterSpacing=1.sp)}
  }
  Spacer(Modifier.height(10.dp))
  Row(verticalAlignment=Alignment.Top){
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(v.title,color=White,fontWeight=FontWeight.Bold,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=18.sp)
    Text(v.author,color=Muted,fontSize=11.sp,maxLines=1,modifier=if(v.channelId!=null)Modifier.clickable{onChannel(v)} else Modifier)
   }
   Box(Modifier.padding(start=8.dp).size(34.dp).background(Color(0xFF1C3851),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.PlayArrow,null,tint=Blue)}
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
@Composable private fun PlayerPage(player:Player?,info:StreamInfo?,segments:List<SponsorSegment>,loading:Boolean,error:String?,onCaptionMenu:(Boolean)->Unit,captionMenu:Boolean,onDownload:()->Unit,onAudioDownload:()->Unit,onPip:()->Unit,qualityMenu:Boolean,onQualityMenu:(Boolean)->Unit,onQuality:(String)->Unit,selectedQuality:String,fullscreen:Boolean,onFullscreen:(Boolean)->Unit,onResize:()->Unit,displayMode:Int,onFavorite:(VideoItem)->Unit){
 Column(Modifier.fillMaxSize().then(if(fullscreen)Modifier else Modifier.padding(15.dp)),verticalArrangement=Arrangement.spacedBy(10.dp)){
  if(player!=null&&info!=null){
   if(fullscreen){
    Box(Modifier.fillMaxSize().background(Color.Black)){
     AndroidView(factory={ctx->PlayerView(ctx).apply{this.player=player;useController=true;resizeMode=displayMode;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)}},update={it.player=player;it.resizeMode=displayMode},modifier=Modifier.fillMaxSize())
     IconButton(onClick={onFullscreen(false)},modifier=Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0x88000000),RoundedCornerShape(30.dp))){Icon(Icons.Outlined.FullscreenExit,null,tint=Color.White)}
    }
   }else{
    Text("Now playing",fontSize=25.sp,fontWeight=FontWeight.Bold,color=White)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
     Box{OutlinedButton(onClick={onQualityMenu(true)}){Icon(Icons.Outlined.HighQuality,null);Text(selectedQuality);Icon(Icons.Outlined.ArrowDropDown,null)}
      DropdownMenu(expanded=qualityMenu,onDismissRequest={onQualityMenu(false)}){
       DropdownMenuItem(text={Text("Auto")},onClick={onQuality("Auto")})
       info.variants.forEach{variant->DropdownMenuItem(text={Text(variant.label)},onClick={onQuality(variant.label)})}
      }
     }
     OutlinedButton(onClick=onResize){Icon(Icons.Outlined.AspectRatio,null);Text(when(displayMode){AspectRatioFrameLayout.RESIZE_MODE_FIT->"Fit";AspectRatioFrameLayout.RESIZE_MODE_ZOOM->"Zoom";else->"Fill"})}
     Spacer(Modifier.weight(1f))
     IconButton(onClick={onFullscreen(true)}){Icon(Icons.Outlined.Fullscreen,null,tint=Blue)}
    }
    AndroidView(factory={ctx->PlayerView(ctx).apply{this.player=player;useController=true;resizeMode=displayMode;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)}},update={it.player=player;it.resizeMode=displayMode},modifier=Modifier.fillMaxWidth().height(225.dp).background(Color.Black,RoundedCornerShape(12.dp)))
    Text(info.video.title,color=White,fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(info.video.author,color=Muted,fontSize=12.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
     OutlinedButton(onClick=onDownload,enabled=info.downloadUrl!=null){Icon(Icons.Outlined.Download,null);Text("Video")}
     OutlinedButton(onClick=onAudioDownload,enabled=info.audioDownloadUrl!=null){Icon(Icons.Outlined.MusicNote,null);Text("Audio")}
     OutlinedButton(onClick=onPip){Icon(Icons.Outlined.PictureInPictureAlt,null);Text("PiP")}
     OutlinedButton(onClick={onFavorite(info.video)}){Icon(Icons.Outlined.BookmarkAdd,null);Text("Save")}
    }
    if(info.captions.isNotEmpty())Box{
     OutlinedButton(onClick={onCaptionMenu(true)}){Text("Subtitles")}
     DropdownMenu(expanded=captionMenu,onDismissRequest={onCaptionMenu(false)}){
      info.captions.forEach{caption->DropdownMenuItem(text={Text(caption.label)},onClick={
       player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredTextLanguage(caption.languageCode).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).build();onCaptionMenu(false)
      })}
      DropdownMenuItem(text={Text("Turn subtitles off")},onClick={player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build().let{params->{player.trackSelectionParameters=params;onCaptionMenu(false)}}})
     }
    }
    if(info.downloadUrl==null)Text("Offline video download unavailable for this stream format.",color=Muted,fontSize=11.sp)
    if(segments.isNotEmpty())Text("SponsorBlock active · "+segments.size+" segments",color=Blue,fontSize=12.sp)
   }
  }else EmptyCard("Select a video from Search or Explore to start playback.")
  if(error!=null)ErrorLabel(error)
 }
}
@Composable private fun DiscoverPage(
 query:String,onQuery:(String)->Unit,provider:ServiceProvider,onProvider:(ServiceProvider)->Unit,
 mode:String,onMode:(String)->Unit,onSearch:()->Unit,channels:List<ChannelItem>,playlists:List<PlaylistItem>,
 videos:List<VideoItem>,activePlaylist:PlaylistItem?,loading:Boolean,error:String?,
 onSubscribe:(ChannelItem)->Unit,onPlaylist:(PlaylistItem)->Unit,onDownloadPlaylist:()->Unit,onPlay:(VideoItem)->Unit,onChannel:(ChannelItem)->Unit
){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=8.dp,bottom=24.dp)){
  item{Text("Discover",fontSize=28.sp,fontWeight=FontWeight.Bold,color=White);Text("Find creators and collections",color=Muted,fontSize=13.sp)}
  if(activePlaylist!=null){
   item{Row(verticalAlignment=Alignment.CenterVertically){Text("←",color=Blue,modifier=Modifier.clickable{onPlaylist(PlaylistItem("","","","",0,"",""))}.padding(8.dp));Column{Text(activePlaylist.title,color=White,fontWeight=FontWeight.Bold);Text(activePlaylist.author+" · "+activePlaylist.videoCount+" videos",color=Muted,fontSize=12.sp)}}}
   item{Button(onClick=onDownloadPlaylist,enabled=videos.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text("Download playlist")}}
   if(loading)item{CircularProgressIndicator(color=Blue)}
   items(videos,key={"playlist-"+it.service+it.id}){VideoCard(it,onPlay)}
  }else{
   item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=mode=="Channels",onClick={onMode("Channels")},label={Text("Channels")});FilterChip(selected=mode=="Playlists",onClick={onMode("Playlists")},label={Text("Playlists")})}}
   item{Row(Modifier.fillMaxWidth().background(Raised,RoundedCornerShape(14.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically){
    BasicTextField(value=query,onValueChange=onQuery,modifier=Modifier.weight(1f),singleLine=true,cursorBrush=SolidColor(Blue),textStyle=MaterialTheme.typography.bodyLarge.copy(color=White),decorationBox={inner->if(query.isEmpty())Text("Search "+mode.lowercase(),color=Muted);inner()})
    Button(onClick=onSearch,enabled=query.isNotBlank()){Icon(Icons.Outlined.Search,null)}
   }}
   item{Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){ServiceProvider.entries.forEach{p->FilterChip(selected=provider==p,onClick={onProvider(p)},label={Text(p.title)})}}}
   if(loading)item{Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(modifier=Modifier.size(18.dp),color=Blue);Spacer(Modifier.width(8.dp));Text("Searching…",color=Muted)}}
   if(error!=null)item{ErrorLabel(error)}
   if(mode=="Channels")items(channels,key={"channel-"+it.service+it.id}){ch->
    Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).clickable{onChannel(ch)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
     AsyncImage(model=ch.thumbnail.ifBlank{null},contentDescription=null,modifier=Modifier.size(48.dp).background(Raised,RoundedCornerShape(24.dp)))
     Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(ch.name,color=White,fontWeight=FontWeight.SemiBold,maxLines=1);Text(ch.service,color=Muted,fontSize=11.sp)}
     OutlinedButton(onClick={onSubscribe(ch)}){Icon(Icons.Outlined.Add,null);Text("Subscribe")}
    }
   }
   else items(playlists,key={"playlist-result-"+it.service+it.id}){p->
    Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).clickable{onPlaylist(p)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
     Icon(Icons.Outlined.PlaylistPlay,null,tint=Blue,modifier=Modifier.size(46.dp));Spacer(Modifier.width(10.dp))
     Column(Modifier.weight(1f)){Text(p.title,color=White,fontWeight=FontWeight.SemiBold,maxLines=2);Text(p.author+" · "+p.videoCount+" videos",color=Muted,fontSize=11.sp)}
     Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
    }
   }
  }
 }
}
@Composable private fun SubscriptionsPage(channels:List<ChannelItem>,onUnsubscribe:(ChannelItem)->Unit,onChannel:(ChannelItem)->Unit){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(11.dp),contentPadding=PaddingValues(top=8.dp,bottom=24.dp)){
  item{Text("Subscriptions",fontSize=28.sp,fontWeight=FontWeight.Bold,color=White);Text("Your followed creators, saved on this device",color=Muted,fontSize=13.sp)}
  if(channels.isEmpty())item{EmptyCard("No subscriptions yet. Open Discover, search for a channel, and tap Subscribe.")}
  items(channels,key={"sub-"+it.service+it.id}){ch->
   Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).clickable{onChannel(ch)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
    AsyncImage(model=ch.thumbnail.ifBlank{null},contentDescription=null,modifier=Modifier.size(48.dp).background(Raised,RoundedCornerShape(24.dp)))
    Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(ch.name,color=White,fontWeight=FontWeight.SemiBold);Text(ch.service,color=Muted,fontSize=11.sp)}
    TextButton(onClick={onUnsubscribe(ch)}){Text("Following",color=Blue)}
   }
  }
 }
}
@Composable private fun ChannelPage(channel:ChannelItem?,videos:List<VideoItem>,following:Boolean,loading:Boolean,error:String?,onSubscribe:(ChannelItem)->Unit,onPlay:(VideoItem)->Unit,onBack:()->Unit){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=8.dp,bottom=24.dp)){
  item{Text("‹  Back to Discover",color=Blue,modifier=Modifier.clickable(onClick=onBack).padding(vertical=8.dp))}
  if(channel!=null)item{
   Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1A2A49),Color(0xFF252044))),RoundedCornerShape(22.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
    AsyncImage(model=channel.thumbnail.ifBlank{null},contentDescription=null,modifier=Modifier.size(68.dp).background(Raised,RoundedCornerShape(34.dp)))
    Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(channel.name,color=White,fontWeight=FontWeight.Bold,fontSize=18.sp,maxLines=2);Text(channel.service,color=Muted,fontSize=11.sp)}
    Button(onClick={onSubscribe(channel)}){Text(if(following)"Following" else "Subscribe")}
   }
  }
  if(channel!=null)item{Heading("Latest videos","Videos published by this creator")}
  if(loading)item{CircularProgressIndicator(color=Blue)}
  if(error!=null)item{ErrorLabel(error)}
  if(videos.isEmpty()&&!loading)item{EmptyCard("No videos were returned for this channel.")}
  items(videos,key={"channel-video-"+it.service+it.id}){VideoCard(it,onPlay)}
 }
}
@Composable private fun Heading(title:String,subtitle:String){Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Text(title,color=White,fontWeight=FontWeight.SemiBold,fontSize=17.sp);Text(subtitle,color=Muted,fontSize=12.sp)}}
@Composable private fun InfoCard(title:String,subtitle:String){Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(15.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,color=White,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted,fontSize=12.sp)}}
@Composable private fun EmptyCard(text:String){Box(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(17.dp)){Text(text,color=Muted,fontSize=13.sp,lineHeight=18.sp)}}
@Composable private fun ErrorLabel(text:String){Text(text,color=Color(0xFFFF9292),fontSize=12.sp,lineHeight=18.sp)}
private fun duration(s:Long):String{if(s<=0)return "Live / unknown";val h=s/3600;val m=(s%3600)/60;val r=s%60;return if(h>0)"%d:%02d:%02d".format(h,m,r) else "%d:%02d".format(m,r)}
