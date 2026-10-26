package com.jjbeukes.dmonly

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentTest {

    @Test
    fun webViewMarkersAreRemoved() {
        val webView = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP2A.250605.031; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP2A.250605.031) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.7390.122 Mobile Safari/537.36",
            UserAgent.chromeLike(webView),
        )
    }

    @Test
    fun reducedAndroid17UserAgentIsHandled() {
        val reduced = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/150.0.0.0 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36",
            UserAgent.chromeLike(reduced),
        )
    }

    @Test
    fun chromeUserAgentIsUnchanged() {
        val chrome = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, UserAgent.chromeLike(chrome))
    }
}
