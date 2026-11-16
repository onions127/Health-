package com.jcwear.coach

import android.Manifest
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.bluetooth.le.ScanFilter
import android.content.Intent
import android.content.IntentSender
import com.jcwear.coach.notify.BedtimeReminder
import com.jcwear.coach.notify.Notifications
import com.jcwear.coach.ui.BedtimeUi
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.mutableStateOf
import com.jcwear.coach.ble.SyncWorker
import java.util.regex.Pattern
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

    private val cdm get() = getSystemService(CompanionDeviceManager::class.java)
    @Suppress("DEPRECATION") // getMyAssociations needs API 33; minSdk is 26
    private fun isCompanionPaired() = cdm?.associations?.isNotEmpty() == true
    private val companionPaired = mutableStateOf(false)
    private val morningNotifyOn = mutableStateOf(false)
    private val bedtimeOn = mutableStateOf(false)
    private val bedtimeStatus = mutableStateOf("")
    private val latestBedtimeMfn = mutableStateOf(BedtimeReminder.DEFAULT_LATEST_MFN)
    /** Which toggle asked for the notification permission. */
    private var pendingNotifyToggle: (Boolean) -> Unit = {}
    /** Night to open from the notification's Edit action (session_key); consumed by the dashboard. */
    private val openNightKey = mutableStateOf<String?>(null)

    private val notifyPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingNotifyToggle(granted)
        if (!granted) Toast.makeText(this, "Allow notifications in system settings", Toast.LENGTH_LONG).show()
    }

    /** Turns a notification feature on (asking for the permission first on API 33+) or off. */
    private fun toggleNotify(on: Boolean, set: (Boolean) -> Unit) {
        if (on || Build.VERSION.SDK_INT < 33) return set(!on)
        pendingNotifyToggle = set
        notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun setBedtime(on: Boolean) {
        container.settings.bedtimeReminderEnabled = on
        bedtimeOn.value = on
        replanBedtime()
    }

    private fun replanBedtime() {
        bedtimeStatus.value = BedtimeReminder.plan(this)
    }

    private fun shiftLatestBedtime(deltaMin: Int) {
        val v = (container.settings.latestBedtimeMfn + deltaMin)
            .coerceIn(BedtimeReminder.LATEST_RANGE_MFN.first, BedtimeReminder.LATEST_RANGE_MFN.last)
        container.settings.latestBedtimeMfn = v
        latestBedtimeMfn.value = v
        replanBedtime()
    }

    private fun setMorningNotify(on: Boolean) {
        container.settings.morningNotifyEnabled = on
        morningNotifyOn.value = on
    }

    private fun toggleMorningNotify() = toggleNotify(morningNotifyOn.value, ::setMorningNotify)

    private fun readNightExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.EXTRA_OPEN_EDITOR, false) == true) {
            openNightKey.value = intent.getStringExtra(Notifications.EXTRA_SESSION_KEY)
            Notifications.cancelMorning(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readNightExtra(intent)
    }

    private val companionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        companionPaired.value = isCompanionPaired()
        if (r.resultCode == RESULT_OK) Toast.makeText(this, "Background sync is on", Toast.LENGTH_SHORT).show()
    }

    /** Shows the system "pair as companion" chooser for the band (once; D21). */
    private fun pairAsCompanion() {
        val manager = cdm ?: return Toast.makeText(this, "Companion pairing not supported", Toast.LENGTH_LONG).show()
        val address = container.settings.bandAddress
        val filter = BluetoothLeDeviceFilter.Builder().apply {
            if (address.isNotBlank()) setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build())
            else setNamePattern(Pattern.compile(".*2208.*"))
        }.build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        manager.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("API 33 routes onAssociationPending here by default")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                companionLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }

            override fun onFailure(error: CharSequence?) {
                Toast.makeText(this@MainActivity, "Pairing failed: ${error ?: "band not found"}", Toast.LENGTH_LONG).show()
            }
        }, null)
    }

    /** Forget band: drop the companion association too, so the next pairing starts clean. */
    @Suppress("DEPRECATION")
    private fun forgetBand() {
        container.bleManager.forgetBand()
        cdm?.associations?.forEach { cdm?.disassociate(it) }
        companionPaired.value = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        companionPaired.value = isCompanionPaired()
        morningNotifyOn.value = container.settings.morningNotifyEnabled
        bedtimeOn.value = container.settings.bedtimeReminderEnabled
        latestBedtimeMfn.value = container.settings.latestBedtimeMfn
        readNightExtra(intent)

        setContent {
            MainDashboardScreen(
                viewModel = viewModel { DashboardViewModel(container.database, container.bleManager) },
                settings = container.settings,
                bleManager = container.bleManager,
                syncCoordinator = container.syncCoordinator,
                coachRepo = container.coachRepo,
                cleanupPending = container.cleanup.serverStepsPending,
                companionPaired = companionPaired.value,
                onPairCompanion = ::pairAsCompanion,
                onForgetBand = ::forgetBand,
                onRequestBlePermissions = ::requestBlePermissions,
                morningNotifyOn = morningNotifyOn.value,
                onToggleMorningNotify = ::toggleMorningNotify,
                bedtime = BedtimeUi(
                    on = bedtimeOn.value,
                    status = bedtimeStatus.value,
                    latest = BedtimeReminder.format(latestBedtimeMfn.value),
                    onToggle = { toggleNotify(bedtimeOn.value, ::setBedtime) },
                    onShiftLatest = ::shiftLatestBedtime
                ),
                openNightKey = openNightKey.value,
                onNightOpened = { openNightKey.value = null }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        container.syncCoordinator.sync("OPEN") // a sync already running is joined, not doubled
        replanBedtime()
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
