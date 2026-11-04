package com.bumpngrind.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val HOME = "file:///android_asset/home.html"
        private const val TV = "file:///android_asset/tv.html"
        private val GROUPS = listOf("New Zealand", "Free to Air NZ", "Australia", "Free to Air Australia", "USA", "UK", "Canada")
        private const val SCHEDULE_REFRESH_MS = 10 * 60 * 1000L

        /** "TNT SPORTS 5 International" -> "TNT SPORTS 5" */
        fun displayName(name: String) = name.removeSuffix(" International").trim()
    }

    private lateinit var web: WebView
    private val channels = ArrayList<JSONObject>()   // Bundled catalogue: web channel pages + free-to-air HLS streams.
    @Volatile private var schedule = "{}"            // what's on each channel (see Schedule)
    private lateinit var guide: Schedule
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { fetchSchedule(); handler.postDelayed(this, SCHEDULE_REFRESH_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadChannels()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkLoads = true // Only local menus may use the native bridge.
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            textZoom = 100          // ignore system font scale; menus scale themselves via fit()
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true  // honour the page's viewport meta
            loadWithOverviewMode = false
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.setBackgroundColor(0xFF0A0B10.toInt())
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                !isMenuUrl(request.url.toString())
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !isMenuUrl(url)
        }
        setContentView(web)
        guide = Schedule(channels, getSharedPreferences("schedule", MODE_PRIVATE))
        schedule = guide.merged() // last known, until refreshed
        web.loadUrl(HOME)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    /** Listings are downloaded natively (the menu WebView itself stays offline). */
    private fun fetchSchedule() = Thread {
        fun show(s: String) { schedule = s; runOnUiThread { web.evaluateJavascript("window.onSchedule&&onSchedule()", null) } }
        show(guide.refresh { show(it) })
    }.start()

    private fun loadChannels() {
        val catalogue = JSONArray(assets.open("channels.json").bufferedReader().use { it.readText() })
        for (i in 0 until catalogue.length()) {
            val channel = catalogue.getJSONObject(i)
            when (channel.getString("type")) {
                "channel_page" -> require(android.net.Uri.parse(channel.getString("page_url")).scheme == "https")
                "stream" -> require(android.net.Uri.parse(channel.getString("stream_url")).scheme == "https")
                else -> error("unknown channel type")
            }
            channels.add(channel)
        }
    }

    private fun isMenuUrl(url: String): Boolean = url == HOME || url == TV || url == "$TV#fta"

    inner class Bridge {
        @JavascriptInterface
        fun getChannels(): String {
            val arr = JSONArray()
            for (i in channels.indices) {
                val c = channels[i]
                arr.put(JSONObject().put("i", i).put("name", displayName(c.getString("name")))
                    .put("group", c.getString("group")).put("logo", c.optString("logo")).put("lcn", c.optInt("lcn", 0)))
            }
            return JSONObject().put("count", channels.size)
                .put("channels", arr).put("groups", JSONArray(GROUPS)).toString()
        }

        @JavascriptInterface
        fun getSchedule(): String = schedule

        @JavascriptInterface
        fun play(i: Int) {
            if (i < 0 || i >= channels.size) return
            val c = channels[i]
            runOnUiThread {
                val it = if (c.getString("type") == "stream")
                    Intent(this@MainActivity, PlayerActivity::class.java).putExtra("url", c.getString("stream_url"))
                else
                    Intent(this@MainActivity, ChannelPageActivity::class.java).putExtra("page_url", c.getString("page_url"))
                it.putExtra("title", displayName(c.getString("name")))
                startActivity(it)
            }
        }
    }

    // Gamepad (Xbox etc.): A/X/Start -> Enter (select), B -> Back
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val mapped = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_A,
            android.view.KeyEvent.KEYCODE_BUTTON_X,
            android.view.KeyEvent.KEYCODE_BUTTON_START,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER -> android.view.KeyEvent.KEYCODE_ENTER
            android.view.KeyEvent.KEYCODE_BUTTON_B -> android.view.KeyEvent.KEYCODE_BACK
            else -> -1
        }
        if (mapped == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) onBackPressed()
            return true
        }
        if (mapped != -1) {
            return super.dispatchKeyEvent(android.view.KeyEvent(event.downTime, event.eventTime,
                event.action, mapped, event.repeatCount, event.metaState))
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (web.url?.startsWith(TV) == true) web.loadUrl(HOME) // Live TV or Free to Air TV -> Home
        else super.onBackPressed()
    }
}
