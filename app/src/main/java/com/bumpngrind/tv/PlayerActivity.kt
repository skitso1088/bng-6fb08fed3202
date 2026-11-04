package com.bumpngrind.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Native player for free-to-air channels (official broadcaster HLS streams).
 * Opens full screen behind the BumpNGrind logo screen. BACK returns to the menu.
 */
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var splash: View? = null
    private lateinit var volume: AppVolume
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url")
        if (url == null || !url.startsWith("https://")) { finish(); return }
        val title = intent.getStringExtra("title").orEmpty()
        volume = AppVolume(this) { player?.volume = it }

        val view = PlayerView(this).apply {
            useController = true
            controllerAutoShow = false   // controls only when the remote is used
            setBackgroundColor(0xFF000000.toInt())
            keepScreenOn = true
        }
        val stage = FrameLayout(this)
        stage.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash = Splash.build(this, title)
        stage.addView(splash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(stage)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("BumpNGrind/2.3 (Android TV)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        view.player = p
        p.volume = volume.level
        // No subtitles unless asked for (the player would otherwise pick up broadcast captions).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { hideSplash() }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "$title isn't available right now", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p
        handler.postDelayed({ hideSplash() }, 20000) // never cover the player forever
    }

    private fun hideSplash() {
        val s = splash ?: return
        splash = null
        s.animate().alpha(0f).setDuration(300).withEndAction { (s.parent as? ViewGroup)?.removeView(s) }.start()
        volume.show() // shows where the volume is and which buttons change it
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        (::volume.isInitialized && volume.handleKey(event)) || super.dispatchKeyEvent(event)

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        if (::volume.isInitialized) volume.release()
        player?.release()
        player = null
        finish()
    }
}
