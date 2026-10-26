package com.jjbeukes.dmonly

import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Routes every main-frame navigation through [UrlPolicy].
 *
 * Instagram is a single-page app, so there are two ways to change page and both are covered:
 *
 * - A real navigation (full page load, server redirect) reaches [shouldOverrideUrlLoading]
 *   *before* it happens, so refusing it simply means it never does.
 * - A history.pushState change (most taps inside the site) never asks. WebView reports it through
 *   [doUpdateVisitedHistory] *after* the URL has changed, so a refusal there has to back out again.
 *   The injected script cancels most of these before they happen; this is the backstop.
 */
class DmWebViewClient(
    private val host: Host,
    /** Injected on page start/finish when document-start scripts aren't supported; else null. */
    private val fallbackScript: String?,
) : WebViewClient() {

    interface Host {
        val policyOptions: UrlPolicy.Options
        fun openExternal(url: String)

        /** An allowed page committed. [failed] when it is Chromium's error page for a failed load. */
        fun onAllowedPage(url: String, failed: Boolean)
        fun onPageLoading(loading: Boolean)
        fun onMainFrameError()
        fun onRendererGone()

        /** Navigation is looping; the "keeps redirecting" page is showing instead. */
        fun onStuck()
    }

    /** Times of recent forced redirects, to notice Instagram and the policy bouncing off each other. */
    private val recentRedirects = ArrayDeque<Long>()

    /** Set when the main frame fails to load, until the next navigation starts. */
    private var mainFrameFailed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Subframes are embeds inside an allowed page (captchas, identity checks), not navigation.
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        return when (val decision = UrlPolicy.decide(url, host.policyOptions)) {
            UrlPolicy.Decision.Allow -> false
            is UrlPolicy.Decision.External -> {
                host.openExternal(decision.url)
                true
            }
            UrlPolicy.Decision.Drop -> true
            is UrlPolicy.Decision.Block -> {
                // Refusing is enough when the user is on a normal page: they just stay there. After
                // login (or on a server redirect) the current page is a login page or nothing, so
                // send them somewhere useful instead.
                val current = view.url
                if (request.isRedirect || !UrlPolicy.isBackTarget(current, host.policyOptions)) {
                    redirect(view, decision.redirect)
                }
                true
            }
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (url == null) return
        when (val decision = UrlPolicy.decide(url, host.policyOptions)) {
            UrlPolicy.Decision.Allow -> if (url.startsWith("http")) host.onAllowedPage(url, failed = mainFrameFailed)
            is UrlPolicy.Decision.Block -> backOut(view, decision.redirect)
            is UrlPolicy.Decision.External -> {
                host.openExternal(decision.url)
                backOut(view, UrlPolicy.INBOX_URL)
            }
            UrlPolicy.Decision.Drop -> backOut(view, UrlPolicy.INBOX_URL)
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        mainFrameFailed = false
        host.onPageLoading(true)
        fallbackScript?.let { view.evaluateJavascript(it, null) }
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        super.onPageCommitVisible(view, url)
        fallbackScript?.let { view.evaluateJavascript(it, null) }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        host.onPageLoading(false)
        // Again at the end: earlier callbacks can run before the document exists. The script is
        // idempotent, so extra runs cost nothing.
        fallbackScript?.let { view.evaluateJavascript(it, null) }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            mainFrameFailed = true
            host.onMainFrameError()
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // The WebView is unusable now; the activity rebuilds itself with a fresh one.
        host.onRendererGone()
        return true
    }

    /** The injected script gave up bouncing: show the "keeps redirecting" page. */
    fun showStuck(view: WebView) {
        view.loadDataWithBaseURL(null, STUCK_PAGE, "text/html", "utf-8", null)
        host.onStuck()
    }

    /**
     * Undo a navigation that already happened. Going back is the smooth way (the SPA treats it as a
     * popstate, no reload), so step back to the nearest allowed, non-login page if there is one.
     */
    private fun backOut(view: WebView, fallback: String) {
        val history = view.copyBackForwardList()
        var index = history.currentIndex - 1
        while (index >= 0 && !UrlPolicy.isBackTarget(history.getItemAtIndex(index)?.url, host.policyOptions)) {
            index--
        }
        if (index >= 0 && !tooManyRedirects()) {
            recentRedirects.addLast(SystemClock.elapsedRealtime())
            view.goBackOrForward(index - history.currentIndex)
        } else {
            redirect(view, fallback)
        }
    }

    private fun redirect(view: WebView, url: String) {
        if (tooManyRedirects()) {
            showStuck(view)
            return
        }
        recentRedirects.addLast(SystemClock.elapsedRealtime())
        view.loadUrl(url)
    }

    private fun tooManyRedirects(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (recentRedirects.isNotEmpty() && now - recentRedirects.first() > REDIRECT_WINDOW_MS) {
            recentRedirects.removeFirst()
        }
        return recentRedirects.size >= MAX_REDIRECTS
    }

    private companion object {
        const val MAX_REDIRECTS = 6
        const val REDIRECT_WINDOW_MS = 10_000L

        const val STUCK_PAGE =
            "<html><head><meta name=viewport content='width=device-width'></head>" +
                "<body style='font-family:sans-serif;padding:24px;line-height:1.5'>" +
                "<h3>Instagram keeps redirecting</h3>" +
                "<p>Instagram keeps sending DM Only to a page it blocks.</p>" +
                "<p><a href='https://www.instagram.com/direct/inbox/'>Try your messages again</a></p>" +
                "<p>If this keeps happening, close the app and reopen it in a few minutes, or log out " +
                "under Settings and log in again.</p></body></html>"
    }
}
