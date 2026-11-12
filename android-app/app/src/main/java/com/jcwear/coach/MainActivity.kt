package com.jcwear.coach

import android.Manifest
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.jcwear.coach.ai.ClaudeCoachRepository
import com.jcwear.coach.ble.J2208BleManager
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.data.LocalHealthDatabase
import java.io.IOException
import com.jcwear.coach.ui.MainDashboardScreen

class MainActivity : ComponentActivity() {

    private lateinit var database: LocalHealthDatabase
    private lateinit var bleManager: J2208BleManager
    private lateinit var coachRepo: ClaudeCoachRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = LocalHealthDatabase(applicationContext)
        importBundledHistoryOnce(database)
        bleManager = J2208BleManager(applicationContext, database)
        coachRepo = ClaudeCoachRepository(database)

        setContent {
            MainDashboardScreen(
                database = database,
                bleManager = bleManager,
                coachRepo = coachRepo,
                onRequestBlePermissions = ::requestBlePermissions
            )
        }
    }

    /**
     * Phase 0: merge the bundled real band history once (insert-only, never overwrites).
     * Replaces the old seeder that overwrote the DB file and wrote fake "seed" rows on every launch.
     */
    private fun importBundledHistoryOnce(db: LocalHealthDatabase) {
        val settings = AppSettings(applicationContext)
        if (settings.bundledHistoryImported) return
        try {
            db.importBundledHistory()
            settings.bundledHistoryImported = true
        } catch (e: SQLiteException) {
            Log.e("MainActivity", "Bundled history import failed; will retry next launch", e)
        } catch (e: IOException) {
            Log.e("MainActivity", "Couldn't copy bundled history asset; will retry next launch", e)
        }
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        permissionLauncher.launch(perms)
    }
}
