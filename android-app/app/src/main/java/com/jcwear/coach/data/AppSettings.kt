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

    /** Band to connect to; blank after "Forget band" means scan for any J2208. */
    var bandAddress: String
        // shortcut: defaults to the owner's band so existing installs keep working; drop with multi-user support
        get() = prefs.getString(KEY_BAND_ADDRESS, DEFAULT_BAND_ADDRESS) ?: ""
        set(value) = prefs.edit().putString(KEY_BAND_ADDRESS, value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply()

    /** -1 = never read. */
    var lastBatteryPct: Int
        get() = prefs.getInt(KEY_LAST_BATTERY, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_BATTERY, value).apply()

    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_AT, value).apply()

    /** Last backup failure text, blank after a success. */
    var lastBackupError: String
        get() = prefs.getString(KEY_LAST_BACKUP_ERROR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_BACKUP_ERROR, value).apply()

    /** Band clock minus phone clock at the last sync, in seconds; null if never read. */
    var bandClockDriftSec: Long?
        get() = if (prefs.contains(KEY_CLOCK_DRIFT)) prefs.getLong(KEY_CLOCK_DRIFT, 0L) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_CLOCK_DRIFT) else putLong(KEY_CLOCK_DRIFT, value) }.apply()

    /** Last 20 sync/backup events as a JSON array, newest first (D13). */
    var recentSyncsJson: String
        get() = prefs.getString(KEY_RECENT_SYNCS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_RECENT_SYNCS, value).apply()

    /** Profile (plan 3.3). Blank name = no name in the greeting. */
    var profileName: String
        get() = prefs.getString(KEY_PROFILE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROFILE_NAME, value.trim()).apply()

    /** null = not set. */
    var heightCm: Double?
        get() = prefs.getFloat(KEY_HEIGHT_CM, 0f).takeIf { it > 0f }?.toDouble()
        set(value) = prefs.edit().putFloat(KEY_HEIGHT_CM, value?.toFloat() ?: 0f).apply()

    var weightKg: Double?
        get() = prefs.getFloat(KEY_WEIGHT_KG, 0f).takeIf { it > 0f }?.toDouble()
        set(value) = prefs.edit().putFloat(KEY_WEIGHT_KG, value?.toFloat() ?: 0f).apply()

    /** Morning "scores ready" notification (S5), off until the user turns it on. */
    var morningNotifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_MORNING_NOTIFY, false)
        set(value) = prefs.edit().putBoolean(KEY_MORNING_NOTIFY, value).apply()

    /** ISO date of the last morning notification; blank = never. */
    var lastMorningNotifyDate: String
        get() = prefs.getString(KEY_LAST_MORNING_NOTIFY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_MORNING_NOTIFY, value).apply()

    companion object {
        private const val KEY_MORNING_NOTIFY = "morning_notify_enabled"
        private const val KEY_LAST_MORNING_NOTIFY = "last_morning_notify_date"
        private const val KEY_PROFILE_NAME = "profile_name"
        private const val KEY_HEIGHT_CM = "profile_height_cm"
        private const val KEY_WEIGHT_KG = "profile_weight_kg"
        const val DEFAULT_BAND_ADDRESS = "C2:56:0C:31:17:F6"
        private const val KEY_BAND_ADDRESS = "band_address"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_BATTERY = "last_battery_pct"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_BACKUP_ERROR = "last_backup_error"
        private const val KEY_CLOCK_DRIFT = "band_clock_drift_sec"
        private const val KEY_RECENT_SYNCS = "recent_syncs"
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
