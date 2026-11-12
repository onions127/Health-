package com.jcwear.coach

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.jcwear.coach.ai.ClaudeCoachRepository
import com.jcwear.coach.ble.J2208BleManager
import com.jcwear.coach.data.LocalHealthDatabase
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
        database = LocalHealthDatabase(applicationContext).also {
            it.seedInitialBaselineIfEmpty()
        }
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
