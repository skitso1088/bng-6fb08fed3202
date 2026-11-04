package com.bumpngrind.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*

/** Untrusted channel pages have their own WebView, with no native JavaScript bridge. */
class ChannelPageActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var status: TextView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreen = false
    private lateinit var original: Uri
    private var padX = 0
    private var padY = 0
    private var splash: View? = null
    private lateinit var volume: AppVolume
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        /**
         * Runs in every frame (incl. the embedded player). window.bngVol is the volume: new videos take it, and a
         * {bngVol} message sets it and passes it on to child frames, since the player is usually in a cross-origin iframe.
         */
        private const val VOLUME_JS = """(function(){
function setAll(){var vs=document.getElementsByTagName('video');
for(var i=0;i<vs.length;i++){if(Math.abs(vs[i].volume-window.bngVol)>0.001)vs[i].volume=window.bngVol;}}
window.bngSet=function(l){window.bngVol=l;setAll();
for(var i=0;i<window.frames.length;i++){try{window.frames[i].postMessage({bngVol:l},'*');}catch(e){}}};
window.addEventListener('message',function(e){var l=e.data&&e.data.bngVol;
if(typeof l==='number'&&l>=0&&l<=1)window.bngSet(l);});
document.addEventListener('play',function(e){if(e.target&&e.target.tagName==='VIDEO')e.target.volume=window.bngVol;},true);
})();"""
        /** For the first 20s: press the player's Unmute, unmute videos and hold them at the chosen volume. */
        private const val UNMUTE_JS = """(function(){var n=0,t=setInterval(function(){
if(++n>40){clearInterval(t);return;}
var vs=document.getElementsByTagName('video');
for(var i=0;i<vs.length;i++){var v=vs[i];if(v.muted){v.muted=false;}if(Math.abs(v.volume-window.bngVol)>0.001){v.volume=window.bngVol;}}
if(!window.bngUnmuted){var b=document.querySelectorAll('button,[role=button],div,span,a'),hit=null;
for(var j=0;j<b.length;j++){var e=b[j],tx=(e.innerText||e.getAttribute('aria-label')||'');
if(tx.length<40&&/unmute/i.test(tx)&&e.offsetParent!==null)hit=e;}
if(hit){window.bngUnmuted=1;hit.click();}}
},500);})();"""
        /** Stretch the channel page's video player (#player / .player) over the whole screen. */
        private const val FILL_PLAYER_JS = """(function(){if(document.getElementById('bng-fs'))return;
var s=document.createElement('style');s.id='bng-fs';s.textContent=
'#player,.player{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;'+
'max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;border-radius:0!important;'+
'z-index:2147483646!important;background:#000!important;aspect-ratio:auto!important}'+
'#player iframe,.player iframe,#player video,.player video{width:100%!important;height:100%!important;border:0!important}'+
'html,body{overflow:hidden!important}';
(document.head||document.documentElement).appendChild(s);})();"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // no screensaver mid-match
        original = Uri.parse(intent.getStringExtra("page_url").orEmpty())
        if (original.scheme != "https" || original.host.isNullOrEmpty()) { finish(); return }
        volume = AppVolume(this) { web.evaluateJavascript("window.bngSet&&bngSet($it)", null) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val dp = resources.displayMetrics.density
        // Overscan-safe margin (~4% of screen) around the controls; removed in fullscreen.
        padX = (resources.displayMetrics.widthPixels * 0.04f).toInt()
        padY = (resources.displayMetrics.heightPixels * 0.04f).toInt()
        root.setPadding(padX, padY, padX, padY)
        toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun button(label: String, action: () -> Unit) = Button(this).also {
            it.text = label; it.isAllCaps = false; it.textSize = 14f
            it.minHeight = 0; it.minimumHeight = 0; it.minWidth = 0; it.minimumWidth = 0
            it.setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            it.setOnClickListener { action() }
            toolbar.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = (8 * dp).toInt() })
        }
        button("Back") { onBackPressed() }
        button("Browser") { openBrowser() }
        button("Fullscreen") { setFullscreen(true); web.requestFocus() }
        button("Volume") { volume.show() }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            text = "Loading… · MENU shows controls"
        }
        root.addView(toolbar); root.addView(status)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false // let stations start with sound
            setSupportMultipleWindows(true) // Popups are rejected by the default onCreateWindow handler.
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, "window.bngVol=${volume.level};" + VOLUME_JS + UNMUTE_JS + FILL_PLAYER_JS, setOf("*"))
        // Do not add a JavascriptInterface here, including for subframes or redirects.
        web.webViewClient = object : WebViewClient() {
            private fun blocked(uri: Uri): Boolean = uri.scheme != "https"
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = blocked(request.url)
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blocked(Uri.parse(url))
            override fun onPageFinished(view: WebView, url: String) {
                status.text = "${intent.getStringExtra("title")} · MENU controls · BACK returns"
                view.evaluateJavascript(FILL_PLAYER_JS, null)
                view.evaluateJavascript("window.bngSet&&bngSet(${volume.level})", null) // reaches frames that loaded after a change
                handler.postDelayed({ hideSplash() }, 1200) // let the full-screen player paint first
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError()
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) showError()
            }
            // Default SSL handling cancels invalid certificates; never proceed past TLS errors.
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view; customCallback = callback
                web.visibility = View.GONE
                root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                setFullscreen(true)
                view.requestFocus()
            }
            override fun onHideCustomView() { hideCustomView() }
        }
        // Web page with a BumpNGrind logo screen on top while the channel page loads.
        val stage = FrameLayout(this)
        stage.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash = Splash.build(this, intent.getStringExtra("title").orEmpty())
        stage.addView(splash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        handler.postDelayed({ hideSplash() }, 20000) // never hide the page forever
        setContentView(root)
        web.loadUrl(original.toString())
        setFullscreen(true) // stations open full screen; MENU shows the controls
        web.requestFocus()
    }


    private fun hideSplash() {
        val s = splash ?: return
        splash = null
        s.animate().alpha(0f).setDuration(300).withEndAction { (s.parent as? ViewGroup)?.removeView(s) }.start()
        web.requestFocus()
        volume.show() // shows where the volume is and which buttons change it
    }

    private fun showError() {
        hideSplash()
        setFullscreen(false)
        status.text = "Unable to load this page. Choose Browser to open the original channel."
        toolbar.getChildAt(1).requestFocus()
    }

    private fun openBrowser() {
        try { startActivity(Intent(Intent.ACTION_VIEW, original).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "No external browser installed", Toast.LENGTH_LONG).show() }
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        val (x, y) = if (enabled) 0 to 0 else padX to padY
        root.setPadding(x, y, x, y)
        toolbar.visibility = if (enabled) View.GONE else View.VISIBLE
        status.visibility = if (enabled) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (enabled)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
    }

    private fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        val callback = customCallback; customCallback = null
        callback?.onCustomViewHidden()
        web.visibility = View.VISIBLE
        setFullscreen(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullscreen && ::volume.isInitialized && volume.handleKey(event)) return true // the toolbar needs Up/Down
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) {
                setFullscreen(false); toolbar.getChildAt(0).requestFocus()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            !fullscreen -> { setFullscreen(true); web.requestFocus() }
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::volume.isInitialized) volume.release()
        if (::web.isInitialized) {
            hideCustomView(); web.stopLoading(); root.removeView(web); web.destroy()
        }
        super.onDestroy()
    }
}
