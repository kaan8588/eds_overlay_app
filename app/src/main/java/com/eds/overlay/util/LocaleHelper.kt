package com.eds.overlay.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Single source of truth for the app's display language.
 *
 * Activities go through AppCompat's per-app locale API
 * ([AppCompatDelegate.setApplicationLocales]), which is the only supported way
 * to switch languages in an [androidx.appcompat.app.AppCompatActivity]:
 * AppCompat re-applies its own locale state on every activity attach, so a
 * hand-rolled `attachBaseContext` override gets silently overwritten.
 *
 * Non-AppCompat components (the [android.app.Application] and the overlay
 * [android.app.Service]) are not covered by that API on Android 12 and below,
 * so they wrap their base context through [wrapContext] instead. The chosen
 * language is mirrored into SharedPreferences so both paths agree.
 */
object LocaleHelper {

    const val PREFS_NAME = "muavin_prefs"
    private const val KEY_LANG = "app_lang"

    const val LANG_TR = "tr"
    const val LANG_EN = "en"

    /**
     * Returns the stored language tag, seeding it on first launch from the
     * system language: Turkish devices get Turkish, everything else English.
     */
    fun storedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_LANG, null)?.let { return it }

        val seeded = if (Locale.getDefault().language == LANG_TR) LANG_TR else LANG_EN
        prefs.edit().putString(KEY_LANG, seeded).apply()
        return seeded
    }

    /** Returns the language to switch to from [tag] — the app only ships tr/en. */
    fun oppositeLanguage(tag: String): String =
        if (tag == LANG_TR) LANG_EN else LANG_TR

    /**
     * Persists [tag] and hands it to AppCompat, which re-creates every started
     * activity with the new locale.
     *
     * The write is committed synchronously because the overlay service reads
     * the preference from its own process entry point right afterwards.
     */
    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, tag)
            .commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /**
     * Reconciles AppCompat's locale state with the stored preference. Must be
     * called from an activity, since AppCompat resolves its locale storage
     * through an active delegate.
     *
     * When AppCompat has no locale yet (fresh install, or an upgrade from the
     * previous manual implementation) the stored preference is pushed to it.
     * When it already has one — for instance because the user picked a language
     * in the Android system settings — that choice wins and is mirrored back
     * into the preference so the service stays in sync.
     */
    fun syncWithStoredLanguage(context: Context) {
        val applied = AppCompatDelegate.getApplicationLocales()

        if (applied.isEmpty) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(storedLanguage(context))
            )
            return
        }

        val appliedTag = applied[0]?.language ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LANG, null) != appliedTag) {
            prefs.edit().putString(KEY_LANG, appliedTag).apply()
        }
    }

    /**
     * Wraps a non-AppCompat base context so its resources resolve in the
     * selected language.
     *
     * @param base the context handed to `attachBaseContext`
     * @return a configuration context pinned to the stored language
     */
    fun wrapContext(base: Context): Context {
        val locale = Locale.forLanguageTag(storedLanguage(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
