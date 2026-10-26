package com.jjbeukes.dmonly

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * The injected script's line to the app, exposed to the page as `window.__dmoBridge`.
 *
 * A JavascriptInterface rather than a message channel because the script needs *synchronous*
 * answers: a tap on a link has to be cancelled inside the click handler, before Instagram's router
 * sees it. Every method is harmless if called by something other than our script: two of them
 * only answer questions, and [reportUsername] is ignored unless the main frame is on instagram.com.
 *
 * Methods run on WebView's JavaBridge thread, not the main thread.
 */
class WebBridge(
    private val prefs: Prefs,
    private val currentUrl: () -> String?,
    private val onUsername: (String) -> Unit,
    private val onBlocked: () -> Unit,
    private val onStuck: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var options = prefs.policyOptions()

    @Volatile
    private var config = prefs.scriptConfig()

    /** Re-read settings; call on the main thread after they change. */
    fun refresh() {
        options = prefs.policyOptions()
        config = prefs.scriptConfig()
    }

    /** "allow", "block", "external" or "drop" for [url]. */
    @JavascriptInterface
    fun decide(url: String?): String = when (UrlPolicy.decide(url ?: return "drop", options)) {
        UrlPolicy.Decision.Allow -> "allow"
        is UrlPolicy.Decision.Block -> "block"
        is UrlPolicy.Decision.External -> "external"
        UrlPolicy.Decision.Drop -> "drop"
    }

    /** The route id ("inbox", "thread", "search", "profile"...) for [url]. */
    @JavascriptInterface
    fun route(url: String?): String = UrlPolicy.route(url).id

    /** Settings the script needs, as JSON. */
    @JavascriptInterface
    fun config(): String = config

    /** The script refused something the user tapped; let them know why nothing happened. */
    @JavascriptInterface
    fun blocked() {
        main.post(onBlocked)
    }

    /** The script has bounced too often in a short time; stop and show the "keeps redirecting" page. */
    @JavascriptInterface
    fun stuck() {
        main.post {
            if (UrlPolicy.ParsedUrl.parse(currentUrl() ?: return@post)?.host?.endsWith("instagram.com") == true) onStuck()
        }
    }

    /** The logged-in user's username, read from Instagram's own nav bar or API. */
    @JavascriptInterface
    fun reportUsername(name: String?) {
        val username = UrlPolicy.normaliseUsername(name) ?: return
        main.post {
            if (UrlPolicy.ParsedUrl.parse(currentUrl() ?: return@post)?.host?.endsWith("instagram.com") != true) {
                return@post
            }
            if (prefs.username != username) prefs.username = username
            onUsername(username)
        }
    }
}
