package online.arnitcreation.musicplayer

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object {
        @Volatile var audioSessionId: Int = 0
    }
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        audioSessionId = player.audioSessionId
        // ExoPlayer may not create its audio output session until playback becomes ready.
        // Refresh the ID when playback starts or the playback state changes.
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                audioSessionId = player.audioSessionId
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                audioSessionId = player.audioSessionId
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        audioSessionId = 0
        super.onDestroy()
    }
}
