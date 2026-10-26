package com.jjbeukes.dmonly

import java.net.URLDecoder

/**
 * Decides what the WebView is allowed to show.
 *
 * Plain Kotlin with no Android dependencies so it can be unit tested on the JVM. The injected
 * script asks the same questions synchronously through [WebBridge], so this object is the single
 * source of truth for real page loads and for Instagram's client-side (pushState) navigation.
 *
 * Only the main frame is ever judged. Subframes (reCAPTCHA, Meta's identity checks) are part of
 * whatever page embeds them, and gating them strands people half way through logging in.
 */
object UrlPolicy {

    const val ORIGIN = "https://www.instagram.com"
    const val INBOX_URL = "$ORIGIN/direct/inbox/"
    const val SEARCH_URL = "$ORIGIN/explore/search/"

    sealed interface Decision {
        /** Show it in the WebView. */
        data object Allow : Decision

        /**
         * Keep the user out. If the WebView already shows the page (client-side navigation),
         * step back to the previous allowed page or, failing that, load [redirect].
         */
        data class Block(val redirect: String) : Decision

        /** Hand off to another app: the browser, mail, the dialer. Never an instagram.com URL. */
        data class External(val url: String) : Decision

        /** Swallow silently, e.g. intent:// links that would launch the real Instagram app. */
        data object Drop : Decision
    }

    /** What kind of allowed page a URL is. Drives the bottom bar and the injected script. */
    enum class Route(val id: String) {
        INBOX("inbox"),
        THREAD("thread"),
        DM("dm"),
        SEARCH("search"),
        PROFILE("profile"),
        FOLLOW_LIST("follow"),
        AUTH("auth"),
        ACCOUNT("account"),
        OTHER("other"),
    }

    data class Options(
        /** Instagram's notifications page, where follow requests for private accounts are approved. */
        val allowActivity: Boolean = false,
        /** Block Instagram's "Discover people" page of suggested accounts. */
        val hideSuggestions: Boolean = true,
    )

    /** Hosts judged by the Instagram path rules. instagr.am is Instagram's old short domain (301s to www). */
    private val INSTAGRAM_HOSTS = setOf(
        "instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "www.instagr.am",
    )

    /** Instagram's ig.me short links (ig.me/m/<user> opens a chat). Loaded in-app so the redirect is judged. */
    private val SHORT_LINK_HOSTS = setOf("ig.me", "www.ig.me")

    /** Password, 2FA, cookie-consent and Accounts Center flows; they must load in-app. */
    private val IN_APP_HOSTS = setOf(
        "accountscenter.instagram.com",
        "auth.instagram.com",
        "accountscenter.meta.com",
        "auth.meta.com",
    )

    /** Outbound link wrappers: l.instagram.com/?u=<encoded target>. */
    private val LINK_SHIM_HOSTS = setOf("l.instagram.com", "l.facebook.com", "lm.facebook.com")

    private val FACEBOOK_HOSTS = setOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com")

    /** Facebook paths that belong to "Log in with Facebook" or account recovery, not the feed. */
    private val FACEBOOK_LOGIN_PATH = Regex(
        "^/(login|checkpoint|two_step_verification|two_factor|auth|recover|cookie|dialog/oauth|v\\d+(\\.\\d+)?/dialog/oauth)(/|\\.php|$).*",
        RegexOption.IGNORE_CASE,
    )

    /** Other Meta surfaces that only exist to pull you somewhere else. */
    private val DROPPED_HOSTS = setOf(
        "meta.ai", "www.meta.ai",
        "threads.com", "www.threads.com", "threads.net", "www.threads.net",
        "applink.instagram.com",
    )

    private val DROPPED_SCHEMES = setOf("intent", "instagram", "fb", "fb-messenger", "market", "android-app")
    private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo")
    private val INERT_SCHEMES = setOf("about", "data", "blob", "javascript")

    /**
     * First path segments for login, security, settings and the web app's own plumbing. Instagram
     * reserves these words (they appear in its route table), so no username can collide with them.
     */
    private val ALLOWED_ROOTS = setOf(
        "challenge", "two_factor", "auth_platform", "fxcal", "consent", "oauth", "403invalidnonce",
        "terms", "privacy", "legal", "help", "support", "session", "emails", "push", "language",
        "download", "api", "graphql", "ajax", "_n",
    )

    /** Reserved first segments that lead to feeds, posts, reels, stories or other distractions. */
    private val BLOCKED_ROOTS = setOf(
        "p", "reel", "reels", "tv", "video", "stories", "s", "live", "channels", "create", "ar",
        "aradscamera", "a", "c", "r", "add", "ads", "audio", "bulk_upload", "categories", "comments",
        "community_notes", "directory", "insights", "notifications", "popular", "profiles", "qr",
        "related_profiles", "saved", "scheduled_content", "school_partnerships", "series", "settings",
        "sfs_tools", "similar_accounts", "supervision", "threads", "votinginfocenter", "watch",
        "your_activity", "archive", "meta_ai", "developer", "about", "maps", "shop", "shopping",
        "guide", "guides", "lite", "me",
    )

    /** /accounts/ pages that are part of logging in (as opposed to settings pages). */
    private val AUTH_ACCOUNT_PAGES = setOf(
        "login", "onetap", "password", "emailsignup", "signup", "two_factor", "logout", "suspended",
        "disabled", "registered", "confirm_email", "confirm_phone", "update_email", "vulnerable_password",
        "scraping_warning", "update_risky_contactpoint", "profile_selection",
    )

    private val AUTH_ROOTS = setOf(
        "challenge", "two_factor", "auth_platform", "fxcal", "consent", "oauth", "403invalidnonce", "terms",
        "privacy",
    )

    /** Instagram usernames: letters, digits, periods and underscores, at most 30 characters. */
    private val USERNAME = Regex("^[A-Za-z0-9._]{1,30}$")

    fun decide(url: String, options: Options = Options()): Decision {
        val parsed = ParsedUrl.parse(url) ?: return Decision.Drop
        return when (parsed.scheme) {
            "http", "https" -> decideWeb(parsed, options, depth = 0)
            in INERT_SCHEMES -> Decision.Allow
            in EXTERNAL_SCHEMES -> Decision.External(parsed.raw)
            in DROPPED_SCHEMES -> Decision.Drop
            else -> Decision.Drop
        }
    }

    /** True if [url] may be shown in the WebView. */
    fun isAllowed(url: String?, options: Options = Options()): Boolean =
        url != null && decide(url, options) == Decision.Allow

    /**
     * True if Back may land on [url]: an allowed web page that isn't a login step. Excludes inert
     * pages such as about:blank (the "keeps redirecting" page).
     */
    fun isBackTarget(url: String?, options: Options = Options(), allowAuth: Boolean = false): Boolean {
        val scheme = url?.let { ParsedUrl.parse(it) }?.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        return isAllowed(url, options) && (allowAuth || !isAuthPage(url))
    }

    /**
     * Any Instagram-owned host. Such URLs must never be handed to another app: Android's App Links
     * would open them in the real Instagram app.
     */
    fun isInstagramFamilyHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h in INSTAGRAM_HOSTS || h in SHORT_LINK_HOSTS || h == "instagram.com" ||
            h.endsWith(".instagram.com") || h.endsWith(".instagr.am") || h.endsWith(".ig.me")
    }

    /** Classifies an allowed URL. Blocked or foreign URLs come back as [Route.OTHER]. */
    fun route(url: String?): Route {
        val parsed = url?.let { ParsedUrl.parse(it) } ?: return Route.OTHER
        if (parsed.scheme != "http" && parsed.scheme != "https") return Route.OTHER
        if (parsed.host in IN_APP_HOSTS) return Route.AUTH
        if (parsed.host in FACEBOOK_HOSTS) return Route.AUTH
        if (parsed.host !in INSTAGRAM_HOSTS) return Route.OTHER

        val segments = parsed.segments
        val first = segments.firstOrNull()?.lowercase() ?: return Route.OTHER
        val second = segments.getOrNull(1)?.lowercase()
        return when {
            first == "direct" -> when {
                second == null || second == "inbox" -> Route.INBOX
                second == "t" -> Route.THREAD
                else -> Route.DM
            }
            first == "explore" && second == "search" && segments.size == 2 -> Route.SEARCH
            first == "accounts" -> if (second == null || second in AUTH_ACCOUNT_PAGES) Route.AUTH else Route.ACCOUNT
            first in AUTH_ROOTS -> Route.AUTH
            first in ALLOWED_ROOTS || first in BLOCKED_ROOTS || first == "explore" -> Route.OTHER
            first == "users" || first == "linking" -> Route.OTHER
            !USERNAME.matches(segments[0]) -> Route.OTHER
            segments.size == 1 -> Route.PROFILE
            second == "followers" || second == "following" -> Route.FOLLOW_LIST
            else -> Route.OTHER
        }
    }

    /** True for login and checkpoint pages, which back navigation must not land on once logged in. */
    fun isAuthPage(url: String?): Boolean = route(url) == Route.AUTH

    /** The username a profile or followers/following URL belongs to, or null. */
    fun profileUsername(url: String?): String? {
        val r = route(url)
        if (r != Route.PROFILE && r != Route.FOLLOW_LIST) return null
        return ParsedUrl.parse(url!!)?.segments?.firstOrNull()
    }

    /** Normalises what a person typed (or the page reported) into a username, or null if implausible. */
    fun normaliseUsername(raw: String?): String? {
        val name = raw?.trim()?.removePrefix("@")?.trim('/')?.lowercase() ?: return null
        if (!USERNAME.matches(name)) return null
        if (name in ALLOWED_ROOTS || name in BLOCKED_ROOTS) return null
        if (name in setOf("direct", "accounts", "explore", "users", "linking", "web")) return null
        return name
    }

    /** The profile URL for [username], or null if it isn't a plausible Instagram username. */
    fun profileUrl(username: String?): String? = normaliseUsername(username)?.let { "$ORIGIN/$it/" }

    private fun decideWeb(url: ParsedUrl, options: Options, depth: Int): Decision {
        val host = url.host
        return when {
            // A web URL without a host is malformed; a browser would "fix" it into anything.
            host.isEmpty() -> Decision.Drop
            host in INSTAGRAM_HOSTS -> decideInstagramPath(url.segments, options)
            host in IN_APP_HOSTS -> Decision.Allow
            host in SHORT_LINK_HOSTS -> Decision.Allow
            host in LINK_SHIM_HOSTS -> unwrapLinkShim(url, options, depth)
            host in FACEBOOK_HOSTS -> if (FACEBOOK_LOGIN_PATH.matches(url.path)) Decision.Allow else Decision.External(url.raw)
            host in DROPPED_HOSTS -> Decision.Drop
            isInstagramFamilyHost(host) -> Decision.Drop // help., about., i. etc. would hand over to the app
            host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net") -> Decision.Drop
            host == "play.google.com" && ("com.instagram" in url.query || "com.facebook" in url.query) -> Decision.Drop
            else -> Decision.External(url.raw)
        }
    }

    /**
     * Instagram wraps every outbound link. Unwrap it and judge the real target, so a friend's link
     * to an Instagram post is still blocked and a link to an Instagram profile still opens in-app.
     */
    private fun unwrapLinkShim(url: ParsedUrl, options: Options, depth: Int): Decision {
        val target = url.queryParam("u")?.let { ParsedUrl.parse(it) } ?: return Decision.Drop
        if (target.scheme != "http" && target.scheme != "https") return Decision.Drop
        if (depth >= 2) return Decision.Drop
        return decideWeb(target, options, depth + 1)
    }

    private fun decideInstagramPath(segments: List<String>, options: Options): Decision {
        // "/" is the home feed. Instagram also lands here right after logging in.
        val first = segments.firstOrNull()?.lowercase() ?: return Decision.Block(INBOX_URL)
        val second = segments.getOrNull(1)?.lowercase()

        return when {
            first == "direct" -> if (second == "events") Decision.Block(INBOX_URL) else Decision.Allow

            first == "accounts" -> when {
                second == "activity" -> if (options.allowActivity) Decision.Allow else Decision.Block(INBOX_URL)
                else -> Decision.Allow
            }

            // The phone layout's activity page; follow requests for private accounts live here.
            first == "notifications" -> if (options.allowActivity) Decision.Allow else Decision.Block(INBOX_URL)

            // Only the bare search page. /explore/ itself is the grid (and where search's Cancel
            // button goes); tags, locations and keyword results are post grids too.
            first == "explore" -> when {
                second == "search" && segments.size == 2 -> Decision.Allow
                second == "people" && !options.hideSuggestions -> Decision.Allow
                else -> Decision.Block(SEARCH_URL)
            }

            // Server-side shortcuts to your own profile.
            first == "users" -> if (second == "self" && segments.size == 2) Decision.Allow else Decision.Block(INBOX_URL)
            first == "linking" -> if (second == "me" && segments.size == 2) Decision.Allow else Decision.Block(INBOX_URL)

            first == "web" -> if (second == "lite") Decision.Block(INBOX_URL) else Decision.Allow

            first in ALLOWED_ROOTS -> Decision.Allow
            first in BLOCKED_ROOTS -> Decision.Block(INBOX_URL)
            !USERNAME.matches(segments[0]) -> Decision.Block(INBOX_URL)

            // A profile: the header (bio, counts, follow button) and the followers/following lists
            // are allowed; posts, reels, tagged, saved and anything else under it are not.
            segments.size == 1 -> Decision.Allow
            second == "followers" || second == "following" -> Decision.Allow
            else -> Decision.Block("$ORIGIN/${segments[0]}/")
        }
    }

    /** Lenient URL splitter: java.net.URI rejects many real-world URLs (spaces, '|', unicode). */
    internal data class ParsedUrl(
        val raw: String,
        val scheme: String,
        val host: String,
        val path: String,
        val query: String,
    ) {
        /** Path segments, percent-decoded, with "." and ".." resolved the way a browser would. */
        val segments: List<String>
            get() {
                val out = ArrayList<String>()
                for (part in path.split('/')) {
                    when (val seg = percentDecode(part)) {
                        "", "." -> Unit
                        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                        else -> out.add(seg)
                    }
                }
                return out
            }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return bytes.toString("UTF-8")
        }

        fun queryParam(name: String): String? = query.split('&').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=') != name) return@firstNotNullOfOrNull null
            runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }

        companion object {
            private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

            fun parse(input: String): ParsedUrl? {
                // Browsers drop tabs and newlines anywhere in a URL; do the same so they can't hide a host.
                val raw = input.trim().replace(Regex("[\\t\\r\\n]"), "")
                val scheme = SCHEME.find(raw)?.groupValues?.get(1)?.lowercase() ?: return null
                val rest = raw.substring(scheme.length + 1)
                val web = scheme == "http" || scheme == "https"
                if (!web && !rest.startsWith("//")) return ParsedUrl(raw, scheme, "", rest, "")

                // For web URLs browsers accept any number of slashes or backslashes before the host
                // ("https:/x", "https:///x"), so skip them all rather than requiring exactly "//".
                val afterSlashes = if (web) rest.trimStart('/', '\\') else rest.substring(2)
                val authorityEnd = afterSlashes.indexOfAny(charArrayOf('/', '?', '#', '\\'))
                    .let { if (it < 0) afterSlashes.length else it }
                val authority = afterSlashes.substring(0, authorityEnd)
                val host = authority.substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
                val tail = afterSlashes.substring(authorityEnd).substringBefore('#').replace('\\', '/')
                val path = tail.substringBefore('?').ifEmpty { "/" }
                val query = if ('?' in tail) tail.substringAfter('?') else ""
                return ParsedUrl(raw, scheme, host, path, query)
            }
        }
    }
}
