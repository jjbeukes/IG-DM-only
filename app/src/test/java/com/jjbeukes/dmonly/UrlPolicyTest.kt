package com.jjbeukes.dmonly

import com.jjbeukes.dmonly.UrlPolicy.Decision
import com.jjbeukes.dmonly.UrlPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    private val ig = "https://www.instagram.com"

    private fun decide(url: String, options: UrlPolicy.Options = UrlPolicy.Options()) = UrlPolicy.decide(url, options)

    private fun assertAllowed(vararg urls: String) = urls.forEach { assertEquals(it, Decision.Allow, decide(it)) }

    private fun assertBlocked(vararg urls: String) = urls.forEach {
        assertTrue("expected $it to be blocked but was ${decide(it)}", decide(it) is Decision.Block)
    }

    // ------------------------------------------------------------------------- allowed

    @Test
    fun directMessagesAreAllowed() = assertAllowed(
        "$ig/direct/inbox/",
        "$ig/direct/inbox",
        "$ig/direct/",
        "$ig/direct/t/340282366841710300949128123456789012345/",
        "$ig/direct/new/",
        "$ig/direct/requests/",
        "$ig/direct/requests/hidden/",
        "$ig/direct/inbox/general/",
        "https://instagram.com/direct/inbox/",
        "https://m.instagram.com/direct/inbox/",
        "$ig/direct/inbox/?__coig_login=1",
    )

    @Test
    fun loginAndSecurityFlowsAreAllowed() = assertAllowed(
        "$ig/accounts/login/",
        "$ig/accounts/login/?next=%2Fdirect%2Finbox%2F%3F__coig_login%3D1",
        "$ig/accounts/login/two_factor?next=%2F",
        "$ig/accounts/onetap/?next=%2F",
        "$ig/accounts/password/reset/",
        "$ig/accounts/emailsignup/",
        "$ig/accounts/suspended/",
        "$ig/accounts/edit/",
        "$ig/accounts/logout/",
        "$ig/challenge/",
        "$ig/challenge/action/AXG1234/",
        "$ig/challenge/v2/",
        "$ig/two_factor/",
        "$ig/auth_platform/codeentry/?apc=abc",
        "$ig/consent/?flow=ig_biz",
        "$ig/privacy/checks/?cookie_consent=1&next=%2F",
        "$ig/fxcal/settings/",
        "$ig/terms/unblock/",
        "$ig/403invalidnonce/",
        "https://accountscenter.instagram.com/password_and_security/",
        "https://auth.instagram.com/",
        "https://accountscenter.meta.com/",
        "https://www.facebook.com/login.php?skip_api_login=1",
        "https://m.facebook.com/dialog/oauth?client_id=124024574287414",
        "https://www.facebook.com/v19.0/dialog/oauth?client_id=1",
        "https://m.facebook.com/checkpoint/1501092823525282/",
        "https://www.facebook.com/recover/initiate/",
    )

    @Test
    fun profilesAndFollowListsAreAllowed() = assertAllowed(
        "$ig/natgeo/",
        "$ig/natgeo",
        "$ig/some.user_name/",
        "$ig/natgeo/?hl=en",
        "$ig/natgeo/followers/",
        "$ig/natgeo/following/",
        "$ig/natgeo/followers/mutualOnly",
        "$ig/users/self",
        "$ig/linking/me",
    )

    @Test
    fun onlyTheBareSearchPageIsAllowed() {
        assertAllowed("$ig/explore/search/", "$ig/explore/search", "$ig/explore/search/?q=bob")
        assertEquals(Decision.Block(UrlPolicy.SEARCH_URL), decide("$ig/explore/"))
        assertEquals(Decision.Block(UrlPolicy.SEARCH_URL), decide("$ig/explore/search/keyword/?q=%23travel"))
        assertEquals(Decision.Block(UrlPolicy.SEARCH_URL), decide("$ig/explore/tags/travel/"))
        assertEquals(Decision.Block(UrlPolicy.SEARCH_URL), decide("$ig/explore/locations/212988663/new-york/"))
        assertEquals(Decision.Block(UrlPolicy.SEARCH_URL), decide("$ig/explore/search/results/"))
        assertEquals(Decision.Block(UrlPolicy.SEARCH_URL), decide("$ig/explore/people/"))
    }

    @Test
    fun suggestedPeopleCanBeAllowed() {
        val options = UrlPolicy.Options(hideSuggestions = false)
        assertEquals(Decision.Allow, decide("$ig/explore/people/", options))
    }

    // ------------------------------------------------------------------------- blocked

    @Test
    fun homeFeedRedirectsToInbox() {
        assertEquals(Decision.Block(UrlPolicy.INBOX_URL), decide("$ig/"))
        assertEquals(Decision.Block(UrlPolicy.INBOX_URL), decide(ig))
        assertEquals(Decision.Block(UrlPolicy.INBOX_URL), decide("$ig/?variant=following"))
        assertEquals(Decision.Block(UrlPolicy.INBOX_URL), decide("https://instagram.com/"))
    }

    @Test
    fun postsReelsStoriesAndOtherFeedsAreBlocked() = assertBlocked(
        "$ig/reels/",
        "$ig/reels/C1a2b3c4d5e/",
        "$ig/reels/audio/123456789/",
        "$ig/reel/C1a2b3c4d5e/",
        "$ig/reel/C1a2b3c4d5e/?igsh=abc",
        "$ig/p/C1a2b3c4d5e/",
        "$ig/p/C1a2b3c4d5e/comments/",
        "$ig/p/C1a2b3c4d5e/liked_by/",
        "$ig/tv/C1a2b3c4d5e/",
        "$ig/stories/natgeo/",
        "$ig/stories/natgeo/3345678901234567890/",
        "$ig/stories/highlights/17912345678901234/",
        "$ig/stories/direct/123/",
        "$ig/s/aGlnaGxpZ2h0OjE3OTEyMzQ1Njc4OTAxMjM0",
        "$ig/live/",
        "$ig/create/select/",
        "$ig/create/story/",
        "$ig/notifications/",
        "$ig/accounts/activity/",
        "$ig/your_activity/interactions/",
        "$ig/popular/travel/",
        "$ig/channels/",
        "$ig/threads/sso/",
        "$ig/ar/123/",
        "$ig/direct/events/123/",
        "$ig/linking/direct-inbox",
        "$ig/web/lite/",
        "$ig/meta_ai/",
        "$ig/settings/",
    )

    @Test
    fun profileSubPagesOtherThanFollowListsAreBlockedBackToTheProfile() {
        for (tab in listOf("reels", "tagged", "saved", "feed", "posts", "reposts", "p/C1a2b3", "reel/C1a2b3", "tv/C1", "live", "guide/x/1", "related_profiles", "similar_accounts")) {
            assertEquals(tab, Decision.Block("$ig/natgeo/"), decide("$ig/natgeo/$tab/"))
        }
    }

    @Test
    fun notificationsCanBeAllowedForFollowRequests() {
        val options = UrlPolicy.Options(allowActivity = true)
        assertEquals(Decision.Allow, decide("$ig/notifications/", options))
        assertEquals(Decision.Allow, decide("$ig/accounts/activity/?followRequests=1", options))
    }

    @Test
    fun pathTricksDoNotSlipThrough() = assertBlocked(
        "$ig/direct/../reels/",
        "$ig/direct/t/1/../../../p/abc/",
        "$ig/%70/C1a2b3/",
        "$ig/P/C1a2b3/",
        "$ig/REELS/",
        "$ig//reels/",
        "$ig/natgeo/%72eels/",
    )

    @Test
    fun implausibleUsernamesAreBlocked() = assertBlocked(
        "$ig/this-has-dashes/",
        "$ig/${"a".repeat(31)}/",
    )

    // ---------------------------------------------------------------- other hosts and schemes

    @Test
    fun appHandoffsAreDropped() {
        for (url in listOf(
            "intent://instagram.com/_u/natgeo#Intent;package=com.instagram.android;scheme=https;end",
            "instagram://user?username=natgeo",
            "fb://profile/1",
            "market://details?id=com.instagram.android",
            "https://play.google.com/store/apps/details?id=com.instagram.android",
            "https://applink.instagram.com/foo",
            "https://www.threads.com/@natgeo",
            "https://www.threads.net/@natgeo",
            "https://www.meta.ai/?utm_source=ig_web_nav",
            "https://help.instagram.com/581066165581870",
            "https://scontent-lhr8-1.cdninstagram.com/v/t51.2885-15/abc.jpg",
            "https://instagram.flhr4-1.fna.fbcdn.net/v/t50.2886-16/abc.mp4",
            "chrome://settings",
            "not a url",
        )) {
            assertEquals(url, Decision.Drop, decide(url))
        }
    }

    @Test
    fun outsideLinksOpenExternally() {
        assertEquals(Decision.External("https://example.com/page?a=1"), decide("https://example.com/page?a=1"))
        assertEquals(Decision.External("mailto:someone@example.com"), decide("mailto:someone@example.com"))
        assertEquals(Decision.External("tel:+441234567890"), decide("tel:+441234567890"))
        assertEquals(Decision.External("https://www.facebook.com/natgeo"), decide("https://www.facebook.com/natgeo"))
        assertEquals(
            Decision.External("https://play.google.com/store/apps/details?id=org.mozilla.firefox"),
            decide("https://play.google.com/store/apps/details?id=org.mozilla.firefox"),
        )
    }

    @Test
    fun linkShimIsUnwrappedAndTheTargetJudged() {
        assertEquals(
            Decision.External("https://example.com/a b"),
            decide("https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2Fa%20b&e=AT0abc"),
        )
        // An Instagram link inside the shim is judged by the Instagram rules, never handed out.
        assertTrue(decide("https://l.instagram.com/?u=https%3A%2F%2Fwww.instagram.com%2Freel%2FC1a2b3%2F") is Decision.Block)
        assertEquals(Decision.Allow, decide("https://l.instagram.com/?u=https%3A%2F%2Fwww.instagram.com%2Fnatgeo%2F"))
        assertEquals(Decision.Drop, decide("https://l.instagram.com/?u=javascript%3Aalert(1)"))
        assertEquals(Decision.Drop, decide("https://l.instagram.com/"))
    }

    @Test
    fun inertSchemesAreAllowed() {
        assertEquals(Decision.Allow, decide("about:blank"))
        assertEquals(Decision.Allow, decide("blob:https://www.instagram.com/1234-5678"))
        assertEquals(Decision.Allow, decide("data:text/html,hello"))
    }

    // ----------------------------------------------------------------------------- routes

    @Test
    fun routesAreClassified() {
        assertEquals(Route.INBOX, UrlPolicy.route("$ig/direct/inbox/"))
        assertEquals(Route.INBOX, UrlPolicy.route("$ig/direct/"))
        assertEquals(Route.THREAD, UrlPolicy.route("$ig/direct/t/12345/"))
        assertEquals(Route.DM, UrlPolicy.route("$ig/direct/requests/"))
        assertEquals(Route.SEARCH, UrlPolicy.route("$ig/explore/search/"))
        assertEquals(Route.PROFILE, UrlPolicy.route("$ig/natgeo/"))
        assertEquals(Route.FOLLOW_LIST, UrlPolicy.route("$ig/natgeo/followers/"))
        assertEquals(Route.AUTH, UrlPolicy.route("$ig/accounts/login/"))
        assertEquals(Route.AUTH, UrlPolicy.route("$ig/accounts/onetap/?next=%2F"))
        assertEquals(Route.AUTH, UrlPolicy.route("$ig/challenge/action/x/"))
        assertEquals(Route.AUTH, UrlPolicy.route("https://accountscenter.instagram.com/"))
        assertEquals(Route.ACCOUNT, UrlPolicy.route("$ig/accounts/edit/"))
        assertEquals(Route.OTHER, UrlPolicy.route("$ig/reels/"))
        assertEquals(Route.OTHER, UrlPolicy.route("$ig/explore/"))
        assertEquals(Route.OTHER, UrlPolicy.route("https://example.com/"))
        assertEquals(Route.OTHER, UrlPolicy.route(null))
    }

    @Test
    fun authPagesAreRecognised() {
        assertTrue(UrlPolicy.isAuthPage("$ig/accounts/login/"))
        assertTrue(UrlPolicy.isAuthPage("$ig/two_factor/"))
        assertFalse(UrlPolicy.isAuthPage("$ig/accounts/edit/"))
        assertFalse(UrlPolicy.isAuthPage("$ig/direct/inbox/"))
        assertFalse(UrlPolicy.isAuthPage(null))
    }

    // -------------------------------------------------------------------------- usernames

    @Test
    fun usernamesAreNormalised() {
        assertEquals("natgeo", UrlPolicy.normaliseUsername("@NatGeo"))
        assertEquals("some.user_1", UrlPolicy.normaliseUsername(" some.user_1/ "))
        assertNull(UrlPolicy.normaliseUsername("has space"))
        assertNull(UrlPolicy.normaliseUsername("reels"))
        assertNull(UrlPolicy.normaliseUsername("direct"))
        assertNull(UrlPolicy.normaliseUsername(""))
        assertNull(UrlPolicy.normaliseUsername(null))
        assertEquals("$ig/natgeo/", UrlPolicy.profileUrl("natgeo"))
        assertNull(UrlPolicy.profileUrl("explore"))
    }

    @Test
    fun profileUsernameIsReadFromProfileUrls() {
        assertEquals("natgeo", UrlPolicy.profileUsername("$ig/natgeo/"))
        assertEquals("natgeo", UrlPolicy.profileUsername("$ig/natgeo/following/"))
        assertNull(UrlPolicy.profileUsername("$ig/direct/inbox/"))
        assertNull(UrlPolicy.profileUsername("$ig/natgeo/reels/"))
    }
}
