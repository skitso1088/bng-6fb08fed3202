package com.bumpngrind.tv

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Up/Down on the remote control playback volume while a channel plays. The real volume buttons can't be used:
 * Android TV never passes them to apps, this box keeps its own HDMI volume fixed at maximum, and the TV
 * ignores the volume commands forwarded over HDMI-CEC.
 * The level is remembered between channels; [apply] receives the new playback volume (0..1).
 */
class AppVolume(private val activity: Activity, private val apply: (Float) -> Unit) {
    private val prefs = activity.getSharedPreferences("volume", Activity.MODE_PRIVATE)
    private var percent = prefs.getInt("level", 80).coerceIn(0, 100)
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { panel?.visibility = View.GONE }
    private var panel: View? = null
    private var label: TextView? = null
    private var fill: View? = null
    private var trackWidth = 0

    val level: Float get() = percent / 100f

    /** True if the key was Up or Down and has been used for volume. */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        percent = (percent + if (code == KeyEvent.KEYCODE_DPAD_UP) STEP else -STEP).coerceIn(0, 100)
        prefs.edit().putInt("level", percent).apply()
        apply(level)
        show()
        return true
    }

    fun show() {
        if (panel == null) build()
        label?.text = "Volume $percent%  (\u2191 \u2193)"
        fill?.layoutParams = fill?.layoutParams?.apply { width = trackWidth * percent / 100 }
        fill?.requestLayout()
        panel?.visibility = View.VISIBLE
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 2500)
    }

    fun release() = handler.removeCallbacks(hide)

    /** Bar sized from the screen so it reads the same at 720p, 1080p and 4K, inside the overscan margin. */
    private fun build() {
        val h = activity.resources.displayMetrics.heightPixels
        val w = activity.resources.displayMetrics.widthPixels
        trackWidth = w / 4
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xE60A0B10.toInt()); cornerRadius = h * 0.02f }
            setPadding(h / 40, h / 60, h / 40, h / 40)
        }
        val text = TextView(activity).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, h * 0.032f)
            setPadding(0, 0, 0, h / 80)
        }
        val track = FrameLayout(activity).apply {
            background = GradientDrawable().apply { setColor(0xFF2A2B38.toInt()); cornerRadius = h * 0.008f }
        }
        val bar = View(activity).apply {
            background = GradientDrawable().apply { setColor(0xFFFF5A1F.toInt()); cornerRadius = h * 0.008f }
        }
        track.addView(bar, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(text)
        box.addView(track, LinearLayout.LayoutParams(trackWidth, h / 60))
        activity.addContentView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { topMargin = h / 15; rightMargin = w / 20 })
        panel = box; label = text; fill = bar
    }

    private companion object { const val STEP = 5 }
}
