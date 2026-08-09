package com.techno.roofing

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Single-screen wrapper around the web app described by [AppConfig].
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    /**
     * In-flight `<input type="file">` request. The WebView keeps the file input
     * unusable until this callback is answered, so it must be called exactly once —
     * including when the picker is cancelled or the activity goes away.
     */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** Whether the page currently loading failed, so the error view can be cleared. */
    private var loadFailed = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Denial only stops notifications from being displayed; the FCM token is
            // still issued, so the device stays addressable.
            Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        findViewById<View>(R.id.retryButton).setOnClickListener { retry() }

        applyWindowInsets()
        registerFileChooser()
        registerBackHandler()
        configureWebView()

        AppMessagingService.ensureNotificationChannel(this)
        logFcmToken()

        val pushTarget = pushTargetUrl(intent)
        when {
            // A cold start from a notification tap goes straight to its destination.
            pushTarget != null -> webView.loadUrl(pushTarget)
            // restoreState returns null when the bundle holds no WebView history.
            savedInstanceState == null || webView.restoreState(savedInstanceState) == null ->
                webView.loadUrl(AppConfig.BASE_URL)
        }

        // Last, so the page load is already under way. launch() only queues the system
        // dialog and returns immediately, so the WebView keeps loading behind it.
        requestNotificationPermissionIfNeeded()
    }

    /** Delivered instead of [onCreate] when a notification is tapped while running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushTargetUrl(intent)?.let { webView.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Pauses drawing and animations. Deliberately not pauseTimers(): that is
        // process-global and would stall the Supabase realtime socket's keep-alive.
        webView.onPause()
    }

    override fun onStop() {
        super.onStop()
        // The WebView flushes cookies on its own schedule, so a process kill shortly
        // after signing in can lose them. Forcing a flush here makes that impossible.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        // An open picker outliving the activity would leave the callback unanswered,
        // pinning a reference to a destroyed WebView.
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        // Detach before destroy() so the view tree holds no reference to a dead WebView.
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Shows the standard system permission dialog once, on the first launch that can
     * still ask for it.
     *
     * POST_NOTIFICATIONS only exists on Android 13 (API 33) and above. On Android 12 and
     * lower notifications are granted at install time, so nothing is ever requested and
     * existing behaviour is untouched.
     *
     * The asked-once flag is what stops the prompt reappearing on every launch: Android
     * would otherwise still surface a real dialog on the second attempt after a single
     * denial. Once the flag is set the request is never repeated, which also covers the
     * permanently-denied state — the user turns notifications on from Android settings
     * from that point on.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)) return
        prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true) }

        notificationPermissionLauncher.launch(permission)
    }

    /**
     * FCM auto-init already generates a token on first launch; this reads it back so it
     * can be verified in logcat. Ongoing refreshes surface in
     * [AppMessagingService.onNewToken] instead.
     *
     * Debug builds only. A registration token is effectively a send credential for this
     * device, so it must never reach release logs. R8 folds the constant and strips the
     * whole body from release, so no token ever reaches the shipped binary.
     */
    private fun logFcmToken() {
        if (!BuildConfig.DEBUG) return
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "FCM registration token: ${task.result}")
            } else {
                Log.d(TAG, "FCM registration token unavailable", task.exception)
            }
        }
    }

    /**
     * Destination carried by a notification tap, or null if there is none.
     *
     * A push payload is untrusted input, so anything outside the web app is discarded
     * rather than loaded — otherwise a spoofed send could point the WebView anywhere.
     */
    private fun pushTargetUrl(intent: Intent): String? {
        // Two sources, because the two delivery paths differ: EXTRA_PUSH_URL is set by
        // AppMessagingService for foreground sends, while a background tray notification
        // is launched by the FCM SDK with the data payload's own keys as extras.
        val target = intent.getStringExtra(AppMessagingService.EXTRA_PUSH_URL)
            ?: intent.getStringExtra(AppMessagingService.DATA_URL)
            ?: return null
        if (!AppConfig.isInternalUrl(target.toUri())) {
            Log.d(TAG, "Ignored push destination outside the web app: $target")
            return null
        }
        return target
    }

    /**
     * targetSdk 36 forces edge-to-edge, so the window insets have to be turned into
     * padding by hand. The bottom also tracks the keyboard, otherwise a focused web
     * form field can end up behind it.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            // Holds the signed-in Supabase session, so this is what keeps the user
            // logged in across app restarts.
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppWebChromeClient()
        webView.setDownloadListener { url, _, _, _, _ -> openExternally(url.toUri()) }
    }

    private fun registerFileChooser() {
        fileChooserLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val callback = fileChooserCallback ?: return@registerForActivityResult
                fileChooserCallback = null
                // parseResult yields null for a cancelled picker, which is what the
                // WebView expects in that case.
                callback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                )
            }
    }

    /** Back navigates web history first, and only leaves the app once it is exhausted. */
    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError() {
        progressBar.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun retry() {
        errorView.visibility = View.GONE
        loadFailed = false
        if (webView.url == null) webView.loadUrl(AppConfig.BASE_URL) else webView.reload()
    }

    private inner class AppWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url

            // Part of the web app or its backend.
            if (AppConfig.isInternalUrl(url)) return false

            // tel:, mailto:, whatsapp:, geo:, intent:... — the WebView cannot load these.
            if (!AppConfig.isWebScheme(url)) {
                openExternally(url)
                return true
            }

            // An outside website. Hand it over only when the user actually tapped it:
            // a gesture-less navigation is a redirect hop (OAuth handoff, email link,
            // payment return) that has to stay in-app or the flow can never come back.
            if (request.hasGesture()) {
                openExternally(url)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loadFailed = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            progressBar.visibility = View.GONE
            // Clears a stale error screen once any navigation succeeds.
            if (loadFailed) showError() else errorView.visibility = View.GONE
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            // Ignore failed sub-resources; only a failed page load is worth showing.
            if (request.isForMainFrame) loadFailed = true
        }
    }

    private inner class AppWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }
    }

    private companion object {
        const val TAG = "TechnoRoofingFcm"

        const val PREFS_NAME = "techno_roofing_prefs"

        /** Set once the system dialog has been shown, so it is never shown again. */
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    }
}
