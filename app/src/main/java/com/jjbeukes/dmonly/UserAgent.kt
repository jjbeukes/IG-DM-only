package com.jjbeukes.dmonly

/** The user agent the WebView presents to Instagram. */
object UserAgent {

    /**
     * WebView's default user agent with the WebView markers removed ("; wv" and "Version/4.0"), so it
     * reads as Chrome on Android. Instagram's mobile site treats both the same today, but this is
     * what wrappers verified on real phones use, and it keeps the mobile layout. Never an iPhone or
     * desktop UA: those switch Instagram to a different layout and app id.
     */
    fun chromeLike(webViewDefault: String): String =
        webViewDefault
            .replace(Regex(""";\s*wv\)"""), ")")
            .replace(Regex("""\s*Version/\d+(\.\d+)*"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
}
