package com.jcwear.coach.ui.sky

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jcwear.coach.domain.sky.SceneParams
import com.jcwear.coach.domain.sky.SkyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Bakes a day's sky on this phone (lock plan, bakes: "the likely sky only"; round RP3). Two jobs:
 * - **night** (periodic, charging and idle): tomorrow's likely sky, today's body state with tomorrow's formation seed; run
 *   before noon it bakes today's instead (a night without a charger).
 * - **now** (once, when a page shows a sky that is not baked): that sky, the keyframes around the hour first, once the screen
 *   goes off; the page renders live meanwhile and plays the bakes from its next opening (or its next minute) on.
 * Both bake only with the screen off (RP3): see the bands below.
 * Keyframes already baked are skipped, so a job stopped by WorkManager (10 min) or the phone carries on where it was.
 */
class SkyBakeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < 29) return@withContext Result.success()
        val ctx = applicationContext
        val night = inputData.getBoolean(K_NIGHT, false)
        val (w, h) = SkyBakes.lastFrame(ctx) ?: return@withContext Result.success()     // no sky page shown yet: nothing to bake for
        val readiness = if (night) SkyBakes.lastReadiness(ctx) else inputData.getInt(K_READINESS, -1).takeIf { it >= 0 }
        val today = LocalDate.now(); val clock = LocalTime.now(); val hourNow = clock.hour + clock.minute / 60.0
        val date = if (night && clock.hour >= 12) today.plusDays(1) else today
        val sky = SkyState.weather(readiness?.toDouble())
        val key = SkyBakes.key(ctx, sky, date.toEpochDay().toInt(), w, h)
        SkyBakes.prune(ctx, key, today.toEpochDay().toInt())
        // today: the two keyframes around now first (the page can play the sky once both are in), then outward; tomorrow: from the first light on
        val hours = if (date == today) SceneParams.bracket(hourNow).let { (a, b, _) -> listOf(a, b) + SceneParams.KEYFRAMES.sortedBy { hr -> Math.abs(hr - hourNow).let { minOf(it, 24 - it) } } }.distinct()
            else SceneParams.KEYFRAMES
        var left = hours.filterNot { SkyBakes.has(SkyBakes.dir(ctx, key), it) }
        if (left.isEmpty()) return@withContext Result.success()
        // Bands (RP3, S21): each band is a render pass of a whole float slice on the Mali tiler, and short duty lets the GPU clock
        // drop, so the prototype's 8 ms bands / 16 ms gaps took 150-215 s per keyframe (68-82 s of GPU); 24 / 4 ms with the screen
        // off took 10-17 s, whole-slice passes 10-13 s. With the screen on, beside the open sky page 24 / 4 left 24-31% of its
        // scroll frames janky and 12 / 12 12-20%; under the idle home screen 12 / 12 slowed to 55-113 s per keyframe (the clock
        // again). So: 24 / 4, only while the screen is off (it holds while on, and retries in 5 min if it starts with it on)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        var budget = 24.0; var gap = 4L; var screenHold = true
        // debug builds: `adb shell setprop debug.sky.bake budget/gap/n` (RP3's band measurements)
        if ((ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val d = try { Runtime.getRuntime().exec(arrayOf("getprop", "debug.sky.bake")).inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
            d.split('/').takeIf { it.size == 3 }?.let { (b, g, n) -> budget = b.toDouble(); gap = g.toLong(); left = left.take(n.toInt()); screenHold = false }
        }
        Log.i(TAG, "bake ${key.id} (${if (night) "night" else "now"}, readiness $readiness, ${left.size} keyframes, bands ${budget.toInt()}/$gap ms)")
        val r = SkyRenderer(ctx, w, h, 2f, offscreen = true)
        val t0 = System.nanoTime()
        try {
            val inp = SceneParams.Input(sky, 0.0, key.day, (date.dayOfYear - 1).toDouble())
            var held = 0L
            val waiting = { SkyRenderer.visiblePages > 0 || (screenHold && pm.isInteractive) }
            if (waiting()) { Log.i(TAG, "screen on: bake later"); return@withContext Result.retry() }
            val hold = { while (waiting() && !isStopped) { Thread.sleep(250); held += 250 }; isStopped }
            val ok = r.bake(key, inp, left, budget, gap, stop = hold) { s ->
                Log.i(TAG, "keyframe %05.2f: %.1f s (GPU %.1f s, encode %.1f s), %.2f MB".format(java.util.Locale.ROOT, s.hour, s.ms / 1000, s.gpuMs / 1000, s.encodeMs / 1000, s.bytes / 1048576.0))
            }
            Log.i(TAG, "bake ${key.id} ${if (ok) "done" else "stopped"} in %.0f s (held %.0f s for the screen), set %.1f MB".format(java.util.Locale.ROOT, (System.nanoTime() - t0) / 1e9, held / 1e3, SkyBakes.bytes(ctx, key) / 1048576.0))
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "bake failed", e); Result.failure()
        } finally { r.stop() }
    }

    companion object {
        private const val TAG = "SkyBake"
        private const val K_NIGHT = "night"; private const val K_READINESS = "readiness"

        /** Bake the sky a page is showing (today), unless a bake is already running. */
        fun bakeNow(ctx: Context, readiness: Int?) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("sky-bake-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SkyBakeWorker>().setInputData(workDataOf(K_READINESS to (readiness ?: -1)))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES).build())
        }
        /** The nightly bake of tomorrow's likely sky, on the charger while the phone is idle. Idempotent. */
        fun scheduleNightly(ctx: Context) {
            val c = Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sky-bake-night", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SkyBakeWorker>(24, TimeUnit.HOURS).setConstraints(c).setInputData(workDataOf(K_NIGHT to true)).build())
        }
    }
}
