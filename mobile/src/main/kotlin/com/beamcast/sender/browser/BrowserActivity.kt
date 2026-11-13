package com.beamcast.sender.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.beamcast.protocol.LoadRequest
import com.beamcast.sender.R
import com.beamcast.sender.cast.CastClient
import com.beamcast.sender.cast.CastSession
import com.beamcast.sender.data.Entry
import com.beamcast.sender.data.Store
import com.beamcast.sender.data.UrlInput
import com.beamcast.sender.databinding.ActivityBrowserBinding
import com.beamcast.sender.media.MediaClassifier
import com.beamcast.sender.media.MediaKind
import com.beamcast.sender.ui.DevicePicker
import com.beamcast.sender.ui.applySystemBarsPadding
import com.beamcast.sender.ui.castLocalFile
import com.beamcast.sender.ui.castRequest
import com.beamcast.sender.ui.openTvRemote
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {
    private lateinit var b: ActivityBrowserBinding
    private lateinit var store: Store
    private lateinit var detector: MediaDetector
    private var detected: List<DetectedMedia> = emptyList()
    private var desktop = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) castLocalFile(uri)
    }
    private val entries = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(EntriesActivity.EXTRA_URL)?.let { b.web.loadUrl(it) }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarsPadding()
        store = Store.get(this)
        setSupportActionBar(b.toolbar)

        detector = MediaDetector { list ->
            detected = list
            b.fab.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            b.fab.text = list.size.toString()
        }
        setupWebView()
        b.fab.setOnClickListener { showMediaSheet() }
        b.address.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                UrlInput.resolve(v.text.toString())?.let { b.web.loadUrl(it) }
                v.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    b.web.canGoBack() -> b.web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CastSession.device.collect { invalidateOptionsMenu() } }
        }
        store.lastDevice?.let { d ->
            if (CastSession.device.value == null) lifecycleScope.launch {
                runCatching { CastClient.info(d) }.onSuccess { if (CastSession.device.value == null) CastSession.device.value = d }
            }
        }

        if (savedInstanceState != null) {
            desktop = savedInstanceState.getBoolean("desktop")
            applyDesktop()
            b.web.restoreState(savedInstanceState)
        } else {
            val loaded = handleIntent(intent)
            if (!loaded) b.web.loadUrl(UrlInput.HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("desktop", desktop)
        b.web.saveState(outState)
    }

    override fun onDestroy() {
        b.web.destroy()
        super.onDestroy()
    }

    /** Returns true if a page load was started. Direct media links go straight to the cast flow. */
    private fun handleIntent(intent: Intent): Boolean {
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { UrlInput.extractUrl(it) ?: UrlInput.resolve(it) }
            else -> null
        } ?: return false
        if (MediaClassifier.classify(url) != null) {
            castRequest(
                LoadRequest(
                    url = url,
                    title = url.substringBefore('?').substringAfterLast('/').ifBlank { null },
                    mimeType = MediaClassifier.guessMime(url),
                ),
            )
            return false
        }
        b.web.loadUrl(url)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        val web = b.web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
        }
        detector.userAgent = web.settings.userAgentString
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "BeamBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                detector.onRequest(request.url.toString(), request.requestHeaders, request.isForMainFrame)
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme?.lowercase()
                return s != "http" && s != "https" && s != "file" && s != "about"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                detector.pageUrl = url
                if (!b.address.hasFocus()) b.address.setText(url)
                b.progress.visibility = View.VISIBLE
                invalidateOptionsMenu()
            }

            override fun onPageFinished(view: WebView, url: String) {
                detector.pageUrl = url
                view.evaluateJavascript(SCAN_JS, null)
                if (MediaClassifier.isHttp(url)) {
                    store.history.add(Entry(view.title.orEmpty(), url, System.currentTimeMillis()))
                    store.saveHistory()
                }
                invalidateOptionsMenu()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progress.setProgressCompat(newProgress, true)
                if (newProgress >= 100) b.progress.visibility = View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                b.fullscreen.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                b.fullscreen.visibility = View.VISIBLE
                b.main.visibility = View.GONE
                WindowInsetsControllerCompat(window, b.root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() = exitFullscreen()
        }
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        b.fullscreen.removeView(v)
        b.fullscreen.visibility = View.GONE
        b.main.visibility = View.VISIBLE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowInsetsControllerCompat(window, b.root).show(WindowInsetsCompat.Type.systemBars())
    }

    private inner class Bridge {
        @JavascriptInterface
        fun report(url: String?, tag: String?) {
            if (url != null) detector.onReported(url, tag ?: "video")
        }
    }

    private fun applyDesktop() {
        b.web.settings.apply {
            userAgentString = if (desktop) DESKTOP_UA else null
            useWideViewPort = desktop
            loadWithOverviewMode = desktop
        }
        detector.userAgent = b.web.settings.userAgentString
    }

    // ---- menu ----
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browser_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_back).isEnabled = b.web.canGoBack()
        menu.findItem(R.id.action_forward).isEnabled = b.web.canGoForward()
        menu.findItem(R.id.action_desktop).isChecked = desktop
        val url = b.web.url.orEmpty()
        menu.findItem(R.id.action_bookmark).setTitle(
            if (store.bookmarks.contains(url)) R.string.bookmark_remove else R.string.bookmark_add,
        )
        val connected = CastSession.device.value != null
        menu.findItem(R.id.action_cast).apply {
            setIcon(if (connected) R.drawable.ic_cast_connected else R.drawable.ic_cast)
            title = CastSession.device.value?.let { getString(R.string.connected_to, it.name) } ?: getString(R.string.action_cast)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_back -> b.web.goBack()
            R.id.action_forward -> b.web.goForward()
            R.id.action_reload -> b.web.reload()
            R.id.action_cast -> DevicePicker.show(this) { }
            R.id.action_media -> showMediaSheet()
            R.id.action_tv_remote -> openTvRemote()
            R.id.action_desktop -> { desktop = !desktop; applyDesktop(); b.web.reload(); invalidateOptionsMenu() }
            R.id.action_bookmark -> toggleBookmark()
            R.id.action_bookmarks -> entries.launch(Intent(this, EntriesActivity::class.java))
            R.id.action_history -> entries.launch(Intent(this, EntriesActivity::class.java).putExtra(EntriesActivity.EXTRA_HISTORY, true))
            R.id.action_local -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                pickFile.launch(arrayOf("video/*", "audio/*", "image/*"))
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleBookmark() {
        val url = b.web.url ?: return
        if (store.bookmarks.contains(url)) {
            store.bookmarks.remove(url)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            store.bookmarks.add(Entry(b.web.title.orEmpty(), url, System.currentTimeMillis()))
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
        store.saveBookmarks()
        invalidateOptionsMenu()
    }

    // ---- detected media sheet ----
    private fun showMediaSheet() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_media, null)
        val container = view.findViewById<ViewGroup>(R.id.items)
        view.findViewById<TextView>(R.id.empty).visibility = if (detected.isEmpty()) View.VISIBLE else View.GONE
        for (m in MediaClassifier.sortByQuality(detected) { it.url }) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_media, container, false)
            val kindLabel = getString(
                when (m.kind) {
                    MediaKind.HLS -> R.string.kind_hls
                    MediaKind.DASH -> R.string.kind_dash
                    MediaKind.VIDEO -> R.string.kind_video
                    MediaKind.AUDIO -> R.string.kind_audio
                },
            )
            val quality = when {
                MediaClassifier.isAdaptiveMaster(m.url) -> getString(R.string.quality_best)
                else -> MediaClassifier.qualityHint(m.url)?.let { "${it}p" }
            }
            row.findViewById<TextView>(R.id.kind).text = if (quality != null) "$kindLabel · $quality" else kindLabel
            row.findViewById<TextView>(R.id.host).text = UrlInput.host(m.url) ?: ""
            row.findViewById<TextView>(R.id.url).text = UrlInput.shorten(m.url, 70)
            row.findViewById<View>(R.id.cast).setOnClickListener {
                sheet.dismiss()
                castRequest(LoadRequest(url = m.url, title = b.web.title, mimeType = m.mime, headers = m.headers))
            }
            row.findViewById<View>(R.id.copy).setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("url", m.url))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            container.addView(row)
        }
        sheet.setContentView(view)
        sheet.show()
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val SCAN_JS = """
            (function(){
              if (window.__bcScan) { window.__bcScan(); return; }
              window.__bcScan = function(){
                try {
                  document.querySelectorAll('video,audio,source').forEach(function(e){
                    var s = e.currentSrc || e.src;
                    if (!s || s.indexOf('http') !== 0) return;
                    var p = e.parentElement;
                    var t = (e.tagName === 'AUDIO' || (p && p.tagName === 'AUDIO')) ? 'audio' : 'video';
                    BeamBridge.report(s, t);
                  });
                } catch (x) {}
              };
              window.__bcScan();
              var n = 0;
              var i = setInterval(function(){ window.__bcScan(); if (++n > 15) clearInterval(i); }, 2000);
            })();
        """
    }
}
