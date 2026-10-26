package com.jjbeukes.dmonly

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * The handful of settings the app keeps. Keys match res/xml/preferences.xml, and this reads the
 * default preferences file so it sees exactly what the Settings screen writes.
 */
class Prefs(context: Context) {

    val raw: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var username: String?
        get() = UrlPolicy.normaliseUsername(raw.getString(KEY_USERNAME, null))
        set(value) = raw.edit().putString(KEY_USERNAME, UrlPolicy.normaliseUsername(value)).apply()

    val blurMedia: Boolean get() = raw.getBoolean(KEY_BLUR_MEDIA, false)
    val hideSuggestions: Boolean get() = raw.getBoolean(KEY_HIDE_SUGGESTIONS, true)
    val allowActivity: Boolean get() = raw.getBoolean(KEY_ALLOW_ACTIVITY, false)

    /** Set by Settings after clearing the session, so the main screen resets its WebView. */
    var pendingReset: Boolean
        get() = raw.getBoolean(KEY_PENDING_RESET, false)
        set(value) = raw.edit().putBoolean(KEY_PENDING_RESET, value).apply()

    /** A page Settings wants the main screen to open (e.g. Instagram's notifications page). */
    fun requestOpen(url: String) {
        raw.edit().putString(KEY_PENDING_OPEN_URL, url).apply()
    }

    fun consumePendingOpenUrl(): String? {
        val url = raw.getString(KEY_PENDING_OPEN_URL, null) ?: return null
        raw.edit().remove(KEY_PENDING_OPEN_URL).apply()
        return url
    }

    fun policyOptions() = UrlPolicy.Options(
        allowActivity = allowActivity,
        hideSuggestions = hideSuggestions,
    )

    /** What the injected script needs to know, as JSON. */
    fun scriptConfig(): String = JSONObject()
        .put("blurMedia", blurMedia)
        .put("hideSuggestions", hideSuggestions)
        .put("allowActivity", allowActivity)
        .toString()

    /** Changes to any of these mean the page has to be reloaded to pick them up. */
    fun pageAffectingSnapshot(): String = "$blurMedia|$hideSuggestions|$allowActivity"

    companion object {
        const val KEY_USERNAME = "username"
        const val KEY_BLUR_MEDIA = "blur_media"
        const val KEY_HIDE_SUGGESTIONS = "hide_suggestions"
        const val KEY_ALLOW_ACTIVITY = "allow_activity"
        const val KEY_PENDING_RESET = "pending_reset"
        const val KEY_PENDING_OPEN_URL = "pending_open_url"
    }
}
