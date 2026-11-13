package com.jcwear.coach

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jcwear.coach.ui.DashboardViewModel
import com.jcwear.coach.ui.MainDashboardScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) container.syncCoordinator.sync("PERMISSION") }

    private val container get() = (application as HealthApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MainDashboardScreen(
                viewModel = viewModel { DashboardViewModel(container.database, container.bleManager) },
                settings = container.settings,
                bleManager = container.bleManager,
                syncCoordinator = container.syncCoordinator,
                coachRepo = container.coachRepo,
                cleanupPending = container.cleanup.serverStepsPending,
                onRequestBlePermissions = ::requestBlePermissions
            )
        }
    }

    override fun onStart() {
        super.onStart()
        container.syncCoordinator.sync("OPEN") // a sync already running is joined, not doubled
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
