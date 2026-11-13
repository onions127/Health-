package com.jcwear.coach.data

import android.content.Context

/**
 * Persists user-entered connection settings in app-private storage.
 * The prefs file is excluded from cloud backup and device transfer
 * (see res/xml/backup_rules.xml and data_extraction_rules.xml) because it holds secrets.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var homelabBaseUrl: String
        get() = prefs.getString(KEY_HOMELAB_URL, DEFAULT_HOMELAB_URL) ?: DEFAULT_HOMELAB_URL
        set(value) = prefs.edit().putString(KEY_HOMELAB_URL, value.trim()).apply()

    var homelabApiToken: String
        get() = prefs.getString(KEY_HOMELAB_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOMELAB_TOKEN, value.trim()).apply()

    var anthropicApiKey: String
        get() = prefs.getString(KEY_ANTHROPIC_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ANTHROPIC_KEY, value.trim()).apply()

    /** True once the bundled band-history dump has been merged into the live DB (Phase 0 import). */
    var bundledHistoryImported: Boolean
        get() = prefs.getBoolean(KEY_BUNDLED_HISTORY_IMPORTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BUNDLED_HISTORY_IMPORTED, value).apply()

    var useMetricUnits: Boolean
        get() = prefs.getBoolean(KEY_METRIC_UNITS, true)
        set(value) = prefs.edit().putBoolean(KEY_METRIC_UNITS, value).apply()

    /** One-time fake-row cleanup progress (plan 1.1); null = not started. See CleanupJob. */
    var cleanupState: String?
        get() = prefs.getString(KEY_CLEANUP_STATE, null)
        set(value) = prefs.edit().putString(KEY_CLEANUP_STATE, value).apply()

    companion object {
        private const val KEY_METRIC_UNITS = "use_metric_units"
        private const val KEY_CLEANUP_STATE = "cleanup_state"
        const val PREFS_NAME = "app_settings"
        private const val KEY_BUNDLED_HISTORY_IMPORTED = "bundled_history_imported"
        const val DEFAULT_HOMELAB_URL = "https://sterver.tail24d7a.ts.net:8443"
        private const val KEY_HOMELAB_URL = "homelab_base_url"
        private const val KEY_HOMELAB_TOKEN = "homelab_api_token"
        private const val KEY_ANTHROPIC_KEY = "anthropic_api_key"
    }
}
