package com.jcwear.coach

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import com.jcwear.coach.ai.ClaudeCoachRepository
import com.jcwear.coach.ble.J2208BleManager
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.data.LocalHealthDatabase
import java.io.IOException

/** One instance of each long-lived dependency for the whole app (activity and, later, background work). */
class AppContainer(context: Context) {
    val settings = AppSettings(context)
    val database = LocalHealthDatabase(context)
    val bleManager = J2208BleManager(context, database)
    val coachRepo = ClaudeCoachRepository(database)
}

class HealthApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        importBundledHistoryOnce(container)
    }

    /** Phase 0: merge the bundled real band history once (insert-only, never overwrites). */
    private fun importBundledHistoryOnce(c: AppContainer) {
        if (c.settings.bundledHistoryImported) return
        try {
            c.database.importBundledHistory()
            c.settings.bundledHistoryImported = true
        } catch (e: SQLiteException) {
            Log.e("HealthApp", "Bundled history import failed; will retry next launch", e)
        } catch (e: IOException) {
            Log.e("HealthApp", "Couldn't copy bundled history asset; will retry next launch", e)
        }
    }
}
