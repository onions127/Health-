# Health++: fix plan for the 12 user problems

Status: draft for review (CEO, design, eng). Date: 2026-11-12.

## Root cause (why 9 of the 12 problems exist)

The current Compose UI is a design mockup wired to fake data, not to the band:

| Where | What it does | Problems it causes |
|---|---|---|
| `MainDashboardScreen.kt` `buildNinetyDaySeries` | Fills every day without a report with `sin()/cos()` values and hand-placed "outliers". Only 14 days of real reports are computed; the other 76 are always synthetic. | #4, #5, #6, #11 |
| Same function | Today's Recovery (86/88) and Daily (84/86) scores are hardcoded. | #4, #12, trust |
| `TrendsTabView` | Trend values, chips and "normal" ranges are hardcoded strings (`"59 ms" to "↑ 8% vs normal"`). Sparkline color is green for everything except strain. | #4, #12 |
| `MetricDetailView` | Outlier text is always `"Lowest in 2 weeks"`, whether the outlier is high or low. | #5, #12 |
| `SleepDetailView` | Sleep window is the literal `"23:10 – 06:30"`; chips are literal strings. | #10 |
| `SleepAndReadinessEngine` sleep score | 55% duration, 30% deep+REM share, 15% efficiency. No timing component, so 7.5h after a 3 AM bedtime can score 100. | #10 |
| `TodayTabView` | Greeting is the literal `"Morning, Alex"`. | #9 |
| `J2208BleManager` | `batteryPct` starts at 75, `connectionState` starts at a fake "Ready (...)" string; UI falls back to 84%. "Last synced 6 min ago" is a literal. No sync state machine. | #8 |
| `LocalHealthDatabase.seedInitialBaselineIfEmpty` (called from `MainActivity`) | If there are fewer than 50 sleep blocks, **overwrites the database file** with the bundled asset. The asset is **your real band history** (BLE dump of 2026-11-12: 550 sleep blocks since July, real frames, no `seed` tags), so the overwrite destroys anything newer. It then inserts fake HRV/sleep rows tagged `raw_hex = "seed"` for any recent day without data, on **every launch**. | #4, #5, #6, #10, plus data-loss risk |
| Settings | Homelab URL/token went back to `remember { "http://192.168.1.100:8000" }`; the `AppSettings` persistence added on 2026-11-12 is no longer used. Token is passed as `""`. | #7, backup broken |
| `ScoreRingGauge` | Draws the ring only; the number sits next to it. | #1 |
| Recovery summary text | Sits mid-screen in a tight card, so it reads as truncated. | #2 |

## Decisions (from Stav, 2026-11-12)

- **Real data only.** Remove all synthetic and demo data. Days with no band data are gaps. New users see "Not enough data yet" until there's enough history.
- **Sleep timing counts twice:** regularity against your own 14-day routine, plus a penalty for very late sleep (after 02:00) regardless of routine.
- **Trend colors:** compare with your 30-day average. Grey within ±1 standard deviation of your normal day-to-day spread. Beyond that, green if it's the good direction for that metric, red if it's the bad one.
- **Height/weight feed** distance-from-steps (when the band's distance is missing) and the calories fallback (D10: the band's own `calories_kcal` first; when it's missing, estimate from steps × weight; no age/sex fields).
- **Circles:** the number goes *inside* any ring or semicircle that shows the same value (Daily score, Recovery score, etc.).
- **Recovery summary:** move it to the bottom and give it room so the full sentence shows; no fake "more" affordance.
- **Sync:** automatic on app open and every 30 minutes, with a clear status on Today.
- **Ship in phases**, each usable on its own.

## Priorities

P0 = wrong or fake data (trust). P1 = misleading presentation. P2 = polish and missing settings.

| # | Problem | Priority | Phase |
|---|---|---|---|
| — | Demo DB overwrite / fake rows (data loss risk) | P0 | 1 |
| 6 | Smoothing shows a sine wave | P0 | 1 |
| 10 | Sleep times constant, score ignores bedtime | P0 | 1 |
| 12 | No lower-is-better vs higher-is-better | P0 | 1 |
| 5 | High outliers labelled "lowest" | P0 | 1 |
| 8 | Unpredictable sync, poor battery display | P0 | 1 |
| 9 | Greeting ignores time of day | P1 | 1 (tiny) |
| 4 | Trend colors | P1 | 2 |
| 11 | Sleep trends missing | P1 | 2 |
| 3 | Steps need distance | P1 | 2 |
| 1 | Numbers inside circles | P2 | 3 |
| 2 | Recovery summary at the bottom | P2 | 3 |
| 7 | Name / height / weight in Settings | P2 | 3 (name needed in 1 for greeting: default "there") |

## Phase 0: Hotfix (D17, ships first, ~1 hour)
- Remove the `ensureFourteenDayWindowPopulated()` call (MainActivity.kt:27 → `seedInitialBaselineIfEmpty`), so no new fake rows are written from today.
- Change the asset copy from "close and overwrite the DB file" into a **one-time import** of the bundled real dump: copy the asset to the cache dir (an asset can't be opened as a SQLite DB directly), `ATTACH` it, `INSERT OR IGNORE` every row into the live DB, then detach and delete the copy. The asset has `user_version` 0, so don't open it with the helper (a local row always wins), and set `AppSettings.assetImported = true`. It never overwrites anything.
- Verify: fresh install shows July–Nov history; existing install keeps its rows; launching twice adds no rows (count check in logcat).

## Phase 1 (split per D18): 1a refactor → 1b trust → 1c sync → 1d background sync
- **1a, no behavior change:** section 1.0 (ViewModel + file split). **Regression guard (D23):** step zero of 1a extracts today's in-composition calculation (MainDashboardScreen.kt:277-304) **unchanged** into a pure function `buildDashboardState(db, clock: Clock)`, with `LocalDate.now()` replaced by the injected clock. A Robolectric test then records that function's output for a fixture DB with a fixed clock (2026-11-12 08:00) (`src/test/resources/fixture_health.db`, built from a slice of the real dump) into `src/test/resources/golden/ui_state_pre1a.json` (scores, vitals, trend rows, sleep values per tab). After 1a, `DashboardUiStateGoldenTest` asserts that `DashboardUiState` produces identical values. Done when that test passes and the app builds green. 1b regenerates the golden deliberately, and the diff shows each intended change.
- **1b, trust:** sections 1.1, 1.2, 1.3, 1.4, 1.6. In 1b, the cleanup runs BACKUP_COPY → LOCAL_DELETE unconditionally (fakes disappear from the phone right away). The server steps (restore, purge, summaries) stay pending until 1c ships them. That's safe because restore excludes seed rows.
- **1c, sync:** section 1.5 (minus background sync) and the cleanup job's server steps.
- **1d:** background 30-min sync (D3).

## Phase 1 sections

### 1.0 Architecture (D12)
- New `ui/DashboardViewModel` owns all screen state as one `StateFlow<DashboardUiState>`. It loads and derives data on `Dispatchers.IO` (repository queries, baseline engine, sleep engine). The composables become stateless renderers. That fixes the whole-DB read on the main thread inside composition (`remember(refreshTick)`, MainDashboardScreen.kt:277), and sync state survives rotation.
- Split `MainDashboardScreen.kt` (2,512 lines) per screen while touching it: `ui/today/`, `ui/trends/`, `ui/sleep/`, `ui/settings/`, `ui/components/`. Behavior doesn't change because of the split itself.
- **Class layout (D19, smaller arrangement):** 5 new classes. `ui/DashboardViewModel`, `domain/DailySeriesRepository`, `domain/MetricInsights` (registry, baseline/status/extreme label, D14 floors and gap-aware smoothing), `ble/SyncCoordinator`, `data/CleanupJob`. `HomelabClient` is **moved out of** `ClaudeCoachRepository` (its `syncToHomelab` and the new restore/purge calls), not built beside it.
- Repository queries become date-ranged (`getHrRecords(from, to)`, ...), not `getAll*()`. `device_time` is already the `PRIMARY KEY` on every table, so range scans use the index.

```
 J2208BleManager --(SyncState, records)--> LocalHealthDatabase <--(restore/purge)-- HomelabClient <--> sterver API
        |                                         |
        v                                         v
  SyncCoordinator (single-flight Mutex) --> DailySeriesRepository --> MetricInsights (registry + baseline + status)
        |                                         |                         |
        +----------------> DashboardViewModel (StateFlow<UiState>, IO dispatcher) <-+
                                   |
                     Today / Trends / Sleep / Settings composables (stateless)
```

### 1.1 Remove fabricated data (blocks everything else)
- Delete `seedInitialBaselineIfEmpty`, `ensureFourteenDayWindowPopulated`, the asset-DB copy and its call in `MainActivity`. Remove `assets/jcwear_local_health.db` from the APK.
- **One-time cleanup (D4, revised by D9: restore first, delete only provable fakes):**
  - **Identify fakes by tag only (D15).** Fake rows are exactly those with `raw_hex = "seed"` (written by `ensureFourteenDayWindowPopulated`, LocalHealthDatabase.kt:370-396). The bundled asset is real band data and is **never** deleted. No key list.
  - **Order of operations** (a one-time `CleanupJob`, state kept in `AppSettings.cleanupState`):
    1. `RESTORE_PENDING`: page through `GET /api/v1/restore?all=true&table=<t>&after=<device_time>&limit=500` for each table and **insert only keys missing locally** (`CONFLICT_IGNORE`; a local row always wins), so real nights that survive only on sterver come back, and a server-side seed copy can never overwrite a real local row. Restored rows keep the server's `raw` field. **The server's restore never returns `raw = "seed"` rows**, so a restore can't bring fakes back, whatever the step order. If no server URL is configured, steps 1, 3 and 4 stay pending (step 2 still runs) and resume once the URL is set and reachable. A missing token isn't a reason to skip: the server only checks it when `SYNC_API_TOKEN` is set (main.py:148).
    2. `LOCAL_DELETE`: in one transaction, delete local rows where `raw_hex = "seed"`. Log the count per table.
    3. `SERVER_DELETE_PENDING`: `POST /api/v1/purge-seeded`. The server deletes rows whose `raw = "seed"`, per table (D16: kept automated).
    4. `SUMMARIES_PENDING`: recompute daily summaries locally from the remaining real rows. Upload them with the normal merge sync, and `DELETE /api/v1/summaries?dates=...` for dates that no longer have any real data.
    0. Before step 1, run `PRAGMA wal_checkpoint(TRUNCATE)`, then make a consistent copy with `VACUUM INTO '<path>/jcwear_local_health.pre-cleanup.db'` (SQLite 3.27+; on older devices, close the helper and copy the main file plus any `-wal`). This saves the copy to `jcwear_local_health.pre-cleanup.db` (kept until the user taps "Delete backup" in Settings → Sync, or for 30 days). If the copy fails, the cleanup doesn't start.
    5. `DONE`. Any failed step stays pending and retries after the next successful band sync. A pending cleanup shows a small "Finishing data cleanup…" note in Settings → Sync.
  - **Server changes (`homelab-server/app/main.py`):** `GET /restore?all=true` returns every table, paged per table by a `device_time` cursor (500 rows a page, including the `raw` field); add `POST /purge-seeded` and `DELETE /summaries`. Every endpoint requires the token. **The destructive endpoints (`/purge-seeded`, `DELETE /summaries`) return 503 if `SYNC_API_TOKEN` is unset on the server**, so they can never run unauthenticated. Before deploying, the server's SQLite file is copied to `jcwear_homelab.db.bak-<date>`. **Pre-check (D16 kept):** run `count(*)` per table on sterver first, and record the numbers in the deploy notes. They inform testing but don't remove the automated cleanup. The `DailySummaryPayload` and `daily_readiness_summaries` metric fields become nullable (`Optional[...]`, nullable columns), so days with gaps upload as `null`, never as defaults. The SQLAlchemy columns are already nullable (no `nullable=False`, main.py:116-128), so **no schema migration is needed**. The real bug is `default=0` on every column: SQLAlchemy writes 0 for `None` on INSERT. Remove the `default=`s from the summary columns and make the Pydantic fields `Optional[...] = None`. (Supersedes N5's table-rebuild migration.)
  - **Normal syncs always merge (upsert)**; nothing deletes server data except the purge and summary-delete calls above.
  - **Uploads cover every table.** `syncToHomelab` currently sends only `sleep_blocks.takeLast(30)`, `hrv_logs.takeLast(200)` and summaries (ClaudeCoachRepository.kt:51-108). Change it to upload all tables (sleep, hrv, hr, spo2, temp, steps) in batches of at most 500 rows. Track what's been sent with a local `uploaded INTEGER DEFAULT 0` column per table, set after the server confirms, **not** a `device_time` watermark, so band backfills with older timestamps still upload. Summaries are recomputed, so the last 30 days of summaries are always resent (cheap, idempotent merge). The local DB version bump adds the `uploaded` column with `ALTER TABLE`. Band writes switch from `CONFLICT_REPLACE` to **UPDATE-then-INSERT** in one transaction (`UPDATE ... SET <data columns> WHERE device_time = ?`, and `INSERT` if 0 rows changed). `ON CONFLICT DO UPDATE` needs SQLite 3.24 / API 30, but minSdk is 26 (build.gradle.kts:12). `uploaded` is left out of the update, so re-fetching the band's full history doesn't reset it. A row is re-marked `uploaded = 0` only if its data actually changed. Rows that came from the restore are inserted with `uploaded = 1`, so they aren't sent straight back.
  - **Tests:** a migration test on a fixture DB with real and seeded rows mixed (real survive, seeded go, counts logged); a restore-merge test (a server-only real row ends up local); server tests for `purge-seeded`, nullable summaries, the restore-all paging and the schema migration on an existing SQLite DB; a restore test where the server holds a seed copy of a key the phone has as a real row (the real row must survive); a test that a failed purge step is retried.
- **Coach and backup use real data only (R1):** `askClaudeCoach` and `syncToHomelab` take the real `DayMetrics`, never seeded reports. The token and URL come from `AppSettings` (they currently pass `""` and a literal URL).
- Remove every hardcoded value: Recovery/Daily overrides, battery defaults (75, 84), the fake connection string, "Last synced 6 min ago", "Last backup today, 06:42", the seeded coach message, `liveHr ?: 52`, `resultHrv ?: 66`, the morning-check RMSSD fallback `73.0` (J2208BleManager.kt:315; on failure, report "Check failed, try again" and store nothing), the firmware default `"v0.0.2.3"` (:51), `ScoreRingGauge(score = 85/88)` in the Morning Check flow.
- Replace `buildNinetyDaySeries` with a `DailySeriesRepository` that builds one `DayMetrics` per date **from real records only**, for all dates with data (not just 14). Every metric field is nullable; `null` means "no data", never a default.
- Empty states: fewer than 3 days → "Wear your band tonight to see your first scores"; fewer than 7 days of a metric → show the value but no "vs normal" chip ("Building your baseline: 4 of 7 days").
- Re-wire `AppSettings` for URL, token and API key (and later the profile).

### 1.2 Metric registry (#12)
One `MetricDef` per metric, used by Today, Trends, Detail and the coach context, so the rules live in one place:

```kotlin
enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER, CLOSER_TO_ZERO_IS_BETTER, NEUTRAL }
data class MetricDef(id, label, unit, direction, decimals, extract: (DayMetrics) -> Double?)
```
- Higher is better: HRV, Readiness, Recovery, Daily score, Sleep score, Sleep duration, Steps, Distance, SpO2.
- Lower is better: Resting HR.
- Closer to zero is better: Skin temp delta. Status rule: compare |value| with the baseline of |values|; NORMAL within 1 SD, otherwise BAD if |value| is larger, GOOD if smaller.
- Neutral: Strain (shown grey/amber, never "good" or "bad" by itself).

### 1.3 Baseline and comparison engine (#4, #5, #12 logic)
Pure Kotlin in `domain/`, unit tested:
- `baseline(metric, day)`: mean and SD of that metric over the 30 days **before** `day` (today excluded), using only days with data; needs ≥7 values.
- `status(value, baseline, direction)`: `NORMAL` if |value − mean| ≤ 1 SD; otherwise `GOOD` or `BAD` depending on direction. Neutral metrics are only ever `ABOVE`/`BELOW`.
- `extremeLabel(metric, day)`: walks back to find the last day with a more extreme value in the **same** direction → "Highest in 5 weeks" / "Lowest in 3 weeks" / "Highest on record". Fixes high outliers being called "lowest".
- Chip text comes from the same output: "↑ 12% vs your normal" plus the status color.
- **Normal-range floor (D14):** half-width = max(1 SD, the per-metric floor). Floors: SpO2 1 %, RHR 2 bpm, HRV 3 ms, sleep 20 min, steps 10 % of the mean, skin temp 0.2 °C, scores 3 pts. They live in `MetricDef.minNormalHalfWidth`.

### 1.4 Sleep: real times and a timing-aware score (#10)
- Carry the stitched `SleepSession` (start, end, stages, efficiency) into `DayMetrics` for its wake date. Sleep Detail shows the real "Fell asleep 03:12 · Woke 10:40" and draws the hypnogram from real stages.
- Sleep score v2 (`SleepAndReadinessEngine`), each part 0–100:
  - Duration vs 7.5 h target: 40%
  - Restorative (deep+REM share): 20%
  - Efficiency: 10%
  - All clock-time math uses **minutes since 12:00 noon** (so 23:50 = 710 and 00:10 = 730 sit next to each other). "Onset after 03:00" means onset between 03:00 and 12:00. The main sleep is the longest session whose onset falls between 18:00 and 12:00. Naps (onset 12:00–18:00) never get timing marks and don't count toward the score.
  - **Regularity: 15%.** The sleep midpoint's distance from your 14-day median midpoint. Full marks within 30 min, linear down to 0 at 3 h.
  - **Late-sleep timing: 15%.** Full marks if asleep by 02:00 (matching the user's decision), linear down to 0 by 04:00.
  - **Hard cap:** sleep onset after 03:00 → score capped at 70. A 3 AM bedtime can never read as a great night.
- Golden unit tests (fixtures with realistic stages: ~20% deep, ~22% REM, 92% efficiency): 23:00–06:30 regular night ≥ 85; 03:15–10:45 (same duration) ≤ 70; a short night is lower than a full one; no history yet → regularity is neutral (scored as full marks, with a note).

### 1.5 Sync state and battery (#8)
- `SyncState` sealed class in `J2208BleManager`: `Idle`, `Searching`, `Connecting`, `Syncing(step, progress)`, `Synced(at)`, `Failed(reason, at)`. Persist `lastSuccessfulSyncAt` and `lastBatteryPct/At` in `AppSettings`.
- Sync triggers in Phase 1: on app open (foreground) and pull-to-refresh. **Single-flight:** one app-wide `AppContainer` (created in a custom `Application` class in 1a) owns the single `LocalHealthDatabase`, `J2208BleManager` and `SyncCoordinator`. MainActivity (today it builds its own, MainActivity.kt:26-30) and the 1d Worker both use it. The `SyncCoordinator` guards every trigger **and the Morning Check** with one `Mutex`. A trigger during a running sync joins it instead of starting a second GATT session. For the lock to cover the real work, `J2208BleManager` gets a `suspend fun connect(timeout = 20 s)` that waits on a `CompletableDeferred` until service discovery completes. The GATT callback (`onReadyToSync`, :186-194) stops launching syncs itself, and `syncDeviceMetadataAndHistory` no longer calls `scanAndConnect()` and returns early (:234-237). Every sync ends with `gatt.disconnect()` + `gatt.close()` in a `finally`, including after a connect timeout (an unclosed GATT leaks and causes status 133). `BAND_NOT_FOUND` comes from that connect timeout, because the fast path `getRemoteDevice(KNOWN_BAND_MAC)` never fails, so a scan timeout alone would never fire.
- **No silent BLE failures (repair for #8):** replace the `catch (_: Exception) {}` blocks in `J2208BleManager` (:85, :108, :127, :136) with specific handling that maps to `Failed(reason)`. Reasons: `BLUETOOTH_OFF` (pill: "Bluetooth off · Turn on"), `PERMISSION_MISSING` ("Allow Bluetooth"), `BAND_NOT_FOUND` (scan timeout 20 s, "Band not found · Retry"), `GATT_ERROR(code)` (one automatic retry after 3 s, then "Couldn't connect · Retry"), `HISTORY_TIMEOUT` (the 25 s fetch timeout; keep the partial data and show "Sync incomplete · Retry"). Each is logged with its reason and code.
- **Recent syncs (D13):** keep the last 20 sync and backup events (time, trigger, result, reason, rows received/uploaded) as a JSON ring buffer in `AppSettings` (enough for one user, no new table), shown under Settings → Sync → "Recent syncs".
- **Band clock (D20):** the 02:00/03:00 sleep rules trust band timestamps (local time, no zone). Each sync reads `GetDeviceTime 0x41` (verified, PROTOCOL.md:129), records the drift in Recent syncs, and shows "Band clock is 7 min off" in Settings → Band when it's over 2 min. Writing `SetDeviceTime 0x01` (PROTOCOL.md:114, *not tested*) stays behind `AppSettings.clockWriteVerified = false` until it's verified once with `tools/j2208-band-tools`. After that, syncs auto-correct drift over 2 min.
- **Phase 1d (D3, after 1c; mechanism D21):** a one-time `CompanionDeviceManager` association with the band (a "Pair as companion" prompt in Settings → Band), plus a `PeriodicWorkRequest` every 30 min (WorkManager) that calls `SyncCoordinator.sync(trigger = BACKGROUND)`. **Timeout budget:** first measure a real full sync in 1c (Recent syncs records the duration). Today's worst case is about 170 s (a 20 s connect + 6 × 25 s history fetches, J2208BleManager.kt:244-281). Set the Worker timeout to the measured p95 + 30 s, capped at 9 min (WorkManager's limit is 10). CompanionDeviceManager is used for its exemption from background-start and standby restrictions on the band's MAC, so the periodic Worker isn't deferred indefinitely by App Standby buckets. Doze may delay runs; Recent syncs shows the actual times. No foreground service or permanent notification.
- After a successful band sync, upload to the homelab server (merge mode, all tables, with the token from `AppSettings`) and record `lastBackupAt`. Upload failures show in Settings → Sync ("Backup failed 2 h ago · Retry") but don't turn the Today pill red; the band sync status and the backup status are separate.
- Today header status pill, always visible:
  - `Syncing…` with a spinner
  - `Synced 2 min ago` (grey)
  - `Band not found · Retry` (amber, tappable)
  - `Last sync 9 h ago` (amber after 6 h)
- Battery: a band icon with a fill level and a number ("62%"), red below 20% with "Charge your band". Show "—" when unknown. Never a default value. Shown in the header next to the sync pill and in Settings → Band.

### 1.6 Greeting (#9)
`greetingFor(LocalTime)`: 05:00–11:59 "Good morning", 12:00–16:59 "Good afternoon", 17:00–21:59 "Good evening", otherwise "Hi". Uses the profile name, or no name if none is set. Recomputed when the app resumes, not only once at start.

## Phase 2: Trends that tell the truth

### 2.1 Trends tab from real data (#4)
- Each row comes from the registry plus the baseline engine: the 30-day average and today's value. **The value text is colored by status** (green good / red bad / grey normal), using direction.
- The sparkline is drawn in neutral grey, with today's dot in the status color, so the color has one meaning.
- The normal band in charts = mean ± 1 SD (not hardcoded ranges).

### 2.2 Smoothing done properly (#6)
- Rolling average over **real points only**. A window needs ≥2 real values; the line breaks across gaps of more than 1 day (no drawing through missing data).
- Window by range: Week = raw values only (no line); Month = 3-day; 3M = 7-day; Year = weekly averages.
- Raw daily dots always visible underneath; tap a dot for the exact value.
- Unit tests: a flat series stays flat; a single spike is softened but visible; gaps produce separate segments.

### 2.3 Sleep trends (#11)
The Sleep row in Trends opens a **Sleep Trends** page, not the single-night detail:
- Duration bars per night with the smoothed line and your normal band.
- Bedtime/wake-time range chart (a vertical bar per night from sleep to wake), which makes late nights obvious.
- Sleep score trend and the weekly stage mix (deep/REM/light %).
- Tap a night → that night's Sleep Detail.

### 2.4 Steps with distance (#3)
- Distance per day = the sum of the band's `distance_km` from step records. If it's missing or zero while steps > 0, fall back to `steps × stride`, where stride = height × 0.414 (needs height; otherwise show steps only).
- Steps card: "8,420 steps · 6.1 km" (mi when imperial), with a progress ring toward your normal.
- Units: the existing Metric/Imperial toggle in Settings (`useMetricUnits`, currently `remember` only) moves into `AppSettings` so it persists. It applies to distance, height, weight and temperature.

## Phase 3: Polish and profile

### 3.1 Numbers inside rings (#1)
- `ScoreRingGauge(value, label?)` draws the number centered inside the ring (and an optional small label below it). Remove the duplicate number printed beside the rings on Today, Sleep Detail and the Morning Check result.
- Ring color = score color; the number stays white for contrast.

### 3.2 Recovery summary at the bottom (#2)
- Move the Recovery card to the bottom of Today (after Activity).
- Full-width text, no `maxLines` or ellipsis. Only show a chevron when there really is a detail view behind it.

### 3.3 Profile in Settings (#7)
- Settings → Profile: Name, Height, Weight (metric/imperial aware, with sensible ranges and validation). Stored in `AppSettings`.
- Used by: the greeting, the distance fallback (height), and the calories fallback (weight, D10): the band's `calories_kcal` when present, otherwise steps × weight (kg) × 0.0005 kcal. Label it "est." when estimated.

## Testing
- New dependencies (all checked into `app/build.gradle.kts` in the phase that needs them): `androidx.lifecycle:lifecycle-viewmodel-compose` (1a), `androidx.work:work-runtime-ktx` (1d), test-only `org.jetbrains.kotlinx:kotlinx-coroutines-test`, `org.robolectric:robolectric`, `androidx.test:core`.
- **DB tests run on the JVM with Robolectric (D22):** `testImplementation("org.robolectric:robolectric:<current>")` + `androidx.test:core`; `LocalHealthDatabase`, `CleanupJob`, upsert, the asset import and migrations are tested with `./gradlew :app:testDebugUnitTest`. It's test-only and never ships in the APK.
- Unit tests (JVM) for the baseline/status/extreme-label engine, sleep score v2, smoothing with gaps, the distance fallback and the greeting boundaries.
- A seeded **test-only** fixture DB under `src/test`, never in `src/main` assets.
- Manual on-device checklist per phase: fresh install shows empty states (no fake numbers); a real night shows real times; Retry works with the band off; battery shows "—" before the first connection.

## Rough effort
Phase 1 is about 3–4 days (the BLE background sync is the biggest unknown), Phase 2 about 2 days, Phase 3 about 1 day.

## CEO review ledger (mode: SELECTIVE EXPANSION, 2026-11-12)

| ID | Contract and evidence | Current | Proposed | Status |
|---|---|---|---|---|
| R1 repair | "Real data only" must also cover the coach context and homelab upload: `coachRepo.askClaudeCoach(recentReports=...)`, `syncToHomelab(..., recentReports)` (MainDashboardScreen.kt:428, :471) both read seeded reports. | not in plan | Add to 1.1: coach and upload read only `DayMetrics` with real data; the token comes from `AppSettings`, not `""`. | approved (required repair to an approved invariant) |
| D3 | Background 30-min BLE sync is the biggest risk in Phase 1 (Android background BLE limits). | Phase 1 | Separate step after the trust fixes (now Phase 1d per D18) | approved: user answer D3 = B (scope: background sync only; on-open sync + status stay in Phase 1) |
| D4 | Fake rows already exist locally (seeder, LocalHealthDatabase.kt:317) and possibly on sterver (summaries upserted from mixed data, main.py `sync_batch` merge). | Open question 1 | Precise delete + server cleanup | approved: user answer D4 = A; method reopened by D9 |
| D9 | Spec review: replace-sync could delete real nights that survive only on sterver. | D4 replace mode | Restore first, delete only rows tagged seed/demo on both sides, recompute summaries | approved: user answer D9 = A (supersedes D4's replace mode) |
| D10 | Calories source (open question 3; band already sends `calories_kcal`). | open | Band first, weight-based fallback, no age/sex | approved: user answer D10 = A |
| S1–S13 | Spec review fixes: nullable server summaries, merge for normal syncs, all-table upload, cleanup retry state, threshold consistency, |value| rule for skin temp, persisted units toggle, test fixtures. | — | Applied in 1.1, 1.2, 1.4, 1.5, 2.4, 3.3 | approved (clarifications and required repairs within approved scope) |
| N1–N6 | Spec re-review: insert-only restore, upload tracking by flag not watermark, `raw` field in restore, pending restore without URL, server schema migration for SQLite/Postgres, paged restore. | — | Applied in 1.1 | approved (required repairs within approved D9 scope) |
| D5 | Expansion: data provenance ("Based on 23 nights") | — | Add / Defer / Skip | deferred: user answer D5 = B (TODOS.md) |
| D6 | Expansion: morning "scores ready" notification after auto-sync | — | Add / Defer / Skip | deferred: user answer D6 = B (TODOS.md) |
| D7 | Expansion: bedtime reminder from the timing score | — | Add / Defer / Skip | deferred: user answer D7 = B (TODOS.md) |
| D8 | Expansion: CSV export of all data | — | Add / Defer / Skip | deferred: user answer D8 = B (TODOS.md) |

| D11 | Scope document approval | — | Approve plan + CEO summary | approved: user answer D11 = A |
| D12 | Section 1/5: whole-DB reads in composition on the main thread (MainDashboardScreen.kt:277); 2,512-line file | — | ViewModel + IO loading + per-screen split | approved: user answer D12 = A |
| D13 | Section 8: sync failures leave no trace | — | Recent syncs (last 20) in Settings | approved: user answer D13 = A (storage: prefs JSON, implementation detail) |
| D14 | Section 4: SD≈0 makes colors flicker | ±1 SD | max(1 SD, per-metric floor) | approved: user answer D14 = A |
| R2–R5 | Required repairs: single-flight sync, no silent BLE failures (J2208BleManager.kt:85/108/127/136), pre-cleanup DB copy, destructive endpoints refuse without server token | — | Applied in 1.1 and 1.5 | approved (repairs within approved #8 and D9 guarantees) |
| D15 | Outside voice (verified): the asset DB is real band history; key-list deletion would erase it | D9 key list | Delete only `raw_hex = "seed"` rows | approved: user answer D15 = A (supersedes D9's key list) |
| D16 | Outside voice: sterver is probably empty, so the automated cleanup may be unnecessary | automated | Keep the automated server cleanup | approved: user answer D16 = B (keep automated) |
| D17 | Outside voice: the fake seeder still runs on every launch | wait for Phase 1 | Phase 0 hotfix: stop the seeder; asset import becomes insert-if-missing | approved: user answer D17 = A |
| D18 | Outside voice: Phase 1 too large to debug | one release | 1a refactor → 1b trust → 1c sync → 1d background | approved: user answer D18 = A |
| R6–R9 | Outside-voice repairs: suspend connect with timeout (single-flight feasibility), upsert that preserves `uploaded`, extra hardcoded fakes (RMSSD 73.0, firmware), minutes-from-noon time math, band clock check | — | Applied in 1.1, 1.4, 1.5 | approved (repairs within approved scope) |

| D19 | Eng Scope Challenge: 7 new classes | 7 classes | Smaller arrangement: 5 new + HomelabClient moved out of ClaudeCoachRepository | approved: user answer D19 = B |
| D20 | Band clock: 0x41 verified, 0x01 untested (PROTOCOL.md:114, :129) | open | Read drift every sync; write only after a one-time verification | approved: user answer D20 = A |
| D21 | Background sync mechanism | open | CompanionDeviceManager + WorkManager | approved: user answer D21 = A |
| D22 | DB tests need Android SQLite (`LocalHealthDatabase : SQLiteOpenHelper`) | none | Robolectric, test-only | approved: user answer D22 = A |
| D23 | Regression rule: the 1a refactor has zero UI coverage | none | Golden of the UI-state values before and after 1a | approved: user answer D23 = A |
| E1–E9 | Eng outside voice (verified): server `default=0` (no migration needed), UPSERT needs API 30 (minSdk 26), injectable clock for the golden, measured timeout budget, WAL-safe copy, restore excludes seed + LOCAL_DELETE in 1b, single AppContainer + Morning Check under the Mutex + GATT close, deps list, asset ATTACH, nap window. #8 (sterver empty) restates D16, so D16 is kept and a count pre-check added. | — | Applied in 1.0, 1.1, 1.4, 1.5, Phase 0, Testing | approved (repairs within D9/D12/D16/D21/D23 scope) |

Approval readiness: PASS (checked R1, D3, D4/D9→D15, D5–D8 deferred, D10–D23, S1–S13, N1–N6 (N5 superseded by E1), R2–R9, E1–E9; each cites its user answer or repair basis above)

## NOT in scope
- Data provenance line under scores. Deferred (D5) to TODOS.md: the user chose to keep this release smaller.
- Morning "scores ready" notification. Deferred (D6): depends on Phase 1d background sync.
- Bedtime reminder. Deferred (D7): needs a validated timing score and notifications first.
- CSV export. Deferred (D8): sterver already holds a backup.

## Open questions
1. ~~How to clean fake rows~~ → resolved by D4/D9 (restore first, delete tagged fakes).
2. ~~Background sync mechanism~~ → resolved by D21 (CompanionDeviceManager + WorkManager).
3. ~~Calories~~ → resolved by D10 (band first, weight fallback).

---

# CEO review output (2026-11-13, /plan-ceo-review, SELECTIVE EXPANSION)

## Reality rows
REALITY: premise-table pass All 12 root causes are cited to code (MainDashboardScreen.kt:138-179, :257, :277, :471; LocalHealthDatabase.kt:317, :370-396; J2208BleManager.kt:48, :85-136, :315). The "demo DB" claim was corrected by a measured comparison: the asset is the real band dump, with 0 seed rows. unverified=1 (whether sterver holds any data; D16 chose automated handling)
REALITY: already-done n/a No prior commits touch this scope (HEAD a95065c)
REALITY: surface-check finding Server routes `/restore?all`, `/purge-seeded` and `DELETE /summaries` don't exist yet in main.py; they're planned as new work in 1.1
REALITY: binding-decisions pass Ledger D3–D18 is recorded with user answers; there are no prior gstack decisions
REALITY: numbers pass Asset row counts 550/204/1792/3584/2560/1920 [measured: sqlite]; effort figures [estimated]
REALITY: deferred-asks pass No linked issue; D5–D8 are deferred to TODOS.md
REALITY: pr-count pass Phases 0, 1a, 1b, 1c and 1d ship as separate builds (D17, D18)
PLAN_REALITY: 7/7 rows, 1 unverified

## What already exists (reused)
- `SleepAndReadinessEngine.stitchSleepSessions` (post-noon stitcher): feeds real session times into Sleep Detail.
- `J2208Protocol` / `J2208BleManager` GATT plumbing: gains `suspend connect()` and `SyncState`.
- `LocalHealthDatabase` schema (`distance_km`, `calories_kcal`, `device_time` PKs): reused, plus the `uploaded` column.
- `AppSettings`: gains profile, units, sync state, cleanup state and Recent syncs.
- `ScoreRingGauge`, `HealthCard`, `ContextChip`: the ring gets a centered number.
- Server `sync_batch` merge and `/restore`: restore gains paging and `?all`.

## Dream state delta
Today: a demo with real BLE plumbing, where fake rows and hardcoded scores drive the UI and the coach. After this plan: every number comes from your own band data, colors and labels follow each metric's direction and your personal baseline, sync status is explicit, and phone and sterver hold the same clean history. Still missing for the 12-month ideal: provenance per score (D5), nudges (D6, D7), export (D8), and background sync is still unproven (1d).

## Error & Rescue Registry
```
METHOD/CODEPATH              | WHAT CAN GO WRONG               | CLASS               | RESCUE                                | USER SEES
-----------------------------|---------------------------------|---------------------|---------------------------------------|---------------------------------
J2208BleManager.connect()    | Bluetooth off                   | BLUETOOTH_OFF       | none                                  | "Bluetooth off · Turn on"
                             | permission denied               | PERMISSION_MISSING  | request permission                    | "Allow Bluetooth"
                             | band absent / no callback       | BAND_NOT_FOUND 20s  | user Retry                            | "Band not found · Retry"
                             | GATT status 133 etc.            | GATT_ERROR(code)    | 1 auto retry after 3 s                | "Couldn't connect · Retry"
syncDeviceMetadataAndHistory | 25 s history fetch timeout      | HISTORY_TIMEOUT     | keep partial rows                     | "Sync incomplete · Retry"
startMorningCheck60s         | no or too few PPI intervals     | MORNING_CHECK_FAILED| store nothing (was a fake 73.0)       | "Check failed, try again"
HomelabClient.upload         | no URL configured               | NOT_CONFIGURED      | stays pending                         | Settings: "Set up backup"
                             | 401 bad token                   | HTTP_401            | stop, log                             | "Backup: token rejected"
                             | timeout / Tailscale off         | IOException         | retry on next sync                    | "Backup failed 2 h ago · Retry"
                             | 5xx                             | HTTP_5XX            | retry on next sync                    | same
CleanupJob step 0 (DB copy)  | disk full / IO error            | IOException         | abort, nothing deleted                | Settings note + Recent syncs
CleanupJob steps 1/3/4       | server unreachable              | IOException / HTTP  | step stays pending, retried           | "Finishing data cleanup…"
Server destructive endpoints | SYNC_API_TOKEN unset            | 503                 | refuse                                | Recent syncs: "Server not secured"
Server startup migration     | rebuild fails mid-way           | sqlite3.Error       | rollback; .bak copy exists            | server keeps old schema, logs error
DashboardViewModel load      | DB read error                   | SQLiteException     | keep last good state                  | "Couldn't load data" banner
```
Every previous `catch (_: Exception) {}` site is replaced by a named class above. 0 unrescued paths.

## Failure Modes Registry
```
CODEPATH        | FAILURE MODE                          | RESCUED?                          | TEST?        | USER SEES?               | LOGGED?
----------------|---------------------------------------|-----------------------------------|--------------|--------------------------|--------
Phase 0 import  | asset import runs twice               | Y (flag + INSERT OR IGNORE)       | Y            | nothing                  | Y
Cleanup         | real row deleted                      | Y (seed tag only + pre-copy)      | Y            | nothing                  | Y
Restore         | server seed copy overwrites real row  | Y (insert-if-missing)             | Y            | nothing                  | Y
Upload          | full history re-uploaded every sync   | Y (upsert keeps uploaded)         | Y            | nothing                  | Y
SyncCoordinator | two GATT sessions at once             | Y (Mutex + suspend connect)       | Y            | one spinner              | Y
Baseline        | SD≈0 makes colors flicker             | Y (D14 floor)                     | Y            | grey                     | n/a
Sleep timing    | midnight wrap in the median           | Y (minutes from noon)             | Y            | correct score            | n/a
Sleep timing    | DST night is 1 h off                  | N (accepted, rare)                | Y (doc test) | duration off 1 h once/yr | N
Band clock      | band clock drifted                    | Y if protocol allows              | pending eng  | drift in Recent syncs    | Y
```
CRITICAL GAPS: 0.

## Scope Expansion Decisions
- Accepted: none of D5–D8. R1, D9→D15, D10 and D12–D18 are repairs or in-scope decisions.
- Deferred: D5 provenance, D6 morning notification, D7 bedtime reminder, D8 CSV export (TODOS.md).
- Skipped: none.

## Diagrams

SyncState (1c):
```
 Idle --trigger--> Connecting --services ok--> Syncing(step) --done--> Synced(at)
   ^                 | timeout / BT off / perm      | timeout / GATT err      |
   |                 v                               v                         | trigger
   +----------- Failed(reason, at) <---------------+                          v
                     +--------------- Retry ---------------------------> Connecting
 Invalid: Syncing -> Syncing (blocked by the Mutex); Synced -> Syncing without Connecting
```

CleanupState (1b/1c):
```
 BACKUP_COPY --ok--> RESTORE_PENDING --ok / no URL--> LOCAL_DELETE --> SERVER_DELETE_PENDING --> SUMMARIES_PENDING --> DONE
   | fail: abort, nothing deleted   | fail: stays, retried after the next sync   | fail: stays             | fail: stays
 Invalid: LOCAL_DELETE before BACKUP_COPY (enforced by order); any step after DONE
```

Deploy sequence:
```
 1 Phase 0 APK -> 2 sterver: cp jcwear_homelab.db .bak, deploy server (migration, routes) -> 3 check /health, and that purge
 returns 503 without a token -> 4 1a APK -> 5 1b APK (cleanup runs) -> 6 1c APK -> 7 1d APK
```

Rollback:
```
 Server broken -> previous image + restore jcwear_homelab.db.bak-<date> (~2 min)
 App broken    -> previous APK; DB downgrade unsupported -> clear app data; the restore pulls history back from sterver
 Cleanup wrong -> close the helper, delete live -wal/-shm, copy jcwear_local_health.pre-cleanup.db over the live DB (kept 30 days)
```

## Stale Diagram Audit
The touched files (MainDashboardScreen.kt, LocalHealthDatabase.kt, J2208BleManager.kt, SleepAndReadinessEngine.kt, main.py) contain no ASCII diagrams. 0 stale.

## Implementation Tasks
Synthesized from this review's findings. Each task derives from a specific finding above. Checkbox them as you ship.

- [x] **T0 (P1, human: ~1h / CC: ~10min)** — data — Phase 0 hotfix: remove the fake seeder call; make the asset copy an INSERT OR IGNORE one-time import
  - Surfaced by: Outside voice #3, D17
  - Files: MainActivity.kt, data/LocalHealthDatabase.kt, data/AppSettings.kt
  - Verify: fresh install shows July–Nov history; a second launch adds 0 rows
- [ ] **T1 (P1, human: ~1d / CC: ~45min)** — ui — 1a: DashboardViewModel + IO loading + per-screen split, no behavior change
  - Surfaced by: Sections 1/5, D12, D18
  - Files: ui/MainDashboardScreen.kt → ui/today, ui/trends, ui/sleep, ui/settings, ui/components, ui/DashboardViewModel.kt; app/build.gradle.kts
  - Verify: `./gradlew :app:assembleDebug` green; identical screens on device
- [ ] **T2 (P1, human: ~1d / CC: ~40min)** — domain — 1b: DailySeriesRepository (nullable, real only), MetricRegistry, baseline/status/extremeLabel with D14 floors
  - Surfaced by: root-cause table, 1.1–1.3, D14
  - Files: domain/DailySeriesRepository.kt, domain/MetricInsights.kt, src/test/.../MetricInsightsTest.kt
  - Verify: `./gradlew :app:testDebugUnitTest` (a high outlier says "Highest"; lower RHR is GOOD; a flat series stays grey)
- [ ] **T3 (P1, human: ~4h / CC: ~25min)** — domain — 1b: Sleep score v2 (minutes-from-noon timing, 03:00 cap); real times in Sleep Detail
  - Surfaced by: #10, Outside voice #7
  - Files: domain/SleepAndReadinessEngine.kt, ui/sleep, src/test/.../SleepScoreTest.kt
  - Verify: golden tests (23:00–06:30 ≥ 85; 03:15–10:45 ≤ 70; 23:50/00:10 midpoints adjacent)
- [ ] **T4 (P1, human: ~4h / CC: ~25min)** — data — 1b: remove all hardcoded fakes; greeting; AppSettings wiring for URL, token and units
  - Surfaced by: 1.1 list, Outside voice #6
  - Files: ui/*, ble/J2208BleManager.kt, data/AppSettings.kt
  - Verify: fresh state shows "—" and empty states; a test asserts none of the known literals remain
- [ ] **T5 (P1, human: ~1d / CC: ~45min)** — data+server — 1b/1c: CleanupJob, upsert that keeps `uploaded`, all-table batched upload, server routes + migration
  - Surfaced by: D4/D9/D15/D16, N1–N6, Outside voice #5
  - Files: data/LocalHealthDatabase.kt, data/CleanupJob.kt, ai/ClaudeCoachRepository.kt, homelab-server/app/main.py, homelab-server/test_server.py
  - Verify: migration test (real rows survive, seed rows go); server pytest for purge, paging, 503 and the migration
- [ ] **T6 (P1, human: ~1d / CC: ~45min)** — ble — 1c: suspend connect + timeout, SyncCoordinator Mutex, SyncState with named failures, battery, Recent syncs, band clock
  - Surfaced by: #8, Section 2, Outside voice #4, D13
  - Files: ble/J2208BleManager.kt, ble/SyncCoordinator.kt, ui/today, ui/settings
  - Verify: fake-GATT unit test (two triggers → one session); on device, BT off → "Bluetooth off" pill
- [ ] **T7 (P2, human: ~1d / CC: ~40min)** — ui — Phase 2: Trends from the registry, gap-aware smoothing, Sleep Trends page, distance
  - Surfaced by: #3, #4, #6, #11
  - Files: ui/trends, ui/sleep, domain/Smoothing.kt + tests
  - Verify: smoothing tests (flat stays flat, gaps break the line); colors follow direction
- [ ] **T8 (P2, human: ~4h / CC: ~20min)** — ui — Phase 3: numbers inside rings, Recovery summary at the bottom, Profile + calories fallback
  - Surfaced by: #1, #2, #7, D10
  - Files: ui/components, ui/today, ui/settings
  - Verify: on device; profile validation unit test
- [ ] **T9 (P2, human: ~1d / CC: ~45min)** — ble — 1d: background 30-min sync (mechanism decided in eng review)
  - Surfaced by: D3, D18
  - Files: to be determined
  - Verify: phone locked for 2 h → Recent syncs shows about 4 background syncs

## Completion Summary
```
  +====================================================================+
  |            MEGA PLAN REVIEW — COMPLETION SUMMARY                   |
  +====================================================================+
  | Mode selected        | SELECTIVE EXPANSION                         |
  | System Audit         | fake/hardcoded data drives UI; BLE errors   |
  |                      | swallowed; asset DB is real band history    |
  | Step 0               | SELECTIVE; R1, D3, D4→D9→D15, D10 accepted; |
  |                      | D5–D8 deferred                              |
  | Section 1  (Arch)    | 3 issues found                              |
  | Section 2  (Errors)  | 15 error paths mapped, 5 GAPS (fixed)       |
  | Section 3  (Security)| 2 issues found, 1 High severity             |
  | Section 4  (Data/UX) | 10 edge cases mapped, 1 unhandled (DST)     |
  | Section 5  (Quality) | 2 issues found                              |
  | Section 6  (Tests)   | Diagram produced, 0 gaps                    |
  | Section 7  (Perf)    | 1 issue found                               |
  | Section 8  (Observ)  | 1 gap found                                 |
  | Section 9  (Deploy)  | 3 risks flagged                             |
  | Section 10 (Future)  | Reversibility: 3/5, debt items: 2           |
  | Section 11 (Design)  | 3 issues                                    |
  +--------------------------------------------------------------------+
  | NOT in scope         | written (4 items)                           |
  | What already exists  | written                                     |
  | Dream state delta    | written                                     |
  | Error/rescue registry| 15 rows, 0 CRITICAL GAPS                    |
  | Failure modes        | 9 total, 0 CRITICAL GAPS                    |
  | TODOS.md updates     | 4 items proposed                            |
  | Scope proposals      | 4 proposed, 0 accepted (EXP + SEL)          |
  | CEO plan             | written                                     |
  | Outside voice        | codex not installed; native fallback done   |
  | Lake Score           | N/A                                         |
  | Diagrams produced    | 5 (arch, 2 state machines, deploy, rollback)|
  | Stale diagrams found | 0                                           |
  | Unresolved decisions | 2 (listed below)                            |
  | Reality rows         | 7/7 rows (PLAN_REALITY), 1 unverified       |
  +====================================================================+
```
Section 11 notes: the loading, empty, error and partial states for Today, Trends and Sleep Trends are only partly specified, and the new screens (Sleep Trends, Recent syncs, Profile) have no layouts. Run /plan-design-review before building Phase 2/3.

### Unresolved Decisions
- Background sync mechanism (CompanionDeviceManager + WorkManager vs foreground service). Owner: eng review, before 1d.
- Whether the J2208 protocol has a band clock set command. Owner: eng review, before 1c.

---

# Eng review output (2026-11-13, /plan-eng-review, FULL_REVIEW)

Scope Challenge: scope accepted as-is (structure: the smaller arrangement, D19).

## Reality rows (eng)
REALITY: premise-table pass Verified: SQLAlchemy summary columns are nullable with `default=0` (main.py:116-128); minSdk 26 (build.gradle.kts:12); MainActivity builds its own manager (MainActivity.kt:26-30); 0x41 works and 0x01 is untested (PROTOCOL.md:114, :129); asset `user_version` 0. unverified=2 (real full-sync duration; WAL mode on device)
REALITY: already-done n/a HEAD a95065c; no commits implement any task
REALITY: surface-check finding WorkManager, ViewModel-compose and Robolectric are not yet in build.gradle.kts:46-56; listed in Testing
REALITY: binding-decisions pass D1–D23 honored; D16 kept despite the outside-voice push to cut (no new evidence)
REALITY: numbers pass worst-case sync about 170 s derived from the code timeouts [estimated]; real duration [re-measure before gate] in 1c
REALITY: deferred-asks pass no linked issue
REALITY: pr-count pass separate builds: Phase 0, 1a, 1b, 1c, 1d, 2, 3
PLAN_REALITY: 7/7 rows, 2 unverified

## Findings by section
**1. Architecture**
- [P1] (confidence: 9/10) J2208BleManager.kt:186-194, :234-237: the sync can't be awaited, so the Mutex can't single-flight it. Resolved by R6 and E7 (suspend connect, one AppContainer).
- [P2] (confidence: 8/10) ClaudeCoachRepository.kt:51-108: coach and backup are mixed in one class. Resolved by D19 (HomelabClient moved out).
- [P2] (confidence: 8/10) MainActivity.kt:26-30: there's no app-wide container, so a Worker would get a second GATT client. Resolved by E7.
Dispositions: all accepted (R6, D19, E7).

**2. Code quality**
- [P1] (confidence: 9/10) J2208BleManager.kt:85, :108, :127, :136: `catch (_: Exception) {}`. Resolved by R3 (named failures).
- [P2] (confidence: 8/10) MainDashboardScreen.kt (2,512 lines). Resolved by D12/D18 (1a split).
- [P2] (confidence: 8/10) main.py:116-128: `default=0` turns missing data into zeros. Resolved by E1.
Dispositions: all accepted.

**3. Tests.** Diagram below; gaps are closed by D22, D23 and the Test Plan artifact.

**4. Performance**
- [P2] (confidence: 8/10) MainDashboardScreen.kt:277-304: full-table reads on every refresh, inside composition. Resolved by D12 (IO dispatcher, date-ranged queries).
- [P3] (confidence: 6/10) Restore over Tailscale: about 10.6k rows total (asset counts) at 500 per page, about 22 requests. Acceptable. Medium confidence; verify on the real network.
Dispositions: accepted / no action.

## Test coverage diagram
```
CODE PATHS                                             USER FLOWS
[+] Phase 0 asset import                               [+] First launch / reinstall
  ├── [GAP→★★★ planned] import once, keep local rows      └── [GAP→★★ planned] history visible, no fakes
[+] CleanupJob (copy, restore, delete, purge, sums)    [+] Cleanup in 1b/1c
  ├── [GAP→★★★] seed-only delete, pre-copy first          ├── [GAP→★★] fakes gone after 1b
  ├── [GAP→★★★] restore insert-if-missing, no seed        └── [GAP→★★] server matches after 1c
  └── [GAP→★★★] failed step retried
[+] Band write upsert (UPDATE-then-INSERT)             [+] Sync
  └── [GAP→★★★] uploaded flag kept on re-fetch            ├── [GAP→★★★] two triggers → one session (fake GATT)
[+] MetricInsights (registry/baseline/smoothing)         ├── [GAP→★★] BT off / permission / not found pills
  ├── [GAP→★★★] direction, floors, extreme labels         └── [GAP→★★] clock drift shown, no 0x01 write
  └── [GAP→★★★] gaps break smoothing
[+] Sleep score v2                                     [+] Trends / Sleep Trends (manual on device)
  └── [GAP→★★★] goldens, midnight, nap window
[+] 1a refactor                                        [+] Server endpoints (pytest)
  └── [GAP→★★★] UI-state golden with a fixed Clock        └── [GAP→★★★] purge 503, paging, Optional fields
[+] Existing: J2208EngineTest.kt (protocol parsing)  [★★ TESTED, unchanged]
COVERAGE today: 1/12 paths; planned: 12/12 with ★★-★★★ tests | E2E: none (single user; on-device checklist per phase)
Legend: ★★★ behavior + edge + error | ★★ happy path | [GAP→...] planned test closes the gap
```

## Failure modes (eng additions)
```
CODEPATH         | FAILURE MODE                          | RESCUED?                         | TEST? | USER SEES?                | LOGGED?
-----------------|---------------------------------------|----------------------------------|-------|---------------------------|--------
1d Worker        | sync exceeds the Worker time budget   | Y (budget = measured p95 + 30 s) | Y     | Recent syncs: "timed out" | Y
Band upsert      | ON CONFLICT unsupported below API 30  | Y (UPDATE-then-INSERT)           | Y     | nothing                   | n/a
Pre-cleanup copy | WAL contents missing from the copy    | Y (checkpoint + VACUUM INTO)     | Y     | nothing                   | Y
GATT             | leaked connection after a timeout     | Y (finally disconnect + close)   | Y     | nothing                   | Y
Server summaries | None stored as 0                      | Y (defaults removed)             | Y     | gaps shown as gaps        | n/a
```
CRITICAL GAPS: 0.

## Worktree parallelization strategy
| Step | Modules touched | Depends on |
|------|-----------------|------------|
| Phase 0 hotfix | data/, MainActivity | — |
| 1a refactor | ui/, app container, test/ | Phase 0 |
| Server changes | homelab-server/app, tests | — |
| 1b trust | domain/, data/, ui/ | 1a |
| 1c sync | ble/, data/, ui/settings | 1b, server changes |
| 1d background | ble/, work/ | 1c |
| Phase 2 / 3 | ui/, domain/ | 1b |

**Parallel lanes:** Lane A: Phase 0 → 1a → 1b → 1c → 1d (shared ui/, data/). Lane B: server changes (independent homelab-server/).
**Execution order:** launch A and B together. B must merge before 1c. Phase 2/3 start after 1b, in sequence with 1c because both touch ui/.
**Conflict flags:** ui/ is shared by 1b, 1c and Phase 2/3, so sequence them.

## Eng task amendments (apply to the CEO task list above)
- **T0:** the asset import uses a cache copy + ATTACH (E9).
- **T1:** step zero extracts `buildDashboardState(db, clock)` unchanged and records the golden with a fixed Clock (E3/D23); add `AppContainer` + `Application` (E7); add lifecycle-viewmodel-compose, Robolectric and coroutines-test (D22).
- **T3:** exclude the 12:00–18:00 nap window (E9).
- **T5:** UPDATE-then-INSERT (E4); `VACUUM INTO` pre-copy (E5); LOCAL_DELETE in 1b, with restore excluding seed rows (E6); server: remove `default=` and make fields Optional instead of migrating (E1); `count(*)` pre-check on sterver.
- **T6:** Morning Check under the Mutex; `finally` disconnect + close (E7); read 0x41 drift, keep 0x01 gated (D20); record every sync's duration (E2).
- **T9:** CompanionDeviceManager + WorkManager (D21); timeout = measured p95 + 30 s (E2); add work-runtime-ktx.

## Completion summary (eng)
- Step 0: Scope Challenge: scope accepted as-is
- Reality rows: 7/7 applicable rows emitted (PLAN_REALITY), 2 unverified premises
- Architecture Review: 3 issues found
- Code Quality Review: 3 issues found
- Test Review: diagram produced, 11 gaps identified (all planned)
- Performance Review: 2 issues found
- NOT in scope: written (CEO section; plus no CI/CD for APK builds, since it's a sideloaded single-user app)
- What already exists: written
- TODOS.md updates: 0 items proposed to user
- Failure modes: 0 critical gaps flagged
- Unresolved decisions: 0 in this review
- Outside voice: codex not installed; native Claude Plan fallback completed (9 findings, all resolved as E1–E9; D16 kept)
- Parallelization: 2 lanes, 2 parallel / 5 sequential
- Lake Score: 3/3 (D20, D22 and D23 were scored, and each answer picked its top-scored option)

---

# Design spec (2026-11-13, /plan-design-review; approved decisions DS1–DS8)

## Today screen hierarchy (DS1, verdict-first)
```
┌──────────────────────────── 360dp ─────────────────────────────┐
│ Good morning, Stav                  [● Synced 2 min ago] [▮ 62%] │  1 header: greeting (by time of day), sync pill (live region), battery
│ ┌ Blood oxygen low overnight: 91% · Details ›                 ┐ │  2 alert (only when SpO2/skin temp out of range; red, tappable card)
│ ┌ Morning check · 60 s · Start ›                              ┐ │  3 Morning Check prompt (mornings until done; tappable card)
│ Well recovered. Good day for a hard session.               ›    │  4 coach verdict (one line, opens the reasons sheet)
│      ( 82 )                         ( 76 )                       │  5 Daily + Recovery rings, numbers inside, no card, no glow
│      Daily ↑ 6%                     Recovery ↑ 4%                │    chip under each: color + arrow
│ ───────────────────────────────────────────────────────────────│
│ Activity   8,420 steps · 6.1 km   [====|////normal////|    ]    │  6 Activity row (distance "est." when computed)
│ ───────────────────────────────────────────────────────────────│
│ Recovery                                                         │  7 Recovery summary, full width, bottom, never truncated
│ HRV 64 ms, 8% above your normal. Resting HR steady at 51.        │    template sentence (DS8)
│ HRV 64 ms ↑  ▁▂▃▅▆▅▇      Resting HR 51 bpm –  ▅▅▄▅▅▅▅          │    tap a vital → its detail
├─────────────────────────────────────────────────────────────────┤
│   Today        Trends        Coach        Settings               │  bottom nav
└─────────────────────────────────────────────────────────────────┘
```

## Trends and Sleep Trends (DS2, ranked list rows)
```
Trends                                  [Calendar]
[ Week | Month | 3M | Year ]
Sleep        7h 12m  ↓ Worse   avg 7h 31m   ╱╲╱‾╲_   ›   ← Sleep row first
HRV          64 ms   ↑ Better  avg 59 ms    _╱‾╲╱‾   ›
Resting HR   51 bpm  – Normal  avg 52 bpm   ‾‾╲_╱‾   ›
...one row per registry metric, 56dp tall, hairline separators, no cards

Sleep Trends:  duration bars (smoothed line + normal band) → bed/wake range chart → score line → weekly stage mix → tap a night = Sleep Detail
```

## Interaction states (DS3)
```
FEATURE          | LOADING                 | EMPTY                                        | ERROR                              | SUCCESS          | PARTIAL
-----------------|-------------------------|----------------------------------------------|------------------------------------|------------------|--------------------------------------------
Today rings      | ring outlines, no number| "Wear your band tonight. Your first scores   | "Couldn't load today · Retry"      | numbers + chips  | number shown, chip replaced by
                 | (skeleton)              |  appear tomorrow morning."                   |  (inline under header)             |                  | "Building your baseline: 4 of 7 nights"
Coach verdict    | grey bar skeleton       | hidden                                       | hidden (rings still show)          | one line         | "Not enough data for advice yet"
Activity         | bar skeleton            | "No steps yet today"                         | inline Retry                       | steps · km       | steps only if distance unknown
Recovery summary | 2 grey lines            | "Recovery appears after your first night."   | inline Retry                       | template + vitals| missing vital shows "—"
Trends rows      | 6 row skeletons         | "Trends start after 3 nights of data."       | inline Retry                       | rows             | row shows value, no chip/arrow until 7 days
Sleep Detail     | hypnogram skeleton      | "No sleep recorded for this night."          | inline Retry                       | real times+stages| times shown, stages "not recorded"
Sleep Trends     | chart skeletons         | "Sleep trends appear after 3 nights."        | inline Retry                       | charts           | gaps break lines
Coach chat       | typing dots in bubble   | 3 suggested-question chips                   | "Coach is offline. Your data is    | reply bubble     | —
                 |                         |                                              |  safe; try again later."           |                  |
Recent syncs     | —                       | "No syncs yet. Open the app near your band." | entries show reason + Retry        | list             | —
```
Rules: no full-screen spinners and no dialogs for errors. The sync pill is the only spinner.

## Journey storyboard (Pass 3, from approved decisions)
```
STEP | USER DOES                    | USER FEELS              | PLAN SPECIFIES
-----|------------------------------|-------------------------|---------------------------------------------
1    | Opens app on waking          | "How am I?"             | Verdict + 2 rings above the fold (DS1)
2    | Sees "Syncing…" pill         | Trust that it's current | Pill states, battery (1.5)
3    | Taps Daily ring              | Curious why             | Composition: Readiness/Sleep/Strain (existing)
4    | Bad night: red alert         | Concern, wants action   | Alert first, plain words, Details › (DS1)
5    | Opens Trends weekly          | Am I improving?         | Ranked rows, color+arrow+word (DS2, DS6)
6    | 3 weeks later, late nights   | Honest feedback         | Timing-aware score, bed/wake chart (1.4, 2.3)
```

## Visual rules (DS4)
- Rings sit directly on the background as the single visual anchor. No card and no radial glow: remove `showRadialGlow` (MainDashboardScreen.kt:2049).
- Sections are full-width, separated by 24dp spacing and a 1dp hairline (`BorderHairline`).
- Cards only for tappable prompts: the alert, Morning Check, and a bottom-sheet content container.

## Design system (DS5)
Create `DESIGN.md` at the repo root and one `ui/theme/HealthTheme.kt`, both built from the existing `HealthColors`:
- Type: Manrope (display 48/34, title 20), Inter (body 16, label 13), with `fontFeatureSettings = "tnum"` on every metric number. Bundle both fonts in `res/font`.
- Spacing on an 8dp grid with 16dp screen padding. Radius 16 for tappable cards only.
- Status colors: good `ScoreGreen`, bad `ScoreCoral`, normal `TextSecondary`. Alerts only use `AlertRed`.

## Accessibility (DS6, DS7)
- Status = color + arrow (↑ ↓ –) + a word on detail views (Better / Normal / Worse).
- Rings: `contentDescription` like "Daily score 82, up 6 percent vs your normal". Charts: a one-line summary ("HRV last 30 days, average 61, highest 72").
- Sync pill: `liveRegion = Polite`. Touch targets ≥ 48dp. Layouts verified at 200% font scale; ring numbers auto-shrink to fit. Robolectric semantics tests assert the descriptions.

## Recovery summary text (DS8)
Built by a template in `MetricInsights` from real values, at most 2 sentences, never truncated. For example: "HRV 64 ms, 8% above your normal. Resting HR steady at 51." With missing data: "HRV not recorded last night. Resting HR 51."

## Design decisions ledger
| ID | Issue | Decision | Answer |
|---|---|---|---|
| DS1 | Today order undefined | Verdict-first order above | D2 = A |
| DS2 | Trends/Sleep Trends layout | Ranked list rows | D3 = A |
| DS3 | States missing | Full state table | D4 = A |
| DS4 | Card mosaic + glow | Layout, not card stack | D5 = A |
| DS5 | No design system | Minimal DESIGN.md + theme, Manrope/Inter, tabular numbers | D6 = A |
| DS6 | Color-only status | Color + arrow + word | D7 = A |
| DS7 | No TalkBack/font-scale spec | Spec'd and tested | D8 = A |
| DS8 | Recovery summary source | Template from real data | D9 = A |

## Design tasks (add to the implementation list)
- [ ] **TD1 (P1, human: ~2h / CC: ~10min)** — design — Write DESIGN.md + `ui/theme/HealthTheme.kt`, bundle Manrope/Inter, add tabular numerals (DS5). Lands with 1a, with no visual change except fonts.
  - Verify: the 1a golden test still passes (values unchanged); fonts visible on device
- [ ] **TD2 (P1, human: ~4h / CC: ~20min)** — ui — Today hierarchy, layout without cards, rings with numbers inside, Recovery summary template (DS1, DS4, DS8, #1, #2). Lands with Phase 3, ordered after 1b.
  - Verify: Robolectric semantics test checks section order and the ring descriptions
- [ ] **TD3 (P1, human: ~4h / CC: ~20min)** — ui — State table for every feature (DS3). Lands with 1b, since empty and partial states appear as soon as fake data is removed.
  - Verify: tests on the empty and partial fixture DBs assert the copy strings
- [ ] **TD4 (P2, human: ~3h / CC: ~15min)** — ui — Trends ranked rows + Sleep Trends layout, color + arrow + word (DS2, DS6). Lands with Phase 2.
  - Verify: on device; semantics test checks the arrow/word for a good, a bad and a normal metric
- [ ] **TD5 (P2, human: ~3h / CC: ~15min)** — a11y — content descriptions, live region, 48dp targets, check at 200% font scale (DS7)
  - Verify: Robolectric semantics tests; manual TalkBack pass on Today

## Design review completion summary
```
+====================================================================+
|         DESIGN PLAN REVIEW — COMPLETION SUMMARY                    |
+====================================================================+
| System Audit         | no DESIGN.md; tokens only in HealthColors   |
| Step 0               | 4/10 initial; focus: all 7 dimensions       |
| Pass 1  (Info Arch)  | 3/10 → 9/10                                 |
| Pass 2  (States)     | 3/10 → 9/10                                 |
| Pass 3  (Journey)    | 7/10 → 9/10 (storyboard added)              |
| Pass 4  (AI Slop)    | 5/10 → 8/10                                 |
| Pass 5  (Design Sys) | 3/10 → 8/10                                 |
| Pass 6  (Responsive) | 2/10 → 9/10                                 |
| Pass 7  (Decisions)  | 1 resolved, 0 deferred                      |
+--------------------------------------------------------------------+
| NOT in scope         | written (mockups, tablet/landscape layouts) |
| What already exists  | HealthColors, ScoreRingGauge, HealthCard,   |
|                      | ContextChip, BottomNavBar, BottomSheetOverlay|
| TODOS.md updates     | 0 items                                     |
| Approved Mockups     | 0 (designer has no OpenAI key)              |
| Decisions made       | 8 added to plan                             |
| Decisions deferred   | 0                                           |
| Overall design score | 4/10 → 9/10                                 |
+====================================================================+
```
NOT in scope (design): visual mockups (the gstack designer has no OpenAI key; add `~/.gstack/openai.json` and re-run to generate them); tablet and landscape layouts (phone-only personal app); outside design voices (not run: codex isn't installed, and the eng outside voice already covered the plan).

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 1 | ISSUES OPEN | 4 proposals, 0 accepted, 4 deferred (its 2 open items are now resolved by D20, D21) |
| Outside Review | native Claude Plan subagent (codex not installed) | Independent 2nd opinion | 2 | unavailable | 18 findings (native fallback: CEO 9 + eng 9); 18 resolved; 0 unresolved; no completed external review |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | ISSUES OPEN | 11 issues, 0 critical gaps (all resolved in plan) |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | CLEAR (PLAN) | score: 4/10 → 9/10, 8 decisions |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

- **OUTSIDE COVERAGE:** codex: not installed for either the CEO or the eng phase, so there's no external review. Both phases ran the native Claude Plan subagent fallback to completion (9 + 9 findings), and all findings are resolved in the plan. Native results don't count as outside coverage.
- **VERDICT:** No review is CLEAR yet. Every eng finding is resolved in the plan with 0 unresolved decisions; the status is issues_open because findings were mapped. Design review CLEAR (8 decisions, 0 deferred). All three requested reviews done; ready to implement starting with Phase 0.

NO UNRESOLVED DECISIONS
