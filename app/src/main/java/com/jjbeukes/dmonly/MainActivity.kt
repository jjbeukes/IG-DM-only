package com.jjbeukes.dmonly

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * The whole app: one WebView showing instagram.com's mobile site, fenced in by [UrlPolicy] and
 * cleaned up by `assets/dmonly.js`, under a native bottom bar (Chats, Search, Profile, Settings).
 */
class MainActivity : AppCompatActivity(), DmWebViewClient.Host {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var client: DmWebViewClient
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var offline: View
    private lateinit var bridge: WebBridge
    private lateinit var backCallback: OnBackPressedCallback

    private val main = Handler(Looper.getMainLooper())

    /** URL of the page on screen, as last committed. Read from the JavaBridge thread too. */
    @Volatile
    private var currentUrl: String? = null

    /** Settings as they were when the page was loaded, to notice changes on return from Settings. */
    private var loadedSnapshot = ""

    private var imeVisible = false
    private var currentRoute = UrlPolicy.Route.OTHER

    /** After logging out, the history from the old session is dropped once the new page commits. */
    private var clearHistoryOnNextPage = false

    /** Profile tab tapped before the username was known; open it once the script reports it. */
    private var profileRequested = false
    private val profileTimeout = Runnable { onProfileLookupFailed() }
    private var usernameDialog: AlertDialog? = null

    private var lastBlockedToast = 0L

    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var pendingMedia: PermissionRequest? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingFiles?.onReceiveValue(pickedUris(result.resultCode, result.data))
            pendingFiles = null
        }

    private val mediaPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingMedia?.let(::answerMediaRequest)
            pendingMedia = null
        }

    override val policyOptions: UrlPolicy.Options
        get() = prefs.policyOptions()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        webView = findViewById(R.id.webview)
        bottomNav = findViewById(R.id.bottom_nav)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        findViewById<View>(R.id.offline_retry).setOnClickListener { retry() }

        applyWindowInsets()
        setUpBottomNav()
        setUpWebView()

        // After a renderer crash the saved state points at the page that crashed it. Restore it once;
        // if it crashes again straight away, start from the inbox instead of looping.
        val restore = savedInstanceState != null && !skipRestoreOnce
        skipRestoreOnce = false
        val restored = restore && webView.restoreState(savedInstanceState!!) != null
        if (!restored) webView.loadUrl(UrlPolicy.INBOX_URL)
        loadedSnapshot = prefs.pageAffectingSnapshot()

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val action = backAction()
                if (action != null) {
                    action()
                } else {
                    // Things changed since the callback was last updated: let the system handle it.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
                updateBackCallback()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    // ---------------------------------------------------------------------------- layout

    /**
     * Edge-to-edge (enforced from Android 15): pad the root for the status bar, cutout and
     * navigation bar, or for the keyboard while it is open, so the message box sits right above it.
     * Children get those insets zeroed (Insets.NONE, not consumed, so WebView still hears about
     * changes) to avoid double padding.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val bars = insets.getInsets(types)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, if (imeVisible) ime.bottom else 0))
            updateBottomNavVisibility()
            WindowInsetsCompat.Builder(insets)
                .setInsets(types, Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
        }
    }

    private fun setUpBottomNav() {
        bottomNav.setOnItemSelectedListener { item -> onTab(item.itemId, reselected = false) }
        bottomNav.setOnItemReselectedListener { item -> onTab(item.itemId, reselected = true) }
    }

    /** @return whether the tab should show as selected. */
    private fun onTab(id: Int, reselected: Boolean): Boolean {
        return when (id) {
            R.id.nav_messages -> {
                open(UrlPolicy.INBOX_URL, spaPath = "/direct/inbox/", forceReload = reselected && currentRoute == UrlPolicy.Route.INBOX)
                true
            }
            R.id.nav_search -> {
                open(UrlPolicy.SEARCH_URL, spaPath = null, forceReload = reselected)
                true
            }
            R.id.nav_profile -> openOwnProfile(reselected)
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                false
            }
            else -> false
        }
    }

    private fun updateBottomNavVisibility() {
        if (!::bottomNav.isInitialized) return
        val hidden = imeVisible ||
            customView != null ||
            currentRoute == UrlPolicy.Route.THREAD ||
            currentRoute == UrlPolicy.Route.AUTH
        bottomNav.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    /** Highlight the tab that matches the page, without triggering navigation. */
    private fun syncSelectedTab(url: String) {
        val id = when (currentRoute) {
            UrlPolicy.Route.INBOX, UrlPolicy.Route.THREAD, UrlPolicy.Route.DM -> R.id.nav_messages
            UrlPolicy.Route.SEARCH -> R.id.nav_search
            UrlPolicy.Route.PROFILE, UrlPolicy.Route.FOLLOW_LIST ->
                if (prefs.username != null && UrlPolicy.profileUsername(url)?.lowercase() == prefs.username) {
                    R.id.nav_profile
                } else {
                    return // someone else's profile: keep whichever tab led here
                }
            else -> return
        }
        bottomNav.menu.findItem(id)?.isChecked = true
    }

    // --------------------------------------------------------------------------- WebView

    // JavaScript is the site itself: instagram.com renders nothing without it. The WebView only
    // ever stays on pages UrlPolicy allows.
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setUpWebView() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // chrome://inspect on debug builds, for when Instagram changes its markup.
            WebView.setWebContentsDebuggingEnabled(true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Meta's login chain (captcha and identity-check frames) sets cookies from other sites.
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.page_background))

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Nothing on the file system is any business of a web page. Content access stays on:
            // content:// URIs are what the photo picker hands back.
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Left at the default (true): nothing plays until tapped.
            mediaPlaybackRequiresUserGesture = true
            // window.open and target=_blank become ordinary navigations, so they hit the policy.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = UserAgent.chromeLike(userAgentString)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Instagram has its own dark mode, driven by prefers-color-scheme (DayNight theme).
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }

        bridge = WebBridge(
            prefs = prefs,
            currentUrl = { currentUrl },
            onUsername = ::onUsernameKnown,
            onBlocked = ::showBlockedToast,
            onStuck = { client.showStuck(webView) },
        )
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)

        val script = assets.open("dmonly.js").bufferedReader().use { it.readText() }
        val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart) {
            // Must be registered before the first load. Runs before any of the page's own scripts.
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(UrlPolicy.ORIGIN))
        }

        client = DmWebViewClient(this, fallbackScript = if (documentStart) null else script)
        webView.webViewClient = client
        webView.webChromeClient = DmChromeClient()
    }

    private fun open(url: String, spaPath: String?, forceReload: Boolean) {
        hideOffline()
        if (forceReload || spaPath == null || !UrlPolicy.isBackTarget(currentUrl, policyOptions)) {
            webView.loadUrl(url)
            return
        }
        // Prefer navigating inside the single-page app (instant) by clicking Instagram's own link.
        val quoted = org.json.JSONObject.quote(spaPath)
        webView.evaluateJavascript("(window.__dmoGo && window.__dmoGo($quoted)) || false") { result ->
            if (result != "true") webView.loadUrl(url)
        }
    }

    /** @return whether the Profile tab should show as selected now. */
    private fun openOwnProfile(reselected: Boolean): Boolean {
        val username = prefs.username
        val url = UrlPolicy.profileUrl(username)
        if (url != null) {
            open(url, spaPath = "/$username/", forceReload = reselected)
            return true
        }
        // Not known yet: ask the page (Instagram's own tab bar, then its web API). The tab is
        // selected once the profile actually opens.
        profileRequested = true
        webView.evaluateJavascript("window.__dmoLookupUsername && window.__dmoLookupUsername()", null)
        main.removeCallbacks(profileTimeout)
        main.postDelayed(profileTimeout, PROFILE_LOOKUP_TIMEOUT_MS)
        return false
    }

    private fun onUsernameKnown(username: String) {
        val dialogOpen = usernameDialog?.isShowing == true
        if (!profileRequested && !dialogOpen) return
        profileRequested = false
        main.removeCallbacks(profileTimeout)
        usernameDialog?.dismiss()
        usernameDialog = null
        UrlPolicy.profileUrl(username)?.let { open(it, spaPath = "/$username/", forceReload = false) }
    }

    private fun onProfileLookupFailed() {
        if (!profileRequested || isFinishing) return
        profileRequested = false
        askForUsername()
    }

    private fun askForUsername() {
        val input = EditText(this).apply {
            hint = getString(R.string.username_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        usernameDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.username_dialog_title)
            .setMessage(R.string.username_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val url = UrlPolicy.profileUrl(input.text.toString())
                if (url == null) {
                    Toast.makeText(this, R.string.invalid_username, Toast.LENGTH_SHORT).show()
                } else {
                    prefs.username = input.text.toString()
                    webView.loadUrl(url)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { usernameDialog = null }
            .show()
    }

    private fun showBlockedToast() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockedToast < BLOCKED_TOAST_INTERVAL_MS) return
        lastBlockedToast = now
        Toast.makeText(this, R.string.blocked_toast, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- DmWebViewClient.Host

    override fun openExternal(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https", "mailto", "tel", "sms", "smsto", "geo")) return
        // Never hand Instagram (or a malformed web URL a browser might "fix" into Instagram) to the
        // system: App Links would open the real Instagram app.
        val host = uri.host.orEmpty()
        if ((scheme == "http" || scheme == "https") && (host.isBlank() || UrlPolicy.isInstagramFamilyHost(host))) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.external_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAllowedPage(url: String, failed: Boolean) {
        currentUrl = url
        currentRoute = UrlPolicy.route(url)
        // Chromium's error page for a failed load also "commits" under the failed URL; keep the
        // Retry screen up for it.
        if (!failed) hideOffline()
        if (clearHistoryOnNextPage && !failed) {
            clearHistoryOnNextPage = false
            webView.post { webView.clearHistory() }
        }
        updateBottomNavVisibility()
        syncSelectedTab(url)
        updateBackCallback()
    }

    override fun onPageLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onMainFrameError() {
        offline.visibility = View.VISIBLE
        progress.visibility = View.GONE
        updateBackCallback()
    }

    override fun onRendererGone() {
        // The renderer died (out of memory, or a WebView update). This WebView can't be used again;
        // rebuild the activity, which destroys it and starts over with a fresh one.
        val now = SystemClock.elapsedRealtime()
        if (lastRendererDeath != 0L && now - lastRendererDeath < RENDERER_CRASH_WINDOW_MS) skipRestoreOnce = true
        lastRendererDeath = now
        recreate()
    }

    override fun onStuck() {
        currentRoute = UrlPolicy.Route.OTHER
        hideOffline()
        updateBottomNavVisibility()
        updateBackCallback()
    }

    private fun retry() {
        hideOffline()
        val target = currentUrl?.takeIf { UrlPolicy.isAllowed(it, policyOptions) } ?: UrlPolicy.INBOX_URL
        webView.loadUrl(target)
    }

    private fun hideOffline() {
        if (offline.visibility != View.GONE) {
            offline.visibility = View.GONE
            if (::backCallback.isInitialized) updateBackCallback()
        }
    }

    // ------------------------------------------------------------------------------ back

    /**
     * What Back should do here, or null to leave it to the system (which closes the app).
     *
     * Back never steps blindly through WebView history: it skips refused pages and (once logged in)
     * login pages, and the inbox is home. Keeping the callback disabled whenever this is null lets
     * Android 16+ show the predictive back-to-home animation.
     */
    private fun backAction(): (() -> Unit)? {
        if (customView != null) return { hideCustomView() }

        val current = webView.url ?: return null
        if (UrlPolicy.route(current) == UrlPolicy.Route.INBOX) return null

        // While logging in, Back may return to an earlier login step (e.g. from 2FA to the form).
        val onAuthPage = UrlPolicy.isAuthPage(current)
        val history = webView.copyBackForwardList()
        var index = history.currentIndex - 1
        while (index >= 0 && !UrlPolicy.isBackTarget(history.getItemAtIndex(index)?.url, policyOptions, allowAuth = onAuthPage)) {
            index--
        }
        if (index >= 0) {
            val steps = index - history.currentIndex
            return {
                hideOffline()
                webView.goBackOrForward(steps)
            }
        }
        // Nothing to go back to: leave from login pages and from the "keeps redirecting" page,
        // otherwise go home to the inbox.
        if (onAuthPage || !current.startsWith("http")) return null
        return {
            hideOffline()
            webView.loadUrl(UrlPolicy.INBOX_URL)
        }
    }

    private fun updateBackCallback() {
        if (!::backCallback.isInitialized) return
        backCallback.isEnabled = backAction() != null
    }

    // ---------------------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        webView.onResume()

        if (prefs.pendingReset) {
            prefs.pendingReset = false
            resetSession()
            return
        }

        prefs.consumePendingOpenUrl()?.let { url ->
            bridge.refresh()
            loadedSnapshot = prefs.pageAffectingSnapshot()
            webView.loadUrl(url)
            return
        }

        val snapshot = prefs.pageAffectingSnapshot()
        if (snapshot != loadedSnapshot) {
            // A setting the page depends on changed: reload with the new rules.
            loadedSnapshot = snapshot
            bridge.refresh()
            val target = currentUrl?.takeIf { UrlPolicy.isAllowed(it, policyOptions) } ?: UrlPolicy.INBOX_URL
            webView.loadUrl(target)
        }
    }

    /**
     * Log out: leave the Instagram page first (so it can't write anything back), then wipe cookies,
     * site storage and cache, and only then load the inbox, which lands on the login page. History
     * from the old session is cleared once the new page has committed.
     */
    private fun resetSession() {
        bridge.refresh()
        loadedSnapshot = prefs.pageAffectingSnapshot()
        currentUrl = null
        webView.stopLoading()
        webView.loadUrl("about:blank")
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        clearHistoryOnNextPage = true
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            if (!isDestroyed) webView.loadUrl(UrlPolicy.INBOX_URL)
        }
    }

    override fun onPause() {
        webView.onPause()
        // Persist the session now rather than whenever the engine gets round to it.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        usernameDialog?.dismiss()
        pendingFiles?.onReceiveValue(null)
        pendingFiles = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------ full-screen video

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        (window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        updateBottomNavVisibility()
        updateBackCallback()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        (window.decorView as ViewGroup).removeView(view)
        customView = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        updateBottomNavVisibility()
        updateBackCallback()
    }

    // ------------------------------------------------------------------------ chrome client

    private inner class DmChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.setProgressCompat(newProgress, true)
        }

        /** "(3) Instagram" means three unread conversations: show it on the Chats tab. */
        override fun onReceivedTitle(view: WebView, title: String?) {
            val unread = title?.let { UNREAD_TITLE.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            if (unread > 0) {
                bottomNav.getOrCreateBadge(R.id.nav_messages).number = unread
            } else {
                bottomNav.removeBadge(R.id.nav_messages)
            }
        }

        /** Attaching photos or videos to a message. */
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            // A chooser still open from before would never be answered; answer it empty.
            pendingFiles?.onReceiveValue(null)
            pendingFiles = filePathCallback
            return try {
                filePicker.launch(pickerIntent(fileChooserParams))
                true
            } catch (_: ActivityNotFoundException) {
                pendingFiles = null
                false
            }
        }

        /**
         * The microphone for voice messages, only for Instagram's own pages. The camera only for
         * Meta's selfie identity check (www.fbsbx.com), which can appear while logging in.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = request.resources.filter { resource -> permissionFor(resource, request.origin) != null }
            if (wanted.isEmpty()) {
                request.deny()
                return
            }
            val needed = wanted.mapNotNull { permissionFor(it, request.origin) }.distinct().filterNot(::isGranted)
            if (needed.isEmpty()) {
                answerMediaRequest(request)
            } else {
                pendingMedia?.deny()
                pendingMedia = request
                mediaPermissions.launch(needed.toTypedArray())
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingMedia == request) pendingMedia = null
        }

        /** Full-screen video (someone's own video, opened full screen). */
        override fun onShowCustomView(view: View, callback: CustomViewCallback) = showCustomView(view, callback)

        override fun onHideCustomView() = hideCustomView()
    }

    /** Grant the page whatever the app now holds for this origin, and nothing else. */
    private fun answerMediaRequest(request: PermissionRequest) {
        val granted = request.resources.filter { resource ->
            permissionFor(resource, request.origin)?.let(::isGranted) == true
        }
        if (granted.isEmpty()) {
            request.deny()
            if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
            }
        } else {
            request.grant(granted.toTypedArray())
        }
    }

    private fun permissionFor(resource: String, origin: Uri?): String? {
        val host = origin?.host?.lowercase() ?: return null
        if (origin.scheme != "https") return null
        return when {
            resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE && host == "www.instagram.com" -> Manifest.permission.RECORD_AUDIO
            resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE && host == "www.fbsbx.com" -> Manifest.permission.CAMERA
            else -> null
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val BRIDGE_NAME = "__dmoBridge"
        const val PROFILE_LOOKUP_TIMEOUT_MS = 6_000L
        const val BLOCKED_TOAST_INTERVAL_MS = 3_000L
        const val RENDERER_CRASH_WINDOW_MS = 60_000L
        val UNREAD_TITLE = Regex("""^\((\d+)\)""")

        /** When the WebView renderer last died in this process, to notice a crash loop. */
        var lastRendererDeath = 0L

        /** Set after a repeated renderer crash: the next activity starts fresh instead of restoring. */
        var skipRestoreOnce = false

        /**
         * The file picker, built by hand: FileChooserParams.createIntent squeezes the page's
         * image-and-video accept list into a single MIME type no picker handles, and ignores
         * multi-select.
         */
        fun pickerIntent(params: WebChromeClient.FileChooserParams): Intent {
            val types = params.acceptTypes
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.contains('/') }
                .distinct()
            return Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = types.singleOrNull() ?: "*/*"
                if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            }
        }

        /** Every picked file, including multi-select (FileChooserParams.parseResult reads only one). */
        fun pickedUris(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != android.app.Activity.RESULT_OK || data == null) return null
            val clip = data.clipData
            val uris = if (clip != null) {
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            } else {
                listOfNotNull(data.data)
            }
            return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        }
    }
}
