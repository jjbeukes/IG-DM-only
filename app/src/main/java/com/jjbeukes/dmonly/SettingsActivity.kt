package com.jjbeukes.dmonly

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The few options DM Only has, plus logging out. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
        // Edge-to-edge is enforced from Android 15: keep the list clear of the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var prefs: Prefs

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = Prefs(requireContext())

            findPreference<EditTextPreference>(Prefs.KEY_USERNAME)?.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    UrlPolicy.normaliseUsername(pref.text)?.let { "@$it" }
                        ?: getString(R.string.pref_username_summary_empty)
                }
                setOnBindEditTextListener { edit ->
                    edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    edit.isSingleLine = true
                }
                setOnPreferenceChangeListener { _, value ->
                    val raw = (value as? String).orEmpty()
                    val ok = raw.isBlank() || UrlPolicy.normaliseUsername(raw) != null
                    if (!ok) Toast.makeText(requireContext(), R.string.invalid_username, Toast.LENGTH_SHORT).show()
                    ok
                }
            }

            findPreference<Preference>(KEY_OPEN_ACTIVITY)?.setOnPreferenceClickListener {
                prefs.requestOpen("${UrlPolicy.ORIGIN}/notifications/")
                requireActivity().finish()
                true
            }

            findPreference<Preference>(KEY_LOGOUT)?.setOnPreferenceClickListener {
                confirmLogout()
                true
            }

            findPreference<Preference>(KEY_ABOUT)?.summary = getString(R.string.pref_about_summary, versionName())
        }

        private fun confirmLogout() {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.pref_logout_confirm)
                .setPositiveButton(R.string.pref_logout_title) { _, _ -> logOut() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        /**
         * Forget the Instagram session. The main screen does the wipe (cookies, site storage, cache)
         * when it resumes, after leaving the Instagram page so nothing can write the session back.
         */
        private fun logOut() {
            prefs.username = null
            findPreference<EditTextPreference>(Prefs.KEY_USERNAME)?.text = null
            prefs.pendingReset = true
            Toast.makeText(requireContext(), R.string.pref_logout_done, Toast.LENGTH_SHORT).show()
            requireActivity().finish()
        }

        private fun versionName(): String = try {
            val context = requireContext()
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }

        private companion object {
            const val KEY_OPEN_ACTIVITY = "open_activity"
            const val KEY_LOGOUT = "logout"
            const val KEY_ABOUT = "about"
        }
    }
}
