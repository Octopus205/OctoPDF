package com.octopus.octopipe.playback
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
class OctoPlaybackService:MediaSessionService() {
 private var session:MediaSession?=null
 private var exo:ExoPlayer?=null
 override fun onCreate(){super.onCreate();val p=ExoPlayer.Builder(this).build();exo=p;session=MediaSession.Builder(this,p).build()}
 override fun onGetSession(controllerInfo:MediaSession.ControllerInfo):MediaSession?=session
 override fun onDestroy(){session?.release();exo?.release();session=null;exo=null;super.onDestroy()}
}
