<!-- /autoplan restore point: "D:\\projects\\JCWear Pro alternative\\.gstack\\tmp\\autoplan\\health-plus-fixes-autoplan-restore-20261116-145603.md" -->
## Implementation plan

# Health++: refinements after a few days of use (R1–R6)

Status: APPROVED 2026-11-17 via /autoplan (gate answers: T1a, T2a, T3a, T4a, T5a). Branch: health-plus-fixes (S0–S6 of `2026-11-13-sleep-accuracy-and-todos.md` shipped). All accepted review fixes are folded into the sections below; the review record further down is history only.

### Problem

Six things came up in daily use:

| # | Report | Cause (verified in code) |
|---|---|---|
| 1 | Morning check comes back after reopening the app | `morningCheckCompleted` is `remember` state (`MainDashboardScreen.kt:95`) and the measured value lives only in `J2208BleManager._morningRmssdMs` (`J2208BleManager.kt:74`). Both die with the process. The measurement itself is lost too, so Recovery silently falls back to overnight HRV. |
| 2 | Typed text in Profile and other fields is unreadable | No `MaterialTheme` wraps the app, so `OutlinedTextField` uses the default **light** scheme: near-black text on `BgCard` navy. 5 call sites: `SettingsTab.kt:302`, `Components.kt:670/677/685`, `CoachTab.kt:138`. The sleep edit `AlertDialog`/`TimeInput` (`SleepDetail.kt`) has the same root cause. |
| 3 | No weight tracking | Weight is one prefs value (`AppSettings.weightKg`), used only for the calorie estimate. No history. |
| 4 | Skin temp shows only the delta | `DayMetrics` keeps only `skinTempDeltaC`; the day's average and the 30-day baseline are computed in `buildDaySeries` (`DailySeriesRepository.kt:131-133`) and thrown away. |
| 5 | Readiness has no "how is this calculated" | `readinessScore` returns a bare `Int` (`DailySeriesRepository.kt:153`); Daily, Recovery and Sleep already return a `ScoreBreakdown`. `MetricDetailView` never shows a `ScoreSource`. |
| 6 | App looks like generic AI UI (bubbles) | Rounded cards, glow rings and chips everywhere; colours, radii and font sizes are hardcoded per call site, so any restyle touches every file. |

### Goals

1. A morning check done today stays done, and its value stays in today's scores, across app restarts.
2. Every text input is readable (WCAG AA, 4.5:1) with one fix at the root, not per field.
3. Weight can be logged quickly and seen as a trend: unchanged weight is `+` then Save; otherwise `+`, type, Save.
4. Skin temp shows the actual temperature and the user's average, with the difference second.
5. Readiness explains itself with the same sheet as the other scores, and the sheet can never disagree with the number.
6. The redesign is a token and component swap, not a rewrite. R1–R5 ship first and don't get redone by it.

Not in scope: weight goals, BMI, body-fat, smart-scale import, sterver backup of the two new tables (TODOS.md), a Compose UI test dependency, a load-time budget.

### Rules for all of R1–R5

- User-facing text says "your average" for the personal baseline (never "normal" or "baseline").
- New UI uses existing shared components and `HealthColors` only: no new colour, radius or text-size literals.
- Accessibility: every new control has a 48dp touch target; chart points and sheet rows have TalkBack descriptions with value, unit and date; the skin temp hero and weight card are checked at font scale 1.3 on a 360dp-wide screen (may wrap, must not clip); field errors show supporting text, not colour alone.
- Writes to the new tables use `insertWithOnConflict(..., SQLiteDatabase.CONFLICT_REPLACE)` (minSdk 26, no `ON CONFLICT DO UPDATE`). Neither table is added to `HISTORY_TABLE_COLUMNS` (no `raw_hex`; not uploaded, restored or seed-cleaned).
- Write results: `saveMorningCheck(...)`, `putWeight(...)` and `deleteWeight(...)` return `Boolean`; true means the row was written or removed. Every caught `SQLiteException` on these paths is logged with the exception object, date, DB version and a fixed event name: `morning_check_save_failed`, `weight_save_failed`, `weight_delete_failed`, `new_tables_read_failed`.
- A throwing read of either new table takes the existing `loadFailed` path, logged with `new_tables_read_failed`.
- Tests: JUnit 4 + Robolectric only. Failure tests pass store functions as lambdas or run against a closed database; no mocking library. Logic that needs testing lives in plain classes and pure functions, not composables.
- Verify for every commit: `./gradlew testDebugUnitTest` (run in `android-app/`). To re-record the golden file on purpose: delete `android-app/app/src/test/resources/golden/dashboard_state.txt`, re-run, review the diff (`DashboardStateGoldenTest.kt:25`).
- Screenshots (R2 inventory, colour sweep before/after) are taken by hand and stored under `docs/screens/<step>/`.

### R1: Morning check persists (effort M)

Storage
1. DB v5: `morning_checks(date TEXT PRIMARY KEY, rmssd_ms REAL NOT NULL, measured_at INTEGER NOT NULL, source TEXT NOT NULL)`. `source` is `hrv` or `hr_estimate` (the band path at `J2208BleManager.kt:341-343` falls back to an estimate from heart rate). `CREATE TABLE IF NOT EXISTS` in `onCreate` and under `if (oldVersion < 5)` in `onUpgrade`. The same bump creates `weight_logs` (R5), so there is one migration. If `onUpgrade` throws, the app crashes with the cause logged; no recovery UI.
2. The row's `date` is the phone's local date when the check finishes: `localDateOf(measuredAtMs, zone)`, a pure function. A redo the same day replaces the row.

Flow
3. `SyncCoordinator` gets a constructor lambda `saveMorningCheck: (rmssdMs: Double, measuredAtMs: Long, source: String) -> Boolean` (it has no DB handle, `SyncCoordinator.kt:45-55`). `morningCheck()` returns `sealed MorningCheckResult { MeasureFailed; Saved(rmssdMs); SaveFailed(rmssdMs) }`.
4. A value outside 15..200 ms is `MeasureFailed` and nothing is saved.
5. The save and `onDataChanged` run in `withContext(Dispatchers.IO + NonCancellable)`, so pressing Back cannot drop a finished measurement. `onDataChanged` is called only when the save returned true. `DashboardViewModel` stops combining `bleManager.morningRmssdMs`; the dashboard refreshes from `onDataChanged`.
6. On `SaveFailed` the UI keeps the value in the flow state and shows one "Retry save" button, which retries the write without re-measuring. The pending value does not survive process death.

Series
7. `buildDaySeries` takes `morningChecks: Map<LocalDate, MorningCheck>`. `DailySeriesRepository.load`, `HealthApp.kt:31`, `SyncWorker.kt:44` and `buildDashboardState` drop the `bleManager.morningRmssdMs.value` argument.
8. Three fields on `DayMetrics` (gate decision T1a):
   - `hrvMs`: overnight average only. Used by the HRV trend, every 30-day HRV baseline, `MetricInsights`, `HomelabClient` `overnight_hrv_ms`, CSV `overnight_hrv_ms`.
   - `morningRmssdMs`: the stored check. Used by the done line, the HRV detail header, `HomelabClient` `morning_rmssd_ms`, CSV.
   - `scoreHrvMs = morningRmssdMs ?: hrvMs`. Used by the `recoveryParts` gate, `readinessParts`, the Today HRV tile (labelled "morning check" when it shows the check), coach context (labelled with its source) and the morning summary notification.
9. A day with a check and no overnight HRV has `hasData = true`, computes Recovery and Readiness from the check, and shows no point on the HRV trend.
10. Past days that had a check before this change never stored it, so nothing changes for them.

UI
11. `morningCheckDone` in `MainDashboardScreen` becomes `today.morningRmssdMs != null`. Delete the `morningCheckCompleted` state and its three writes.
12. Done line, where the start card was, one compact line: "Morning check · 07:12 · HRV 62 ms" (plus "estimated" for `hr_estimate`) with a "Redo" text button. Redo runs the normal flow; cancel or failure keeps the earlier value and line.
13. After a successful check the flow's result stays on screen until the reloaded `today.morningRmssdMs` is set, so the start card does not flash.
14. The dashboard reloads on resume when the local date differs from the loaded series' last day (the start card is back the next morning).
15. HRV detail header for a day with a check: "Overnight 55 ms · Morning check 62 ms (used for today's scores)".
16. CSV: new `morning_checks.csv` (with `source`); daily summary gains `morning_rmssd_ms`.

Tests: `MorningCheckDbTest` (write, same-day replace, survives reopen, returned Boolean, range, source); `localDateOf` at 23:59 and 00:10; failing save yields `SaveFailed`, no row, no `onDataChanged`; failed redo leaves the row unchanged; a redo with the identical value still refreshes; a stored check changes that day's Recovery and Readiness and no other day's; baselines unchanged by checks; check with no overnight HRV; restart keeps the same scores; migration tests (fresh install via `onCreate`; v3 to v5; v4 to v5 from a fixture file with all old rows present; v5 opened by the v4 code path and back to v5, tables survive). Golden file re-recorded once.

### R2: Readable text fields (effort S)

1. `ui/Theme.kt`: `HealthTheme { }` = `MaterialTheme(colorScheme = darkColorScheme(...))`. Every role is set explicitly from `HealthColors`: background, surface, surfaceVariant, all surfaceContainer levels, primary/secondary/tertiary and their containers, inverseSurface, outline, outlineVariant, error (`surface = BgCard`, `onSurface = TextPrimary`, `onSurfaceVariant = TextSecondary`, `primary = AccentAmber`, `onPrimary = TextInverse`, `error = ScoreCoral`); `surfaceTint = Color.Transparent`. Wrap `setContent` in `MainActivity`. This file holds the colour scheme only.
2. No per-field `colors =`.
3. Screen inventory: this commit adds to the on-device checklist a list of every screen and state (including empty, dialog, sheet, picker, error, snackbar) with a baseline screenshot each. A global dark scheme changes every Material component at once (dialogs, `Switch`, `TextButton`, pull-to-refresh), so the walk happens in this commit. Pass criteria: typed text readable, no purple, no unreadable label. The same list is reused for the colour sweep and the redesign check.

Tests: contrast ≥ 4.5 for `onSurface`/`surface`, `onSurfaceVariant`/`surface`, `error`/`surface`; `outline` composited on the card background ≥ 3.0 (if it fails, the colour changes); no role equals the Material 3 dark default for primary, secondary, tertiary or their containers.

### U: Unit system (effort S, before R3)

1. `enum class UnitSystem { SI, IMPERIAL }`. `MetricDef.format(v, units)` and `MetricInsights.chip(..., units)` take it with **no default** (they hardcode "°C" today, `MetricInsights.kt:28`, `:120`). Non-UI callers (coach context, notifications, CSV, homelab upload) pass `UnitSystem.SI` explicitly; composables pass the value from `useMetricUnits`.
2. One setting drives both °F and lb; the Profile units row subtitle says "lb and °F". Imperial: absolute temperature × 1.8 + 32, temperature difference × 1.8, weight in lb. Storage, CSV and coach context stay in °C and kg.

Tests: 34.6 °C shows 94.3 °F; difference 0.2 °C shows 0.4 °F; with IMPERIAL no temperature or weight surface string contains "°C" or "kg".

### R3: Skin temp shows the real number (effort S–M)

1. `DayMetrics` gains `skinTempC: Double?` (day average) and `skinTempBaseC: Double?`.
2. One baseline rule, in `buildDaySeries`: `skinTempBaseC` and `skinTempDeltaC` are null unless the prior 30 days hold at least 3 days with a reading; the baseline is the mean of day averages; N counts those days. Readiness uses the same delta, so the sheet never cites a difference the screen hides (this changes early-day readiness: no 25-point penalty from a one-reading baseline). Golden file re-recorded in this commit.
3. Everywhere skin temp appears (gate decision T5a), the temperature leads ("34.6 °C") and the difference is a second line at the existing secondary size ("+0.2 vs your average 34.4"). The detail hero is labelled "Wrist skin temperature" with one 13sp line "Not core body temperature".
4. The chart keeps plotting the difference and is titled "Difference from your average"; in °F the axis is in °F difference. The selected-day row shows the same pair for that day.
5. States: fewer than 3 baseline days: "Building your average · 2 of 3 nights", temperature alone. No reading on the selected day: "No reading".
6. Bottom card: "Your average · 34.4 °C · from N days", following the selected day.
7. CSV daily summary adds `skin_temp_c`, `skin_temp_base_c`. Coach context adds the absolute value.

Tests: average and baseline returned; difference = average − baseline; 2 baseline days give null difference and no readiness penalty; absolute present without baseline.

### R4: "How is this calculated" for Readiness (effort M)

1. `readinessScore(...)` becomes `readinessParts(...): ScoreBreakdown?`. Parts and raw weights: HRV vs your average (0.40), Resting HR vs your average (0.25), Sleep (0.25, when present), SpO₂ and skin temp (0.10, when present).
2. Score maths: `ScoreBreakdown` gains `divisor: Double = 1.0` and `floor: Int = 0`; `score = (parts.sumOf { points } / divisor).roundToInt().coerceIn(floor, cap ?: 100)`. `readinessParts` uses `divisor` = sum of present weights and `floor = 1`, which is the legacy expression exactly (`DailySeriesRepository.kt:168`). Daily, Recovery and Sleep breakdowns are unchanged (defaults).
3. Equality is strict: the current `readinessScore` body moves to the test source as `legacyReadinessScore`; a test compares it with `readinessParts(...).score` over the real dump and a grid of inputs. A mismatch is fixed in `readinessParts`.
4. `DayMetrics.readinessParts`; `readiness = readinessParts?.score`.
5. Sheet, titled with the date ("Readiness · Tue 10 Nov"). Each part is a row: name, plain comparison ("Resting HR 58, your average 55"; "HRV 62 ms (morning check) vs your 58 ms"), sub-score and points ("72/100 · 18 pts"). Parts with no data stay as greyed rows ("Sleep · no data · not counted"). One line under the rows: "Missing parts are left out and the rest are scaled up". Points are derived from the final integer score by largest remainder, so rows always sum to the shown score.
6. Where: under the Readiness row in the expanded Daily card (`TodayTab.kt:219`), and in `MetricDetailView` under the chart for the selected day through a generic `partsFor(metricId, day)`, so the Daily, Recovery and Sleep score detail screens get the sheet too. `partsFor` returns null for metrics with no breakdown and the link is then absent.
7. Null states, in place of the sheet link: today's HRV or resting HR missing: "No HRV or resting HR for this day". Baseline short: "Building your average · N of 3 nights" with N = min(HRV days, resting HR days).
8. CSV daily summary adds `readiness_hrv_pts`, `readiness_rhr_pts`, `readiness_sleep_pts`, `readiness_vitals_pts` (SpO₂ and skin temp together), same rounding, empty when the part is absent.

Tests: equality guard; missing sleep / missing vitals; rows sum to the score; each null message.

### R5: Weight tracking (effort M–L, two commits)

**R5a: store and calories**
1. `weight_logs(date TEXT PRIMARY KEY, weight_kg REAL NOT NULL, logged_at INTEGER NOT NULL)` (created in R1's v5 bump). One value per day; a second log the same day replaces it. Stored in kg as a full double; one decimal (`%.1f`, `Locale.US`) is display and prefill formatting only.
2. Nothing is migrated from prefs and nothing mirrors back. The profile weight in prefs stays as a fallback for calories only and is never plotted.
3. `putWeight`, `deleteWeight` and Undo are suspend calls through `DashboardViewModel` on `Dispatchers.IO`.
4. `DailySeriesRepository.load(today, heightCm, profileWeightKg)`: callers (`buildDashboardState`, `HealthApp.kt:31`, `SyncWorker.kt:44`) pass height and the profile weight; the repository reads logs itself.
5. `DayMetrics.weightKg` = that day's log or null (no carry-forward).
6. Calorie weight, one rule per day: the newest log on or before that day; else the earliest log; else the profile weight; else null, and the calories tile reads "Log weight to estimate calories". Last week's calories don't move when today's weight changes; logging the first weight or back-dating does change earlier days, by design.
7. `parseWeightKg` accepts a comma decimal; the range check runs on the entered unit's own bounds (30.0–300.0 kg, 66.1–661.4 lb) before conversion.
8. Log `buildDaySeries` load time once on the real dump and record the number in the commit message.

Tests: `WeightLogDbTest` (insert, same-day replace, delete, returned Boolean); the 4-step calorie rule; dashboard state has `caloriesEstimated = true` and a value when steps and a weight log exist and the band sent no calories; "78,4" accepted; 66.1 lb accepted; 172.4 lb round trip; 78.4 kg displays "78.4"; `NoFakeValuesTest` still passes with no logs.

**R5b: UI, CSV, coach**
9. `Metrics.weight = MetricDef("weight", "Weight", "kg", ...)` with `Direction.NEUTRAL` (exists, `MetricInsights.kt:12`), `sparse = true` and `showBaseline = false` (two new `MetricDef` flags, defaults `false` / `true`). `showBaseline = false` removes the normal band, outlier dots, the "vs your average" chip and the bottom average card.
10. Chart (gate decision T4a; user: "the dots contain the real logs, the line is smoothed out"): one dot per real log, and one smoothed line whose value on a logged day is the mean of all logs in the trailing 7 calendar days including that day. With a single log in the window that mean is the log itself, so weekly logging still gives a connected line. The line bridges gaps. Y-axis does not start at zero and spans at least ±2 kg (±4 lb) around the data.
11. Change text names its anchor: "−0.6 kg since 3 Oct" = latest line value minus the line value at the newest log 7 or more days earlier; none if there is no such log. Coach context: latest weight and the same kind of change against the newest log 30 or more days earlier, with its date; omitted when missing.
12. Trends card, last position. Zero logs: "Log your weight" with the profile weight if one exists and a `+` button. One log: value and date, no sparkline, "Log again to see a change". Two or more: value, change line, sparkline of the smoothed line. The weight detail header also has `+`. Not on Today.
13. Profile: the weight field becomes a row "Weight · 78.4 kg · 12 Nov" that opens the log sheet. With no log it shows the profile value undated; deleting the only log returns to that.
14. Log sheet, a modal bottom sheet. Logic (validation, Replaces/Update, unit capture, error text, undo payload) lives in a plain `WeightLogSheetState` class; strings come from pure functions. The sheet captures the unit system when it opens and uses that one value for prefill, label and parsing. `MainDashboardScreen` gains no new weight state.
    - Field: decimal keyboard, autofocus with select-all on the prefilled latest value, unit suffix, comma or dot. Save is disabled while invalid; the error text gives the range in the user's unit; IME Done saves.
    - Date: Material date picker, default today, allowing only dates inside the loaded series (its first day, at least 90 days back, to today). `pickerMillisToLocalDate` converts the picker's UTC-midnight millis with UTC.
    - If the picked date already has a log, the field prefills that entry, a line reads "Replaces 78.4 kg" and the button reads "Update".
    - Save failure: "Couldn't save 78.4 kg for 3 Nov · Retry" inline; the sheet stays open.
15. After save: snackbar "Saved 78.4 kg for 3 Nov · Undo". Undo restores the prior row including `logged_at` (or removes the new one). A new save while an Undo snackbar is showing dismisses that snackbar. If Undo fails: "Couldn't undo" and the screen reloads from the database.
16. Edit and delete: selecting a day with a log shows "Edit · Delete" in the selected-day row; delete shows an Undo snackbar; delete failure is a snackbar "Couldn't delete · Retry" and the point stays. A day without a log shows "No entry · Log".
17. CSV: `weight_logs.csv` and `weight_kg` in the daily summary. New daily-summary columns are appended after the existing ones in this order: `morning_rmssd_ms`, `skin_temp_c`, `skin_temp_base_c`, `weight_kg`, `readiness_hrv_pts`, `readiness_rhr_pts`, `readiness_sleep_pts`, `readiness_vitals_pts`. Values are SI; a missing value is an empty field.
18. Coach settings text gains: "Sends weight and skin temperature to Anthropic with your other metrics."

Tests (on `WeightLogSheetState` and pure functions): invalid input keeps Save off and gives the range text; existing date gives the prior value and "Update"; units toggled mid-draft do not reinterpret the number; failing save keeps the text and shows the message; Undo restores the prior row incl. `logged_at`; failing Undo leaves screen and DB equal; `pickerMillisToLocalDate` at UTC−8 and UTC+2; line value with 1 log equals the log, with 3 logs equals their mean; gap bridging; card with no earlier log shows no change; a weight series with a large jump yields no outlier and no chip; CSV header equals the listed order.

### R6: Redesign (effort L)

The look comes from the `design-taste-frontend` skill (taste-skill 2.5.1), now installed. It is written for websites (HTML/CSS, React); for this Compose app its direction rules apply (plain copy, one system, equal edges, quiet micro-interactions, no generic "AI-made" look), while its browser checker and finish gate do not. Device screenshots and `/design-review` stand in for those.

1. **Colour sweep (S–M, gate decision T2a):** replace raw `Color(0x..)` in `ui/*.kt` with `HealthColors` tokens. Zero pixel change, verified by before/after screenshots of the R2 screen inventory. Shape, spacing and type tokens are not introduced yet.
2. **Direction:** run `design-taste-frontend` against screenshots of the live app. It proposes what "sleek, not AI bubbles" means concretely (radii, borders vs fills, glow, density, type) as variants rendered on three real screens: Today, one metric detail, one sheet. The user picks; the result is written to `DESIGN.md`.
3. **Tokens:** shape, spacing and type tokens for the chosen direction (`HealthShapes`, `HealthType`), replacing hardcoded `RoundedCornerShape(n.dp)` and `fontSize = n.sp`.
4. **Apply:** tokens first, then the shared components in `Components.kt` (`HealthCard`, `StatusChip`, `InsightChip`, `ScoreRingGauge`, `AmberActionButton`, bottom nav), then per-screen layout only where tokens aren't enough. One screen per commit.
5. **Check:** `/design-review` on the device build; the R2 contrast and no-purple tests must still pass with the new palette.

### Order and commits

R1 (DB v5 with both tables, morning check) → R2 → U → R3 → R4 → R5a → R5b → R6.1 colour sweep: one PR, one commit each with its tests, on-device checklist after R5b. R6.2–R6.5 is a second PR. Implementation is sequential: R1, R3, R4 and R5 all edit `DailySeriesRepository.kt` and `DayMetrics`.

### Risks

- **DB v5 rollback.** `onDowngrade` is a no-op, so a rolled-back S6 build keeps working; the new tables are ignored and it shows the last pre-R5 profile weight.
- **Theme side effects (R2).** A global dark scheme changes every Material component at once. Mitigation: the screen inventory walk in the R2 commit.
- **Readiness number drift (R4).** Mitigation: raw weights plus divisor, and the strict equality guard.
- **Intended number changes.** `hrvMs` no longer carries the morning check (trend and baselines are overnight-only); skin temp difference is null under 3 baseline days, which changes readiness on early days. Both re-record the golden file.
- **Backup.** `allowBackup` is true and the backup rules exclude only `app_settings.xml`, so Android auto-backup and device transfer carry the database with both new tables but not the profile prefs: a restored install has logs and no profile weight or height. Sterver backup of the two tables is deferred (TODOS.md).
- **Estimated morning checks.** The band can return heart rate only; such a check is stored as `hr_estimate` and labelled "estimated", but still feeds that day's scores.

### On-device checklist

- [ ] Do a morning check, force-stop the app, reopen: done line stays, Recovery unchanged.
- [ ] Press Back the moment the check finishes: the value is kept.
- [ ] Redo a check: value is replaced. Cancel a redo: earlier value stays.
- [ ] Leave the app open overnight: the start card is back in the morning.
- [ ] R2 screen inventory walk. For each line: typed text readable, no purple, no unreadable label; one screenshot in `docs/screens/r2/`.
  - [ ] Today: normal day
  - [ ] Today: Daily card expanded, Recovery card expanded
  - [ ] Today: low blood oxygen alert
  - [ ] Today: fewer than 3 days of data (empty state)
  - [ ] Today: historical day ("Back to today")
  - [ ] Today: "Couldn't load data · Retry" line
  - [ ] Today: sync pill while syncing (spinner) and pull-to-refresh indicator
  - [ ] Morning check: intro, measuring, result, "Check failed", "Couldn't save the check"
  - [ ] Morning check done line with Redo
  - [ ] Verdict ("why") sheet
  - [ ] "How is this calculated" sheet (Daily, Recovery, Sleep)
  - [ ] Trends list; Trends with fewer than 3 days
  - [ ] Calendar sheet
  - [ ] Metric detail: chart with a selected day, "No data in this range", 30-day average card
  - [ ] Sleep detail: night with stages, Looks right / Edit buttons
  - [ ] Sleep edit dialog: both time inputs with typed digits, AM/PM toggle if shown, error line, confirm step, Cancel / Save
  - [ ] Sleep trends
  - [ ] Coach: empty, with messages, input with typed text, quick-question buttons
  - [ ] Settings: Profile name, height and weight fields, each empty, focused with typed text, and with "Out of range"
  - [ ] Settings: server URL and token fields (server config sheet), Anthropic key field
  - [ ] Settings: sync sheet with Recent syncs, export status line, notification toggles, bedtime row
  - [ ] System toasts and the notification permission dialog (system-drawn, expected unchanged)
- [ ] Skin temp detail shows the temperature first and the average; with 2 nights it shows "Building"; a new install's first reading shows no difference.
- [ ] Readiness sheet rows add up to the score on Today and on a past day in the trend.
- [ ] Log weight today, log again (Update), back-date onto an existing log ("Replaces"), pick yesterday and confirm the saved date, delete then Undo.
- [ ] Weekly-spaced logs give a connected line; two logs in a week show dots and a smoothed line.
- [ ] Switch units: °F and lb everywhere, one decimal for weight; calories for last week don't move when today's weight changes.
- [ ] TalkBack reads a chart point; font scale 1.3 does not clip the skin temp hero or weight card.
- [ ] Export CSV: new files and columns present, in the listed order.

## Review record

### Phase 1: CEO review (SELECTIVE EXPANSION, via /autoplan)

Outside reviewer: Codex not installed, outside coverage unavailable for every phase. Native reviewers only.

#### System audit

- Branch `health-plus-fixes`, clean tree, no stashes, 65 files changed vs `main`. No TODO/FIXME markers in `android-app/app/src` or `homelab-server`.
- Hot files this plan touches again: `MainDashboardScreen.kt` (12 edits in 30 days), `SettingsTab.kt`, `AppSettings.kt`, `LocalHealthDatabase.kt`, `DailySeriesRepository.kt`. `MainDashboardScreen.kt` is the recurring problem area: it holds navigation, settings state and the check flow in one composable, which is how the morning-check flag ended up as `remember` state.
- Prior learning applied: sqlite-upsert-minsdk26 (confidence 8/10, 2026-11-12). "Replace on conflict" in R1/R5 must be `insertWithOnConflict(..., CONFLICT_REPLACE)` as `putLabel` does (`LocalHealthDatabase.kt:140-141`), never `ON CONFLICT DO UPDATE`.
- Prior learning applied: gstack-designer-needs-openai-key (9/10). Mockup generation is unavailable on this machine, one more reason R6's look waits for the user's design skill.
- TODOS.md: "Back up sleep labels to sterver" (P2) is the same shape as backing up `weight_logs` and `morning_checks`. "Health Connect export" (P3) is where weight import/export would live.
- Taste calibration. Good patterns to copy: `ScoreBreakdown` + `ScoreSource` (score and sheet share one source, `ScoreBreakdown.kt:12-15`); `putLabel` (one DAO method owns the write rule, `LocalHealthDatabase.kt:129-141`); `MetricDef` registry (`MetricInsights.kt:40-50`). Pattern to avoid: UI state that is really data (`MainDashboardScreen.kt:95`), and per-call-site colours and sizes.
- Landscape (WebSearch; Aside not installed). [Layer 1] Weight apps lead with a smoothed trend, not the daily number: Happy Scale offers its own smoothing, exponential smoothing and a 7-day moving average ([support](https://happyscale.com/support)); Withings weights earlier weigh-ins ([blog](https://blog.withings.com/2010/07/06/its-now-even-easier-to-follow-your-weight-trend)). [Layer 2] Newer trackers do the same with a 7-day average or EMA. [Layer 3] The plan's "smoothing off" for weight contradicts this: daily weight moves with water alone, so the raw line is the noisy one. Sources are vendor pages, not independent studies.

#### 0A. Premise challenge

Real problem: after a few days of real use the app loses a measurement, hides what the user types, and can't answer "what is my number". Do-nothing cost: the user redoes a 60-second check every morning it is forgotten, can't see their own profile input, and keeps a second app for weight. R1–R5 solve the pain directly. R6 is a proxy until the design skill says what "sleek" means.

| # | Premise | Receipt | Verdict |
|---|---|---|---|
| P1 | Morning-check done flag and value die with the process | `MainDashboardScreen.kt:95`, `J2208BleManager.kt:74` | verified |
| P2 | No Material theme wraps the app, so fields use the light default | `MainActivity.kt:140` | verified |
| P3 | Day temperature and baseline are computed then discarded | `DailySeriesRepository.kt:131-133` | verified |
| P4 | Readiness is a bare Int while other scores have breakdowns | `DailySeriesRepository.kt:153`, `ScoreBreakdown.kt:17-32` | verified |
| P5 | Weight is one prefs value | `AppSettings.kt:84-86` | verified |
| P6 | The redesign can be done as a token and component swap | none | unverified: "bubbles" may be layout and density, not radii |
| P7 | `Direction.NEUTRAL` must be added | `MetricInsights.kt:12` | wrong: it already exists |
| P8 | The UI path gets the user's weight for calories | `DashboardModels.kt:123` | wrong: `buildDashboardState` calls `load(today, morningRmssd)` without height or weight, so the dashboard never shows the estimates that `HealthApp.kt:31` computes for the worker |

#### 0B. Existing code leverage

| Sub-problem | Existing code | Reuse |
|---|---|---|
| Persist a per-day user/measured value | `sleep_labels` table + `putLabel` | same shape for `morning_checks`, `weight_logs` |
| Score explanation | `ScoreBreakdown`, `ScoreSource`, `ScoreInputsSheet` | R4 only adds `readinessParts` |
| Metric card, detail, chips | `MetricDef` registry, `MetricDetailView`, `Direction.NEUTRAL` | R3, R5 add defs, no new screens |
| Weight parsing and range | `parseWeightKg` (`Profile.kt:8`) | reused by the log sheet |
| Export | `CsvExporter` | add files and columns |
| Inline field validation | `ProfileField` (`SettingsTab.kt:299`) | reused in the log sheet |

No rebuilds. Every item extends an existing path.

#### Reality rows

REALITY: premise-table finding premises P1 to P8 checked, two wrong and corrected, unverified=1 android-app/app/src/main/java/com/jcwear/coach/ui/MainDashboardScreen.kt:95
REALITY: already-done pass no commit on origin/main or this branch mentions weight_logs, morning_checks, readinessParts or HealthTheme 84a6e09
REALITY: surface-check finding plan names no CLI command or HTTP route; one named symbol was wrong, Direction.NEUTRAL already exists android-app/app/src/main/java/com/jcwear/coach/domain/MetricInsights.kt:12
REALITY: binding-decisions pass gstack-decision-search returned no branch decisions; routing rules only CLAUDE.md:1
REALITY: numbers pass call-site count, readiness weights and weight range re-read from source android-app/app/src/main/java/com/jcwear/coach/domain/DailySeriesRepository.kt:159-166
REALITY: deferred-asks pass no linked issue; every ask in the user's message is in the plan docs/plans/2026-11-16-refinements.md:9
REALITY: pr-count pass no ship-policy file, default one PR; plan is one branch with one commit per R docs/plans/2026-11-16-refinements.md:5
REALITY: transaction-semantics pass no driver, pool or protocol change; new tables go through the existing SQLiteOpenHelper android-app/app/src/main/java/com/jcwear/coach/data/LocalHealthDatabase.kt:27

#### 0C. Dream state

```
CURRENT                          THIS PLAN                           12-MONTH IDEAL
Measurements and flags can       Every user-entered or measured      One trusted daily picture: band +
vanish; inputs unreadable;       value is stored per day and shown   manual logs, all backed up, every
weight lives elsewhere; scores   with its real number and its        number explains itself, and a
half-explained; generic look.    explanation; theme has one root.    look that is the app's own.
```

The plan moves toward the ideal. The gap it leaves: user-entered data (labels, weight, morning checks) exists only on the phone.

#### 0D/0E. Approach and mode

No approach choice was needed: each item has one obvious path that reuses existing code. Mode SELECTIVE EXPANSION by /autoplan override (about 14 changed files [estimated], added capability).

#### 0F/0G. Scope candidates

HOLD checks: about 14 files [estimated] and no new classes beyond `Theme.kt` and two DAO method groups, under the complexity bar. Minimum set is R1–R5; R6.2–R6.5 are deferrable without blocking them and stay sequenced last. No invariant is weakened.

10x version: the app becomes the one place for body data: weight with a real trend, morning check history, and all of it backed up to sterver. Delight scan: weight trend line; redo a botched morning check; one-decimal weight; "since last week" delta; weight in the coach's context.

| ID | Proposal | Effort (human / CC) | Risk | Decision | Principle and reason |
|---|---|---|---|---|---|
| C1 | 7-day average trend line on the weight chart, with the daily points behind it | S (~3h / ~15min) | low | ACCEPTED | P1. Raw daily weight is noise; every reference app leads with the trend. Replaces "smoothing off" in R5.6. |
| C2 | "Morning check done · 62 ms · Redo" line on Today once a check exists | S (~2h / ~10min) | low | ACCEPTED | P1. R1 says a same-day redo wins, but hiding the card removes the only way to start one. |
| C3 | Dashboard path passes height and weight (P8) | S (~1h / ~10min) | low | ACCEPTED | P2. Bug in a file the plan already edits; R5.7 is invisible without it. |
| C4 | Weight shown and entered with one decimal | S (~1h / ~5min) | low | ACCEPTED | P1. `MainDashboardScreen.kt:369` formats `%.0f`; a tracker that rounds 78.4 to 78 is useless. |
| C5 | Back up `weight_logs` and `morning_checks` to sterver | M (~1d / ~45min) | medium | DEFERRED to TODOS.md | P3. Server table + restore route is outside the blast radius; fold into the existing sleep-labels backup TODO. TASTE: this is data that cannot be re-fetched. |
| C6 | Weight goal and projected date | M (~1d / ~40min) | low | DEFERRED to TODOS.md | P3. User listed tracking and logging only. |
| C7 | Health Connect weight import | M | medium | SKIPPED | P4. Covered by the existing Health Connect TODO. |
| C8 | Weigh-in reminder notification | S | low | SKIPPED | Not asked for; a third notification type needs its own case. |

Corrections (factual, no behaviour choice): P7, `Direction.NEUTRAL` exists, R5.5 drops "this needs". Prior learning: writes use `CONFLICT_REPLACE`.

Taste decisions queued for the final gate:
- T1 (R1.3): a stored morning check replaces that day's overnight HRV in history, so the HRV trend and the 30-day HRV baseline mix 60-second morning readings with overnight averages. Kept as planned because it is what the user sees today and Goal 1 says the number must not change after a restart. Alternative: keep overnight HRV in the trend and use the morning value only for Recovery and Readiness.
- T2 (R6.2): run the token sweep before the design direction is known. Kept, limited to colour, shape and type tokens, which any direction needs. Alternative: wait for the design skill so token names match its vocabulary.
- T3 (C5): defer the backup vs include it now.

#### 0I. Temporal interrogation

- Hour 1: DB v5 with both tables in one migration; the prefs to `weight_logs` copy runs at app start (the DB helper has no `AppSettings`), guarded by a prefs flag like `bundledHistoryImported`.
- Hours 2–3: the `buildDaySeries` signature change ripples to `HealthApp.kt:31`, `SyncWorker.kt:44`, `DashboardModels.kt:123` and every test that calls it; the golden file changes once each for R1, R3, R4, R5.
- Hours 4–5: `MetricDetailView` assumes dense data and delta-only skin temp; weight needs gap-connecting lines and a different hero. Keep it one function with `def`-driven switches, no fork.
- Hour 6+: imperial formatting in three places (weight, temp, existing height); the dark scheme's effect on the time picker.

Effort: human ~4 days / CC ~3 hours for R1–R5 plus C1–C4 [estimated]. R6.2 human ~1 day / CC ~45 min [estimated].

#### Spec review loop

Two reviewer launches. Round 1: 6/10, 24 issues, all fixed. Round 2: 8/10, Scope and Feasibility PASS, 9 open items (2 unfixed consistency items, 7 new). All 9 are applied as accepted bullets; `issues verified: 9 of 9` (applied edits, not a verdict). No third launch: spec not re-verified, the Eng phase reviews the same text.

#### CEO voice (native subagent, input 516304c8…) and consensus

| # | Finding | Severity | Disposition |
|---|---|---|---|
| V1 | Morning check blended into HRV history and baseline becomes permanent | Critical | Accepted in a third form: overnight `hrvMs` for trend and baselines, check stored separately and used for that day's scores. T1 stays a gate decision. Single-voice critical, flagged. |
| V2 | Weight and morning checks have no backup or restore | High | C5 stays provisionally deferred; gate recommendation flips to "include now" (T3). |
| V3 | R5 over-specified; cut trend maths, prefs mirror; consider Health Connect | High | Prefs mirror cut. Trend line kept, gate decision T4. Health Connect rejected: new dependency and permissions, and a local table is needed for calories either way. |
| V4 | R6 is last and blocked on a skill that may not be needed; sweep precedes direction | High | User's direction stands (they said they will add a design skill). Sweep narrowed to colour tokens until then (T2). |
| V5 | Plan body overridden by chained "Replaces" bullets | Medium | Accepted: fold into the body before implementation (task T1). |
| V6 | Hero-size absolute skin temperature reads as body temperature | Medium | Accepted: label, equal weight for delta, one caveat line. |
| V7 | °F and lb assumed | Medium | Kept. `useMetricUnits` already exists with lb and inches; weight needs the unit argument anyway, so °F costs one branch. |
| V8 | Readiness weights never validated | Medium | Accepted the small part: per-part CSV columns. |
| V9 | R2 verified too late; outline contrast untested | Medium | Accepted. |
| V10 | Opportunity cost over competition | Medium | Noted; no change beyond V3. |

```
CEO DUAL VOICES — CONSENSUS TABLE:
  Dimension                            Claude subagent        Codex   Consensus
  1. Premises valid?                    T1 premise wrong       —       N/A
  2. Right problem to solve?            yes for R1–R4          —       N/A
  3. Scope calibration correct?         R5 too large           —       N/A
  4. Alternatives sufficiently explored? Health Connect not     —       N/A
  5. Competitive/market risks covered?  low, single user       —       N/A
  6. 6-month trajectory sound?          only if T1 is separated —      N/A
Outside voice unavailable (Codex not installed): no cell is CONFIRMED.
```

#### Sections 1–11

**1. Architecture.** Current scope: R1–R6 plus C1–C4 accepted, C5/C6 deferred, C7/C8 skipped, T1–T4 pending at the gate.

```
J2208 band ──BLE──> J2208BleManager ──> SyncCoordinator ──saveMorningCheck()──┐
                                                                             v
Log sheet (Trends +, Profile row) ──putWeight/deleteWeight──> LocalHealthDatabase v5
                                                    [morning_checks] [weight_logs] + existing tables
                                                                             │
                              DailySeriesRepository.load(today, height) <────┘
                                        │  buildDaySeries: hrvMs (overnight), morningRmssdMs,
                                        │  scoreHrvMs, skinTempC/BaseC, weightKg, readinessParts
                                        v
                       DashboardViewModel ──> Today / Trends / MetricDetailView / ScoreSource
                                        └──> CsvExporter, ClaudeCoachRepository, SyncWorker
HealthTheme (new, MainActivity) wraps everything.
```

Four paths for the two new flows. Happy: value saved, `onDataChanged`, state rebuilt from the DB. Nil: no check or no log for a day gives null fields, tiles show "—", calories fall back to null. Empty: empty tables behave like nil; the migration inserts only when `weight_logs` is empty. Error: `SQLiteException` on write is the one gap, see section 2. No new state machine: `CheckFlowStep` is unchanged and "done" is now derived from data. Coupling: `SyncCoordinator` gains one lambda, no DB dependency; `MetricDef` gains two flags and `format` gains a unit argument. Scale: a year of daily logs is 365 rows; the series is capped by the 365-day back-date bound. Single point of failure: the phone's SQLite file, see T3. Rollback: reinstall the S6 build; v4 code ignores the new tables, minutes. No artifacts about the work are added.

**2. Error and rescue.**

| Codepath | What can go wrong | Class | Rescued? | Action | User sees |
|---|---|---|---|---|---|
| `saveMorningCheck` | DB write fails | `SQLiteException` | N, GAP | catch in the lambda, log, return failure so the flow shows "Couldn't save the check, try again" | failure card, not a silent loss |
| `putWeight` / `deleteWeight` | DB write fails | `SQLiteException` | N, GAP | same pattern as `saveLabel` (`DashboardViewModel.kt:63-71`) plus a visible "Couldn't save" line in the sheet | inline error |
| log sheet parse | blank, text, out of range, future or too-old date | none (validation) | Y | `parseWeightKg` returns null, inline message | "Out of range" / date message |
| prefs to log migration | DB not ready or write fails | `SQLiteException` | N, GAP | catch, leave the flag unset so it retries next start | nothing lost, retried |
| `readinessParts` | missing inputs | none | Y | returns null | "Needs 3 earlier days…" |
| morning check BLE | band disconnects | existing | Y | existing "Check failed" card | unchanged |

Three gaps, all resolved by accepted bullets below. `saveLabel` today swallows the exception with only a log line; the same silent pattern must not be copied for weight.

**3. Security.** No new endpoints, secrets, permissions or dependencies. Weight is health data kept in the app-private DB, already excluded from cloud backup; the CSV export already writes health data through the share sheet, and weight joins it by design. Coach context now sends weight to the Anthropic API when a key is set: same class of data as HRV and sleep already sent, low likelihood and low impact, noted for the user. SQL goes through `ContentValues`, no string building. No issues found beyond that note.

**4. Data flow and interaction edge cases.**

```
weight text ─> parseWeightKg ─> kg double ─> putWeight(date) ─> refresh ─> card/chart/calories
   nil/blank      out of range     n/a         SQLiteException    stale state until next refresh
   -> Save off    -> inline msg                -> inline error    -> none: refresh follows the write
```

| Interaction | Edge case | Handled | How |
|---|---|---|---|
| Save weight | double tap | Y | same date key, second write replaces with the same value |
| Save weight | back-date, future, older than 365 days | Y | accepted bullets |
| Delete entry | only log | Y | Profile "Not set", calories null |
| Redo check | fails | Y | earlier row kept |
| Redo check | leave app mid-measurement | Y | nothing written until success; flow resets as today |
| Check after midnight | date key | Y | local date at finish |
| Unit toggle with sheet open | prefill unit | GAP | prefill and parse must read the same `useMetricUnits` captured when the sheet opens |
| Phone time zone change | two dates for one day | accepted risk | one personal device; row keeps its local date |

Async ordering: the only shared mutable state is the DB row plus the refresh tick. Invariant: state is rebuilt only after the row exists. Mechanism: the lambda writes synchronously on the coordinator's coroutine before `onDataChanged`; `mapLatest` drops any older in-flight load. The background `SyncWorker` can run a load concurrently, which reads a consistent SQLite snapshot either before or after the write; both orders give a correct result for the data present.

**5. Code quality.** New code follows existing patterns (DAO method per write rule, `MetricDef` flags). Risk: `MetricDetailView` grows three per-metric modes (delta+absolute temp, sparse weight, score sheets). Keep the switches on `MetricDef` fields; if it passes five branches on `def`, split the hero into its own composable. `buildDaySeries` already takes 11 parameters; adding `morningChecks` and `weightLogs` makes 12 after `morningRmssd` and `weightKg` go. Acceptable, no wrapper type needed yet. One finding: `MainDashboardScreen` should not gain more state; the log sheet's draft state lives in the sheet composable.

**6. Tests.**

```
NEW THING                         TYPE   HAPPY                      FAILURE / EDGE
morning_checks DAO                unit   write+read                 same-day replace; 00:10 key; write fails
check feeds scores not baseline   unit   day score changes          other days unchanged; restart same
Redo                              unit   replaces                   failed redo keeps row; identical value refreshes
HealthTheme contrast              unit   3 text pairs ≥ 4.5         outline ≥ 3.0
skin temp absolute + base         unit   avg, base, delta           no baseline; °F conversion
readinessParts                    unit   equals legacy over dump    missing sleep/vitals; null note
weight_logs DAO + migration       unit   insert/delete              replace; migrate once; write fails
weight trend                      unit   mean of 7-day window       1 log; gaps; card with no earlier trend
weight showBaseline=false         unit   no chip/dot                large jump
calories by log date              unit   past day uses its log      no logs → null
CSV                               unit   new files/columns          empty tables
golden dashboard state            golden regenerated once          —
on-device                         manual checklist                  theme walk in R2
```

2am-Friday test: the legacy equality guard over the real dump. Hostile QA: back-date a log to 366 days, toggle units mid-entry, redo a check with the band off. Chaos: kill the process between the check finishing and the row write; the lambda runs before the result is shown, so the value is either saved or the flow reports failure. Pyramid is unit-heavy, correct for this app. Flakiness: tests that use "today" take an injected date, as existing ones do. `no_claim` for the contrast test: it proves token pairs, not rendered pixels (covered by the manual walk). No LLM prompt evals: the coach prompt gains two facts, no behaviour change. Gap: none beyond the three rescue tests added above.

**7. Performance.** `buildDaySeries` runs on every refresh over all rows; two more small table reads add nothing measurable. Trend computation is O(logs × 7). The 365-day bound removes the only unbounded input. No indexes needed: both tables are keyed by date and read whole. No issues found.

**8. Observability.** Single-user app with no telemetry by design. Debuggability comes from `Log.e` on each new catch (table, date, value) and from `pull-phone-db.bat`, which already copies the DB that now holds both tables. One gap: the "Recent syncs" list (D13) could record "morning check saved"; skipped as not needed to diagnose anything the DB does not already show.

**9. Deployment.** Sideloaded APK via `install-phone.bat`. Migration v4→v5 is additive (`CREATE TABLE IF NOT EXISTS`), no table rewrite. Rollback: reinstall S6; `onDowngrade` no-op keeps it opening. Post-install check: the on-device checklist, with the theme walk right after R2. One risk flagged: rolling back below R5 shows the pre-R5 prefs weight.

**10. Trajectory.** Reversibility 4/5: tables and fields are additive; the only semi-permanent choice is T1, and the accepted form keeps both values so it can be changed later without data loss. Debt: `MetricDetailView` breadth; user data unbacked until C5. Phase 2 after this: backup of the three user tables, then the redesign apply. The token file is the platform piece.

**11. Design (UI scope).** Information order is sound: number, context, explanation. Interaction states:

| Feature | Loading | Empty | Error | Success | Partial |
|---|---|---|---|---|---|
| Weight card | n/a (local) | "Log your weight" + `+` | inline save error | value + change | value, no change line |
| Weight detail | n/a | "No logs yet" | — | points + trend | points only |
| Morning check line | measuring flow | start card | "Check failed" | "done · N ms · Redo" | — |
| Skin temp detail | n/a | "—" | — | absolute + delta | absolute only |
| Readiness sheet | n/a | "Needs 3 earlier days…" | — | parts sum to score | missing parts noted |

Gap: the plan does not say where the weight empty state lives or what the `+` target size is; left to the Design phase. AI-slop risk is exactly item 6 and is deferred by the user's choice. A full design review of this plan runs next.

```
Today ──(check done)──> "done · Redo" ──> Morning check flow ──> Today
Trends ──Weight card──> Weight detail ──long-press point──> Delete
   └── + ──> Log sheet ──Save──> Trends          Profile ──Weight row──> Log sheet
Trends ──Readiness──> Detail ──"How is this calculated"──> Sheet
```

#### NOT in scope

Deferred (in TODOS.md): C5 backup of weight and morning checks with sleep labels (provisional, T3); C6 weight goal. Rejected: C7 Health Connect weight (covered by the existing TODO); C8 weigh-in reminder; BMI, body fat, smart-scale import (user did not ask); any new visual design before the design skill; "Recent syncs" entry for morning checks.

#### What already exists

See 0B. All reused: `sleep_labels`/`putLabel` pattern, `ScoreBreakdown`/`ScoreSource`, `MetricDef` registry and `Direction.NEUTRAL`, `parseWeightKg`, `ProfileField`, `CsvExporter`, `onDowngrade` no-op.

#### Dream state delta

After this plan every value the user enters or measures is stored and explained. Still missing against the 12-month ideal: off-phone backup of user-entered data, and the app's own visual identity.

#### Failure modes registry

| Codepath | Failure | Rescued | Test | User sees | Logged |
|---|---|---|---|---|---|
| saveMorningCheck | DB write fails | Y (accepted) | Y | failure card | Y |
| putWeight/deleteWeight | DB write fails | Y (accepted) | Y | inline error | Y |
| weight migration | write fails | Y (retry next start) | Y | nothing | Y |
| log sheet | bad input/date | Y | Y | inline message | n/a |
| theme | a component unreadable in dark | manual walk | contrast unit test | caught before commit | n/a |
| readinessParts | differs from legacy | strict test | Y | n/a | n/a |

0 critical gaps after the accepted bullets.

Diagrams produced: system architecture, data flow, user flow. No state machine, deployment or rollback diagram: nothing new in those. Stale diagram audit: the touched files contain no ASCII diagrams.

#### Completion summary

```
+====================================================================+
|            MEGA PLAN REVIEW — COMPLETION SUMMARY                   |
+====================================================================+
| Mode selected        | SELECTIVE EXPANSION (autoplan override)     |
| System Audit         | 2 wrong premises (P7, P8), 1 unverified (P6)|
| Step 0               | 8 proposals: 4 accepted, 2 deferred, 2 skip |
| Section 1  (Arch)    | 0 issues found                              |
| Section 2  (Errors)  | 6 error paths mapped, 3 GAPS (all resolved) |
| Section 3  (Security)| 1 note, 0 High severity                     |
| Section 4  (Data/UX) | 8 edge cases mapped, 1 unhandled (resolved) |
| Section 5  (Quality) | 2 issues found                              |
| Section 6  (Tests)   | Diagram produced, 0 gaps after additions    |
| Section 7  (Perf)    | 0 issues found                              |
| Section 8  (Observ)  | 1 gap, skipped with reason                  |
| Section 9  (Deploy)  | 1 risk flagged                              |
| Section 10 (Future)  | Reversibility: 4/5, debt items: 2           |
| Section 11 (Design)  | 1 issue, passed to Design phase             |
+--------------------------------------------------------------------+
| NOT in scope         | written (7 items)                           |
| What already exists  | written                                     |
| Dream state delta    | written                                     |
| Error/rescue registry| 6 rows, 0 CRITICAL GAPS                     |
| Failure modes        | 6 total, 0 CRITICAL GAPS                    |
| TODOS.md updates     | 2 items proposed                            |
| Scope proposals      | 8 proposed, 4 accepted                      |
| CEO plan             | written                                     |
| Outside voice        | codex unavailable (not installed); native completed |
| Lake Score           | N/A                                         |
| Diagrams produced    | 3 (architecture, data flow, user flow)      |
| Stale diagrams found | 0                                           |
| Unresolved decisions | 4 taste (T1–T4) held for the final gate     |
| Reality rows         | 8/8 rows (PLAN_REALITY), 1 unverified       |
+====================================================================+
```

<!-- autoplan-accepted:ceo -->
- R1.2, R5.1 writes use `insertWithOnConflict(..., SQLiteDatabase.CONFLICT_REPLACE)`; no `ON CONFLICT DO UPDATE` (minSdk 26). Test: same-day second write replaces the first, in `MorningCheckDbTest` and `WeightLogDbTest`.
- Replaces R5.5 "This needs a `Direction.NEUTRAL`": `Direction.NEUTRAL` already exists (`MetricInsights.kt:12`); `Metrics.weight` uses it. Test: weight never gets a GOOD/BAD status or an outlier dot.
- C1, replaces R5.6 "the 3-day smoothing is off": the weight chart draws daily points plus a 7-day average line computed over logged days only; with fewer than 2 logs in the window it shows points only. The Trends card's change line compares 7-day averages. Tests: average over sparse logs; one log gives no line.
- C2: when `today.morningRmssdMs != null`, Today shows one line "Morning check done · N ms · Redo" in place of the start card; Redo opens the same flow and a successful result replaces the stored row; a failed redo keeps the earlier value. Tests: failed redo leaves the row unchanged; on-device checklist item.
- C3: `buildDashboardState` passes height and the weight source to `DailySeriesRepository.load`, same as `HealthApp.kt:31`. After R5 the repository reads weight from `weight_logs` itself, so no caller passes weight. Test: dashboard state has `caloriesEstimated = true` and a value when steps and a weight log exist and the band sent no calories.
- C4: weight is stored as entered, shown and prefilled with one decimal in kg and in lb (`%.1f`); lb to kg to lb returns the entered value to one decimal. Tests: 172.4 lb round trip; 78.4 kg displays "78.4".
- R5.2 migration runs once at app start, guarded by a prefs flag; it inserts the old prefs weight as today's log only if `weight_logs` is empty. Test: second start inserts nothing.
- Spec review 1 fixes follow. Where one says "Replaces", it wins over the body and over the bullets above.
- R1.1: the row's `date` is the phone's local date at the moment the check finishes. Test: a check at 00:10 is keyed to the new day.
- Replaces R1.2 "`SyncCoordinator.morningCheck()` writes the row" and R1.3 "The `StateFlow` stays only as a refresh trigger": `SyncCoordinator` gets a `saveMorningCheck: (LocalDate, Double) -> Unit` constructor lambda (it has no DB handle, `SyncCoordinator.kt:45-55`), calls it on success, then calls `onDataChanged`. The dashboard refreshes from that call; `DashboardViewModel` stops combining `bleManager.morningRmssdMs`. Test: a redo with the identical value still refreshes; state is read only after the row exists.
- R1 risk, stated plainly: because `hrvMs = morning check ?: overnight` (`DailySeriesRepository.kt:108`), stored checks enter the 30-day HRV baseline of later days and so their Readiness. This is taste decision T1; the plan keeps it until the user decides.
- R5.3: the day series starts at the earliest of first band data, first weight log and today minus 89 days, so a back-dated log is always inside the series. Deleting the only log leaves calorie estimates null and the Profile row reading "Weight · Not set · Log". Tests for both.
- Replaces C4 "stored as entered": weight is stored in kg as a full double; one decimal is display and prefill formatting only.
- Replaces R5.4 "change vs 7 days ago", R5.6 "the line connects logged days across gaps" and the C1 bullet's window wording: the trend value of a logged day is the mean of all logs in the trailing 7 calendar days including that day, defined only when that window holds at least 2 logs. The detail chart draws every log as a point and one line through the trend values, bridging gaps; there is no raw connecting line. The Trends card shows the latest log and "trend minus the trend at the newest logged day 7 or more days earlier"; if either trend value is missing it shows no change line. `MetricDef` gets `sparse: Boolean = false`; `MetricDetailView` switches chart mode on it. Tests: window with 1 log gives no trend value; gap bridging; card with no earlier trend.
- Replaces the `Direction.NEUTRAL` bullet's claim that NEUTRAL alone is enough: NEUTRAL still returns ABOVE/BELOW (`MetricInsights.kt:90`) and `MetricDetail.kt:72` draws a dot for any non-NORMAL status. `MetricDef` gets `showBaseline: Boolean = true`; weight sets it false, which removes the normal band, outlier dots and the "vs your normal" / "Building your baseline" chip. Test: a weight series with a large jump yields no outlier and no baseline chip.
- Replaces R4.2 "If rounding ever differs, the breakdown wins": equality is strict. The current `readinessScore` body moves to the test source as `legacyReadinessScore` and the guard compares against it; a mismatch is fixed in `readinessParts`.
- R4.4: when readiness is null, the Readiness row and the Readiness detail show the line "Needs 3 earlier days of HRV and resting HR" in place of the sheet link.
- R4.5 generic `partsFor` (sheet also on the Daily, Recovery and Sleep score detail screens) is accepted explicitly: same component, same file, no extra data.
- R3.3: N counts days with at least one valid reading in the prior 30 days; the card always describes today's baseline.
- Replaces R3.7 "it's one formatter": `MetricDef.format` and `MetricInsights.chip` hardcode "°C" (`MetricInsights.kt:28`, `:120`). Both take a `metric: Boolean` argument; imperial shows °F (absolute × 1.8 + 32, delta × 1.8) and lb. CSV, coach context and storage stay in °C and kg. R3 effort becomes S–M, R5 M–L. Tests: 34.6 °C shows 94.3 °F; delta 0.2 °C shows 0.4 °F.
- C3 lands in the R5 commit: `buildDashboardState`, `HealthApp.kt:31` and `SyncWorker.kt:44` pass height only; the repository reads weight from `weight_logs`.
- Rollback: every weight write also mirrors the newest log to prefs `weightKg`, so a rolled-back S6 build still has the current weight. Test: prefs equals newest log after insert and after delete.
- Replaces R6.1 "Prep now (part of R2)": R2's `Theme.kt` holds the colour scheme only. Shape, spacing and type tokens land in their own commit as the first step of R6.2.
- On-device checklist adds: weight chart shows points and a trend line after two logs in a week; weight shows one decimal in kg and lb; Redo after a morning check replaces the value; imperial units show °F and lb.
- Spec review 2 fixes follow.
- R5.3 lower bound: the log sheet refuses a date more than 365 days before today with the inline message "Date is more than a year ago". Test: a 366-day-old date is refused and nothing is saved.
- R1 golden file: regenerated once under the kept T1 behaviour; if the user flips T1 at the gate, it is regenerated again in the commit that changes the rule.
- Replaces R5.2 "on upgrade" and "`AppSettings.weightKg` then goes away as a stored value": the copy runs at app start as stated above, and prefs `weightKg` stays as a write-only mirror of the newest log. No code reads it after R5. Deleting the only log clears it to null.
- R5.8 coach "30-day change": trend at the newest logged day minus the trend at the newest logged day 30 or more days earlier; omitted when either is missing.
- Replaces the lambda signature above: `saveMorningCheck: (rmssdMs: Double, measuredAtMs: Long) -> Unit`. The coordinator passes the current time; the lambda derives the local date and writes `measured_at`.
- Unit argument default: `format(v, metric = true)` and `chip(..., metric = true)`, so the non-UI callers (coach context, notifications, CSV, homelab upload) keep SI without edits. Only composables that already hold `useMetricUnits` pass it.
- CEO voice fixes follow.
- Replaces R1.3 "Each day uses its own check, so history keeps the morning value", the Risks line "the stored check replaces the overnight HRV for that day" and the R1 risk bullet above (provisional answer to T1, user decides at the gate): `DayMetrics.hrvMs` is always the overnight average. `morningRmssdMs` is the stored check. `scoreHrvMs = morningRmssdMs ?: hrvMs` feeds that day's Recovery and Readiness and the Today HRV tile, which is labelled "morning check" when it shows the check. The HRV trend and every 30-day HRV baseline use overnight `hrvMs` only. The R4 sheet label names the source: "HRV 62 ms (morning check) vs your 58 ms". Tests: a stored check changes that day's Recovery and Readiness and no other day's; baseline is unchanged by checks; restart keeps the same scores.
- Replaces the "Rollback" bullet above: no prefs mirror. After the one-time copy, nothing reads or writes prefs `weightKg`. A build rolled back below R5 shows the last pre-R5 weight; stated in Risks.
- R2: the every-screen walk happens in the R2 commit, not after R5. The contrast test also asserts outline vs surface at 3.0 or better (WCAG non-text contrast).
- R3.2: the hero is labelled "Wrist skin temperature"; the delta keeps the same text size as the absolute value; one 13sp line reads "Not core body temperature".
- R4: the CSV daily summary adds one column per readiness part (`readiness_hrv_pts`, `readiness_rhr_pts`, `readiness_sleep_pts`, `readiness_vitals_pts`), empty when the part is absent.
- Replaces R6.2 scope (provisional answer to T2): before the design skill exists, the sweep covers colour tokens only. Shape, spacing and type tokens wait for the chosen direction.
- Before implementation starts, every accepted bullet is folded into the R1–R6 sections and the override list is removed, so no stale body line gets built.
- Review section fixes follow.
- `saveMorningCheck` catches `SQLiteException`, logs table, date and value, and reports failure; the flow then shows "Couldn't save the check, try again" and the result is not shown as done. Test: a failing DB leaves no row and yields the failure state.
- `putWeight` and `deleteWeight` catch `SQLiteException`, log, and the sheet shows "Couldn't save" inline and stays open. Test: failing DB keeps the sheet's text and shows the message.
- The weight migration catches `SQLiteException` and leaves its prefs flag unset so it retries on the next start. Test: failure then success inserts exactly one row.
- The log sheet captures `useMetricUnits` when it opens and uses that one value for prefill, label and parsing. Test: toggling units while a draft exists does not reinterpret the number.
- The log sheet's draft state lives inside the sheet composable; `MainDashboardScreen` gains no new state variables for weight.
<!-- /autoplan-accepted:ceo -->

### Phase 2: Design review (via /autoplan)

**Step 0.** Initial design completeness 4/10: the data model is exact, but the five new surfaces (done line, skin temp hero, readiness sheet, weight card, log sheet) had almost no layout, state or copy spec. No DESIGN.md exists; R6 creates the design system once the user's design skill arrives, so R1–R5 calibrate against existing shared components and `HealthColors` only. Reuse: `MetricDetailView`, `ScoreBreakdown` sheet from S3, `ProfileField`, existing card and chip components. Mockups: not generated (gstack designer has no image key).

**Voices.** Codex: not installed, outside coverage unavailable. Native subagent: completed, input hash matched, 26 findings (2 critical, 10 high, 14 medium).

Litmus scorecard (app UI, OPERATE mode):

| Check | Native | Codex | Consensus |
|---|---|---|---|
| 1. Product unmistakable in first screen | NOT SPEC'D | N/A | N/A |
| 2. One strong visual anchor | NO (skin temp hero had two equal numbers) | N/A | N/A |
| 3. Scannable by headings | YES | N/A | N/A |
| 4. Each section one job | YES | N/A | N/A |
| 5. Cards necessary | NO (user complaint 6; waits for R6) | N/A | N/A |
| 6. Motion improves hierarchy | NOT SPEC'D | N/A | N/A |
| 7. Premium without shadows | NOT SPEC'D (R6) | N/A | N/A |
| Hard rejections | #7 stacked cards, existing app, owned by R6 | N/A | N/A |

**Passes** (before → after accepted fixes):

| Pass | Score | Main gaps fixed |
|---|---|---|
| 1 Information architecture | 4 → 8 | Skin temp hero had no leader and differed per surface; HRV tile disagreed with its detail; readiness rows did not say helped or hurt |
| 2 Interaction states | 3 → 8 | Weight card with 0 and 1 logs, log sheet validation, save feedback, skin temp and readiness "building" states, save-failure retry |
| 3 Journey | 6 → 8 | Trust breaks: silent overwrite, invented weight point, weekly weigher gets no trend, lost 60-second measurement |
| 4 AI slop risk | 5 → 7 | R1–R5 may add no new styling literals; hard rejection #7 stays open until R6 |
| 5 Design system | 4 → 7 | Colour scheme only mapped 7 roles (Material purple would leak into switches, pickers, sheets); no DESIGN.md until R6 |
| 6 Accessibility | 2 → 8 | 48dp targets, TalkBack labels, large font, error not by colour alone |
| 7 Decisions | 24 resolved, 2 taste, 2 rejected | see below |

Overall 2/10 → 7/10. Passes 4 and 5 stay below 8 because the redesign direction and design system wait for the user's design skill.

Journey storyboard:

| Step | User does | User feels | Plan specifies |
|---|---|---|---|
| 1 | Finishes morning check, reopens app | Trust: it remembered | Done line with time and value, Redo |
| 2 | Opens profile, types | Can read what they type | Themed fields, contrast test |
| 3 | Opens skin temp | Sees the actual number | Absolute hero, delta under it |
| 4 | Taps "how is this calculated" on Readiness | Understands the score | Dated sheet, points per part |
| 5 | Logs weight | Quick, confirmed | `+`, prefilled, snackbar with Undo |
| 6 | Returns weekly | Sees change with an honest anchor | "−0.6 kg since 3 Oct", line from first two logs |

Rejected: fold all amendments into the body before the gate (pipeline keeps accepted blocks until approval; fold stays task T1; the one cited contradiction is resolved below). Day boundary at 04:00 for the morning check (a check at 00:10 is not a real case; local date stays).

Taste for the gate: T4 updated (trend line with raw-log fallback instead of trend-only). T5 new (skin temp: absolute value leads, delta smaller; CEO phase had them equal size).

NOT in scope: mockups; shape and type tokens; chart redesign; steppers on the weight field; tablet layouts (phone-only app).

What already exists: `HealthColors`, `MetricDetailView` selected-day row and bottom card, S3 breakdown sheet, `ProfileField`, snackbar host in `MainDashboardScreen`.

Design tasks (also in the tasks artifact): D1 full colour role table and test (R2); D2 screen-and-state inventory with baseline screenshots (R2, reused by R6); D3 skin temp hierarchy and states (R3); D4 readiness sheet anatomy (R4); D5 weight card, sheet, edit and delete states (R5); D6 done line (R1).

<!-- autoplan-accepted:design -->
- R1 done line: sits where the morning check card was, one compact line: "Morning check · 07:12 · HRV 62 ms" with a "Redo" text button whose touch target is at least 48dp. Redo runs the normal check flow; cancel or failure keeps the earlier value and line.
- R1 save failure: the measured value stays in memory and the error offers "Retry save", which retries the write without re-measuring. Replaces "try again".
- R1 HRV consistency: when a day has a morning check, the HRV detail header for that day reads "Overnight 55 ms · Morning check 62 ms (used for today's scores)". The Today tile keeps the "morning check" label.
- R2 colour scheme: every `darkColorScheme` role is set explicitly from `HealthColors` (background, surface, surfaceVariant, all surfaceContainer levels, primary/secondary/tertiary and their containers, inverseSurface, outline, outlineVariant, error); `surfaceTint = Color.Transparent`. No Material baseline purple anywhere: a unit test fails if any role still equals the Material 3 dark default for primary, secondary, tertiary or their containers. If `outline` composited on the card background is under 3.0 contrast, the test wins and the colour changes.
- R2 screen inventory: R2 adds a list of every screen and state (including empty, dialog, sheet, picker, error, snackbar) to the on-device checklist with a baseline screenshot each. Pass criteria for the R2 walk: typed text readable, no purple, no unreadable label. The same list is reused for R6.2 and R6.5.
- R3 hierarchy: Replaces "delta same size". Everywhere skin temp appears, the absolute value leads ("34.6 °C"); the delta is a second line at the existing secondary size ("+0.2 vs your average 34.4"). The chart keeps plotting the delta and is titled "Difference from your average".
- R3 states: delta and "your average" show only when the baseline has at least 3 nights; before that the line reads "Building your average · 2 of 3 nights". No reading on the selected day shows "No reading". The bottom average card follows the selected day. In °F the chart axis is in °F delta.
- R4 sheet: titled with the date ("Readiness · Tue 10 Nov"). Each part is a row: name, plain comparison ("Resting HR 58, your average 55"), sub-score and points ("72/100 · 18 pts"). Parts with no data stay as greyed rows ("Sleep · no data · not counted"). Rows show points, not weight percentages; one line under the rows says "Missing parts are left out and the rest are scaled up". Points are rounded by largest remainder so rows always sum to the shown score; CSV `readiness_*_pts` columns use the same rounding.
- R4 null state: "Needs 3 nights of HRV and resting HR · 1 of 3". Replaces the earlier null-readiness note text.
- R5 no invented log: Replaces the prefs to `weight_logs` migration and its prefs flag. Nothing is migrated. Weight for calories is the latest log, else the profile weight in prefs. The prefs value is never plotted. The Profile weight field becomes a row "Weight · 78.4 kg · 12 Nov" that opens the log sheet (shows the prefs value with no date until the first log).
- R5 trend: Replaces "no raw line" and "needs 2 logs or no line". The chart draws a point per log and one line: the 7-day trend value where the window has 2 or more logs, otherwise the raw log, so weekly logging still gives a connected line. Change text names its real anchor: "−0.6 kg since 3 Oct" (latest line value minus the line value at the newest log 7 or more days earlier); no "vs last week" wording. Coach context uses the same anchor date.
- R5 card states: Trends list, last position. Zero logs: "Log your weight" with the profile weight if one exists and a `+` button. One log: value and date, no sparkline, "Log again to see a change". Two or more: value, change line, sparkline of the same line as the detail chart. The weight detail header also has `+`.
- R5 log sheet: modal bottom sheet. Field uses the decimal keyboard, autofocus with select-all on the prefilled latest value, unit suffix, accepts comma or dot. Date opens the Material date picker with future dates and dates over 365 days back disabled. Save is disabled while invalid; the error text gives the range in the user's unit; IME Done saves. If the picked date already has a log, the field prefills that entry, a line reads "Replaces 78.4 kg", and the button reads "Update".
- R5 feedback: after save, a snackbar "Saved 78.4 kg for 3 Nov · Undo". Undo restores the previous state for that date.
- R5 edit and delete: Replaces long-press on a chart point. Selecting a day with a log shows "Edit · Delete" in the selected-day row; delete shows an Undo snackbar. Selecting a day without a log shows "No entry · Log".
- R5 weight detail: no bottom average card (`showBaseline = false`); y-axis does not start at zero and spans at least ±2 kg (±4 lb) around the data; other metrics' charts are not lengthened by an old weight log (series start extension applies to the weight series only).
- R5 calories: with no log and no profile weight the calories tile reads "Log weight to estimate calories".
- Goal 3 wording: Replaces "two taps". Unchanged weight is `+` then Save; otherwise `+`, type, Save.
- Wording: use "your average" everywhere for the personal baseline in user-facing text (not "normal" or "baseline").
- Styling rule for R1–R5: new UI uses existing shared components and `HealthColors` only; no new colour, radius or text-size literals.
- Accessibility: every new control has a 48dp touch target; chart points and sheet rows have TalkBack content descriptions with value, unit and date; the skin temp hero and weight card are checked at font scale 1.3 on a 360dp-wide screen and may wrap but not clip; field errors show supporting text, not colour alone.
- R6.3 acceptance (when the design skill is available): the direction is chosen from variants rendered on three real screens: Today, one metric detail, one sheet.
- On-device checklist additions: weekly-spaced logs give a connected line; back-date onto an existing log shows "Replaces"; delete then Undo; skin temp with 2 nights shows "Building"; switches, date picker and sheets show no purple; TalkBack reads a chart point.
<!-- /autoplan-accepted:design -->

### Phase 2.5: DX review (DX POLISH, via /autoplan)

Ran because the plan matched developer-scope terms. Product type: none of the developer product types; this is an end-user Android app. The only developer-facing surfaces are the internal Kotlin functions the plan adds, the test and verify loop, and the CSV export format.

**Persona.** The owner as solo developer, plus a coding agent implementing R1–R6. Context: builds on Windows, installs on one phone with one band. Tolerance: low for ambiguous spec, since the agent builds what the text says. Expects: one test command and a plan with no contradictions.

**Empathy narrative.** I open the plan to build R5. The body says migrate the prefs weight; a later bullet says don't. One bullet says calories use the log on or before that day, another says the latest log. I pick the later one and last week's calories move, which the checklist forbids. I write `saveMorningCheck` as `-> Unit` because the plan says so, then find it must "report failure" and cannot. I call `format(v)` in a new composable, forget the unit argument, and it compiles and shows kg to a lb user. Nothing told me.

**Benchmark and TTHW.** No peer comparison applies to a personal app. Clock: clean checkout to a green unit test run. Current: about 5 minutes estimated, not measured (`./gradlew testDebugUnitTest`). Target: unchanged. Magical moment: not applicable.

**Voices.** Codex: not installed, outside coverage unavailable. Native subagent: completed, input hash matched, 1 critical, 8 high, 11 medium.

| Dimension | Native | Codex | Consensus |
|---|---|---|---|
| 1. Getting started under 5 min | unknown (no commands in plan) | N/A | N/A |
| 2. Naming guessable | mostly; `metric = true` is a trap | N/A | N/A |
| 3. Errors actionable | no (1 CAP4, 4 CAP6) | N/A | N/A |
| 4. Docs findable and complete | no (override list is the real spec) | N/A | N/A |
| 5. Upgrade path safe | yes, no v4 upgrade test | N/A | N/A |
| 6. Dev environment friction-free | checks need the band | N/A | N/A |

Single-voice critical finding: `saveMorningCheck` typed `-> Unit` cannot report a failed write, so a failed save would show as done and vanish on restart. Fixed below.

Error path matrix (agent-ready-v1, after fixes; trace and context mean a logcat line with the exception):

| Path | Class | what | fix | recovery | code | context | trace | redaction | channel |
|---|---|---|---|---|---|---|---|---|---|
| Weight out of range | validation | pass | pass | N/A | N/A (UI text) | N/A | N/A | pass | pass (inline, Save disabled) |
| `saveMorningCheck` write fails | unexpected | pass | pass (Retry save) | pass (3 tries, then overnight) | pass (event name) | pass | pass | pass | pass (returns false) |
| `putWeight` / `deleteWeight` / Undo fails | unexpected | pass | pass (Retry) | pass | pass | pass | pass | pass | pass (returns false) |
| Read of new tables fails | unexpected | pass | N/A | pass (visible line, no silent fallback) | pass | pass | pass | pass | pass |

Developer journey:

| Stage | Developer does | Friction | Status |
|---|---|---|---|
| 1. Discover | Opens the plan | Body and about 90 override bullets disagree | fixed by fold (task T1) with a zero-"Replaces" check |
| 2. Install | Clean checkout, Gradle sync | none found | ok |
| 3. Hello world | Runs unit tests | no command in plan | fixed (Verify line per commit) |
| 4. Real usage | Implements an R | `-> Unit` result, Boolean unit flag | fixed |
| 5. Debug | Reads logcat after a failed write | no cause logged, no stable name | fixed |
| 6. Upgrade | Installs over v4 | no upgrade test | fixed |

DX scorecard (before → after):

| Dimension | Score |
|---|---|
| Getting started | 5 → 7 |
| API design | 6 → 8 |
| Error messages | 4 → 7 |
| Documentation | 4 → 6 (8 once folded) |
| Upgrade path | 7 → 8 |
| Dev environment | 6 → 7 |
| Community | not applicable (personal app) |
| DX measurement | not applicable (no adoption to measure) |
| Overall | 5 → 7 |

DX implementation checklist: [ ] plan folded, zero "Replaces"; [ ] every write returns a result; [ ] failures logged with exception and event name; [ ] `UnitSystem` has no default; [ ] Verify line per commit; [ ] v4 to v5 upgrade test; [ ] CSV header listed.

Rejected: fold before the gate (taste answers can still change the text; fold is the first task after approval). Synthetic dump fixture (the asset DB is in the repo). Debug-only morning check seeder. One `chartMode` enum in place of `sparse` and `showBaseline` (they vary independently). Rename `skinTempBaseC`.

NOT in scope: "Remove" for a morning check (Redo covers correction; deferred); separate temperature and weight unit settings; automated screenshot tests.

<!-- autoplan-accepted:dx -->
- Write results: `saveMorningCheck: (rmssdMs: Double, measuredAtMs: Long) -> Boolean`, `putWeight(...): Boolean`, `deleteWeight(...): Boolean`; true means the row was written or removed. Replaces the `-> Unit` signature. `SyncCoordinator` calls `onDataChanged` and reports the check done only on true. Tests assert on the returned value.
- Failure logging: every caught `SQLiteException` on the new paths is logged with the exception object and a fixed event name: `morning_check_save_failed`, `weight_save_failed`, `weight_delete_failed`, `new_tables_read_failed`, plus date and DB version.
- Morning check retry: side effects of a failed save are none. "Retry save" allows 3 attempts; after the third failure the screen reads "Couldn't save the check. Scores use overnight HRV today." The pending value is held with `rememberSaveable` so rotation does not lose it.
- Weight failures name the item: "Couldn't save 78.4 kg for 3 Nov · Retry" inline in the sheet; delete failure is a snackbar "Couldn't delete · Retry" and the point stays on the chart. Replaces the bare "Couldn't save".
- Undo goes through `putWeight` / `deleteWeight`; if it fails the snackbar reads "Couldn't undo" and the screen reloads from the database. Test: failing Undo leaves screen and DB equal.
- Read failure: if reading `morning_checks` or `weight_logs` throws, the series loads without them, the failure is logged, and Today shows one line "Couldn't load saved morning check or weight". No silent fallback. Test with a failing read.
- Calorie weight, one rule (replaces R5.7 and the design bullet's "latest log, else profile weight"): for each day use the newest log on or before that day; else the earliest log; else the profile weight in prefs; else null and the "Log weight to estimate calories" tile.
- Units argument: Replaces `metric: Boolean = true`. `enum class UnitSystem { SI, IMPERIAL }`; `format(v, units)` and `chip(..., units)` have no default. Non-UI callers (coach context, notifications, CSV, upload) pass `UnitSystem.SI` explicitly. Test: with IMPERIAL, no temperature or weight surface string contains "°C" or "kg".
- One setting drives both °F and lb; stated in the Profile units row subtitle ("lb and °F").
- `partsFor(metricId, day)` returns null for metrics with no breakdown, and the sheet link is then absent.
- Failure tests: the store functions are passed in as lambdas or run against a closed database; no mocking library is added.
- Date key: `localDateOf(measuredAtMs, zone)` is a pure function with its own test, including 23:59 and 00:10.
- DB upgrade: a test opens a v4 database file fixture with the v5 helper and finds both new tables and all old rows. If `onUpgrade` throws, the app crashes with the cause logged; no recovery UI.
- CSV contract: new daily-summary columns are appended after the existing ones in this order: `morning_rmssd_ms`, `skin_temp_c`, `skin_temp_base_c`, `weight_kg`, `readiness_hrv_pts`, `readiness_rhr_pts`, `readiness_sleep_pts`, `readiness_vitals_pts`. Values are SI; a missing value is an empty field. `readiness_vitals_pts` is SpO₂ and skin temp together. Test: header equals the listed order.
- Verify line: each R section ends with "Verify: `./gradlew testDebugUnitTest`" plus the golden-file regeneration command, copied from the existing golden test's header comment when the plan is folded.
- Screenshots for the R2 inventory and the R6.2 before/after check are taken by hand and stored under `docs/screens/<step>/`.
- Fold check: after the fold (first task once the plan is approved) the plan contains no "Replaces" and no accepted-override list; Risks and each R's test list are updated in the same edit.
- Risks addition: a reinstall loses weight history and morning checks until backup exists (gate decision T3); back-dating cannot re-enter logs older than 365 days.
<!-- /autoplan-accepted:dx -->

### Phase 3: Eng review (via /autoplan)

**Step 0, scope challenge.** Scope accepted as-is (never reduced in this pipeline). Estimated touch: about 16 existing files, 3 new source files (`ui/Theme.kt`, weight sheet state holder, `UnitSystem`), 2 new tables. Over the 8-file threshold, but each R is its own commit and no smaller arrangement keeps all six items. Existing code reused: `ScoreBreakdown` and its sheet, `MetricDetailView`, `putLabel` write pattern, `parseWeightKg`, `Direction.NEUTRAL`, `CsvExporter`, the `loadFailed` path.

Reality rows:

REALITY: premise-table pass 9 claims checked against code, unverified=0 android-app/app/src/main/java/com/jcwear/coach/domain/DailySeriesRepository.kt:153
REALITY: already-done pass no commit on main or this branch touches morning_checks, weight_logs or Theme.kt docs/plans/2026-11-16-refinements.md:1
REALITY: surface-check pass only command named is ./gradlew testDebugUnitTest; Gradle wrapper present android-app/app/build.gradle.kts:59
REALITY: binding-decisions pass user direction "redesign waits for the design skill" kept; T1–T5 held for the gate docs/plans/2026-11-16-refinements.md:84
REALITY: numbers pass weights 0.40/0.25/0.25/0.10 [measured: android-app/app/src/main/java/com/jcwear/coach/domain/DailySeriesRepository.kt:159-166]; effort sizes [estimated] android-app/app/src/main/java/com/jcwear/coach/domain/DailySeriesRepository.kt:159
REALITY: deferred-asks pass no linked issue; all six user items are in scope docs/plans/2026-11-16-refinements.md:7
REALITY: pr-count pass one PR for R1–R5 and the colour sweep; R6.3–6.5 is a later PR after the design skill docs/plans/2026-11-16-refinements.md:94
REALITY: transaction-semantics pass no driver or pool change; single-row CONFLICT_REPLACE writes, no foreign keys or triggers android-app/app/src/main/java/com/jcwear/coach/data/LocalHealthDatabase.kt:129

**Voices.** Codex: not installed, outside coverage unavailable. Native subagent: completed, input hash matched, checked the code, 2 high, 9 medium, 10 low. Its code claims were re-verified here (ScoreBreakdown.kt:14, DailySeriesRepository.kt:168, J2208BleManager.kt:341-343, data_extraction_rules.xml, HomelabClient.kt:65-66, build.gradle.kts:59-62).

| Dimension | Native | Codex | Consensus |
|---|---|---|---|
| 1. Architecture sound | yes, after H2 and M6 fixes | N/A | N/A |
| 2. Test coverage sufficient | no: 4 listed tests could not run without a Compose test dependency | N/A | N/A |
| 3. Perf risks named | full reload per refresh; fine at one user's volume, unmeasured | N/A | N/A |
| 4. Security covered | no new network surface; coach context gains weight and skin temp | N/A | N/A |
| 5. Error paths handled | after M3 (cancel between measure and save) | N/A | N/A |
| 6. Deployment risk manageable | yes; v5 additive, downgrade no-op | N/A | N/A |

**Section 1, architecture.** 4 findings.

```
J2208BleManager.morningCheck60s ──> SyncCoordinator.morningCheck(): MorningCheckResult
                                         │ saveMorningCheck (IO, NonCancellable)
                                         v
 WeightLogSheetState ──> DashboardViewModel ──> LocalHealthDatabase v5
   (plain class)            (suspend, IO)        ├─ morning_checks(date, rmssd_ms, measured_at, source)
                                                 └─ weight_logs(date, weight_kg, logged_at)
                                                        │ getAll*
 AppSettings(heightCm, profileWeightKg) ──> DailySeriesRepository.load ──> buildDaySeries
                                                        │                    ├─ hrvMs (overnight only)
                                                        │                    ├─ morningRmssdMs, scoreHrvMs
                                                        │                    ├─ skinTempC / BaseC / DeltaC (3-day rule)
                                                        │                    ├─ weightKg, caloriesKcal
                                                        │                    └─ readinessParts (raw weights + divisor)
                                                        v
   Today / Trends / MetricDetailView / CsvExporter / ClaudeCoachRepository / HomelabClient / notifications
                                  UI formats through UnitSystem (SI | IMPERIAL)
```

- [CRITICAL] (confidence: 9/10) domain/ScoreBreakdown.kt:14 — `parts.sumOf { it.points }.roundToInt().coerceIn(0, cap ?: 100)` cannot equal legacy `(Σw·v / Σw).roundToInt().coerceIn(1, 100)` (DailySeriesRepository.kt:168) with pre-normalised weights; floating-point error flips .5 boundaries and the floor differs (0 vs 1). The R4 strict-equality guard would fail. Fix accepted: raw weights plus a divisor.
- [CRITICAL] (confidence: 8/10) ui/MainDashboardScreen.kt:138-148 — the check runs in `LaunchedEffect(checkStep)`; Back cancels it, so a cancel between the band result and the insert loses the measurement, and the insert would run on the main thread. Fix accepted.
- [INFORMATIONAL] (confidence: 8/10) domain/DailySeriesRepository.kt — one `List<DayMetrics>` feeds every chart and index; a weight-only longer series needs a second data path. Fix accepted: back-dating limited to the existing series range.
- [INFORMATIONAL] (confidence: 8/10) ble/J2208BleManager.kt:341-343 — `directHrv ?: calculateRmssdFromPpi(pseudoPpi)`: the stored check can be an estimate from heart rate with no range filter. Fix accepted: `source` column and 15..200 range.

Two shapes for weight storage: (A) table in `LocalHealthDatabase` read by the repository (chosen); (B) Health Connect as the store (rejected in the CEO phase: new dependency and permissions). A hides more behind the existing `load` call. Red flags: hand-synced list (`HISTORY_TABLE_COLUMNS` vs tables) is deliberate and tested by exclusion; two ways to format a unit is removed by `UnitSystem`.

Dispositions: all 4 accepted (auto-decided, audit rows 35–38).

**Section 2, code quality.** 5 findings, all accepted.

- [INFORMATIONAL] (9/10) the `hrvMs` split has about 30 uses in 11 files and the plan named four. Consumer table added below.
- [INFORMATIONAL] (9/10) domain/DailySeriesRepository.kt:132-133, :164 — skin temp delta drives a 25-point readiness penalty from a one-reading baseline while the screen hides it below 3 nights. One rule accepted.
- [INFORMATIONAL] (9/10) domain/DailySeriesRepository.kt:157 — readiness is also null when today's HRV or resting HR is missing with a full baseline; "Needs 3 nights · 3 of 3" would be wrong. Two messages accepted.
- [INFORMATIONAL] (8/10) domain/Profile.kt:9 — `toDoubleOrNull` rejects "78,4"; the lb minimum 66.1 lb converts to 29.98 kg and is rejected. Fix accepted.
- [INFORMATIONAL] (7/10) partial-read wrapper and 3-attempt retry are over-built; existing `loadFailed` path and a single Retry accepted.

**Section 3, tests.** Framework: JUnit 4 + Robolectric (build.gradle.kts:59-62); no Compose UI test or mocking library. 6 gaps, all resolved by moving logic out of composables rather than adding a dependency.

```
CODE PATHS                                              USER FLOWS
[+] LocalHealthDatabase v5                              [+] Morning check
  ├── [GAP→unit] onCreate fresh, v3→v5, v4→v5, v5→v4→v5   ├── [GAP→unit] done, restart, still done (result + stored row)
  ├── [GAP→unit] putMorningCheck replace, range, source   ├── [GAP→device] Back during save keeps value
  └── [GAP→unit] putWeight/deleteWeight result, replace   ├── [GAP→unit] redo fails, old value kept
[+] buildDaySeries                                       └── [GAP→device] app open overnight, card returns
  ├── [★★★ TESTED] golden series (regenerated twice)    [+] Weight
  ├── [GAP→unit] hrvMs overnight only; check not in base  ├── [GAP→unit] sheet state: invalid, Replaces/Update, comma
  ├── [GAP→unit] check with no overnight HRV              ├── [GAP→unit] undo restores prior row incl. logged_at
  ├── [GAP→unit] skin temp 3-day rule (delta null)        ├── [GAP→unit] picker millis → date (UTC)
  ├── [GAP→unit] calorie weight: 4-step rule              └── [GAP→device] weekly logs give a line
  └── [GAP→unit] weight line: trend / raw fallback      [+] Units
[+] readinessParts                                        └── [GAP→unit] IMPERIAL strings have no °C or kg
  ├── [GAP→unit] CRITICAL equality vs legacyReadinessScore (dump + grid)
  ├── [GAP→unit] points sum to score (largest remainder) [+] Text fields
  └── [GAP→unit] two null reasons                         ├── [GAP→unit] contrast and no-purple roles
[+] CsvExporter                                           └── [GAP→device] screen inventory walk
  └── [GAP→unit] header order, empty fields

COVERAGE (planned): 24 paths, 20 unit, 4 device-only  |  existing: 1/24
```

Regression rule: three existing behaviours are at risk and each has a dedicated test: readiness numbers (equality guard), history series (golden file), `NoFakeValuesTest` with no logs. Intended differences: `hrvMs` no longer carries today's morning check; skin temp delta is null under 3 baseline days (changes early-day readiness).

Tests made obsolete: none. Test plan artifact: `C:\Users\stav\.gstack\projects\onions127-Health-\stav-health-plus-fixes-eng-review-test-plan-20261116-233542.md`.

**Section 4, performance.** 1 finding. [INFORMATIONAL] (6/10, medium confidence) every refresh re-reads all tables and R5 adds refresh triggers; rows are in the low thousands for one user; unmeasured. Accepted: log load time once on the real dump during R5; no budget set.

**Failure modes.**

| Path | Realistic failure | Test | Handling | User sees |
|---|---|---|---|---|
| Save morning check | Back pressed mid-save | device | NonCancellable save | value kept |
| Save morning check | DB write throws | unit | SaveFailed result | "Retry save" |
| Morning check value | band returns HR only | unit | source = hr_estimate | "estimated" label |
| Weight save / delete / undo | DB write throws | unit | false result | inline or snackbar error |
| v4 → v5 upgrade | create throws | unit (fixture) | crash with logged cause | crash (accepted) |
| Readiness sheet | rounding differs from score | unit | divisor + largest remainder | never disagrees |
| Date picker | zone west of UTC | unit | UTC conversion | correct day |
| App open overnight | stale `today` | device | refresh on resume | card returns |

Critical gaps (no test, no handling, silent): 0.

**Parallelization.** Sequential implementation, no parallelization opportunity: R1, R3, R4 and R5 all edit `DailySeriesRepository.kt` and `DayMetrics`.

NOT in scope: Compose UI test dependency; load-time budget; `chartMode` enum; baseline of prior morning checks (offered at gate under T1).

What already exists: `ScoreBreakdown` sheet (S3), `loadFailed` path, `putLabel` (LocalHealthDatabase.kt:129-141), no-op `onDowngrade`, Android auto-backup already includes the database file.

Completion summary: Step 0 scope accepted as-is. Reality rows 8/8, 0 unverified premises. Architecture 4 issues. Code quality 5. Test review: diagram produced, 6 gaps (23 planned paths without a test today). Performance 1. NOT in scope written. What already exists written. TODOS.md: 3 items held for the gate (C5, C6, Remove morning check). Failure modes: 0 critical gaps. Unresolved decisions: 0 in this phase (5 taste decisions go to the gate). Outside voice: Codex unavailable (not installed). Parallelization: 1 lane, sequential. Lake score: N/A.

<!-- autoplan-accepted:eng -->
- Still-live contradictions are settled here and these win: (1) calorie weight follows the DX rule; deleting the only log falls back to the profile weight and the Profile row shows that value undated. (2) Callers pass `heightCm` and `profileWeightKg` (prefs fallback only); the repository reads logs itself. (3) No series extension at all. (4) The migration-retry bullet and the prefs-mirror test are dead. (5) Skin temp has one baseline rule, below.
- R4 score maths: Replaces "the breakdown stores the normalised weight". `ScoreBreakdown` gains `divisor: Double = 1.0` and `floor: Int = 0`; `score = (parts.sumOf { points } / divisor).roundToInt().coerceIn(floor, cap ?: 100)`. `readinessParts` keeps raw weights 0.40/0.25/0.25/0.10 with `divisor` = sum of present weights and `floor = 1`, which is the legacy expression exactly. Sheet points are derived from the final integer by largest remainder. Existing Daily, Recovery and Sleep breakdowns are unchanged (defaults).
- R1 result type: `SyncCoordinator.morningCheck()` returns `sealed MorningCheckResult { MeasureFailed; Saved(rmssdMs); SaveFailed(rmssdMs) }`. The save and `onDataChanged` run in `withContext(Dispatchers.IO + NonCancellable)`, so Back cannot drop a finished measurement. On `SaveFailed` the UI keeps the value in the flow state and shows one "Retry save" button (replaces the 3-attempt rule and the `rememberSaveable` claim; the pending value does not survive process death).
- R1 table: `morning_checks` gains `source TEXT NOT NULL` with values `hrv` or `hr_estimate` (the band path at `J2208BleManager.kt:341-343` falls back to an estimate from heart rate). A value outside 15..200 ms is a failed check and is not saved. The done line appends "estimated" for `hr_estimate`. CSV `morning_checks.csv` includes `source`.
- R1 consumers of the split (each named in the folded plan): `hrvMs` overnight only: HRV trend, baselines, `MetricInsights`, `HomelabClient` `overnight_hrv_ms`, CSV `overnight_hrv_ms`. `morningRmssdMs`: done line, HRV detail header, `HomelabClient` `morning_rmssd_ms`, CSV. `scoreHrvMs`: `recoveryParts` gate, `readinessParts`, Today HRV tile, coach context (labelled with its source), morning summary notification. A day with a check and no overnight HRV has `hasData = true`, computes Recovery and Readiness from the check, and shows no point on the HRV trend. Tests for that day.
- R1 freshness: the dashboard reloads on resume when the local date differs from the loaded series' last day. After a successful check the flow's result stays on screen until the reloaded `today.morningRmssdMs` is set, so the start card does not flash.
- R3 one skin temp rule: in `buildDaySeries`, `skinTempBaseC` and `skinTempDeltaC` are null unless the prior 30 days hold at least 3 days with a reading; the baseline is the mean of day averages; N counts those days. Readiness uses the same delta, so the sheet never cites a delta the screen hides. Golden file regenerated in the R3 commit. Test: 2 baseline days give null delta and no penalty.
- R4 null states, two messages (replaces the single one): today's HRV or resting HR missing: "No HRV or resting HR for this day". Baseline short: "Building your average · N of 3 nights" with N = min(HRV days, resting HR days). Test for each.
- R5 back-dating: Replaces the series extension and the 365-day rule. The date picker allows only dates inside the loaded series (from its first day, at least 90 days back, to today). `pickerMillisToLocalDate` converts the picker's UTC-midnight millis with UTC; test at UTC−8 and UTC+2.
- R5 input: `parseWeightKg` accepts a comma decimal and numbers are formatted with `Locale.US`; the range check runs on the entered unit's own bounds (30.0–300.0 kg, 66.1–661.4 lb) before conversion. Tests: "78,4"; 66.1 lb accepted.
- R5 writes: `putWeight`, `deleteWeight` and Undo are suspend calls through `DashboardViewModel` on `Dispatchers.IO`. Undo restores the prior row including `logged_at`; a new save while an Undo snackbar is showing dismisses that snackbar.
- R5 testability: sheet logic (validation, Replaces/Update, unit capture, error text, undo payload) lives in a plain `WeightLogSheetState` class and user-facing strings in pure functions, unit tested under Robolectric or plain JUnit. No Compose UI test or mocking dependency is added. The "sheet stays open", "units toggle mid-draft", "failing Undo" and "no °C under IMPERIAL" tests target those classes.
- Read failure: Replaces the partial-load line. A throwing read of the new tables takes the existing `loadFailed` path, logged with the exception and `new_tables_read_failed`.
- Change text when the line mixes a 7-day mean and a single log is accepted as is; the anchor date is always shown.
- Goal and checklist wording: "calories for last week don't move when today's weight changes"; logging the first weight or back-dating does change earlier days, by design.
- Coach: the coach settings text gains one line: "Sends weight and skin temperature to Anthropic with your other metrics."
- Risks correction (replaces "a reinstall loses weight history"): `allowBackup` is true and the backup rules exclude only `app_settings.xml`, so Android auto-backup and device transfer carry the database with both new tables, but not the profile prefs. A restored install has logs and no profile weight or height. Sterver backup of the two tables stays gate decision T3.
- Commits: R1 (DB v5 with both tables, morning check), R2, U (`UnitSystem`, before R3), R3, R4, R5a (weight store, calories, C3), R5b (weight UI, CSV, coach), then the colour sweep. One PR.
- Migration tests: fresh install via `onCreate`; v3 to v5; v4 to v5 from a fixture file; v5 opened by v4 code path and back to v5 (tables survive).
- Load time: R5a logs `buildDaySeries` load time once on the real dump and records the number in the commit message; no budget is set.
- On-device checklist additions: press Back the moment the check finishes, value is kept; leave the app open overnight, the start card is back in the morning; pick yesterday in the weight date picker and confirm the saved date; first reading of skin temp on a new install shows no delta.
<!-- /autoplan-accepted:eng -->

<!-- AUTONOMOUS DECISION LOG -->
## Decision Audit Trail

| # | Phase | Decision | Classification | Principle | Rationale | Rejected |
|---|-------|----------|----------------|-----------|-----------|----------|
| 1 | CEO | Mode SELECTIVE EXPANSION | Mechanical | override | /autoplan fixes the mode | other modes |
| 2 | CEO | Accept C1 weight 7-day trend line | Mechanical | P1 | raw daily weight is noise; in blast radius, S | raw line only |
| 3 | CEO | Accept C2 Redo morning check | Mechanical | P1 | redo rule had no entry point | hide card only |
| 4 | CEO | Accept C3 dashboard gets height/weight | Mechanical | P2 | bug in a touched file | leave for later |
| 5 | CEO | Accept C4 one-decimal weight | Mechanical | P1 | rounding defeats tracking | `%.0f` |
| 6 | CEO | Defer C5 server backup | Taste (T3) | P3 | outside blast radius; existing TODO | include now |
| 7 | CEO | Defer C6 weight goal | Mechanical | P3 | not requested | include now |
| 8 | CEO | Skip C7, C8 | Mechanical | P4 | duplicate TODO; not requested | add |
| 9 | CEO | Keep morning check as the day's HRV in history | Taste (T1) | P5 | matches what the user saw at check time | separate series |
| 10 | CEO | Keep token sweep before design skill, limited to colour/shape/type | Taste (T2) | P6 | mechanical, direction-agnostic | wait for skill |
| 11 | CEO | P6 accepted as an assumption, flagged | Mechanical | P6 | only the design skill can test it | block R6 planning |
| 12 | CEO | 33 spec-review issues applied as accepted bullets | Mechanical | P1 | each was a contradiction, gap or infeasible step | leave open |
| 13 | CEO | T1 provisional answer changed: overnight HRV for trend and baseline, check stored separately and used for that day's scores | Taste (T1) | P5 | voice V1: blended baseline is permanent and compares unlike values | blend as first planned |
| 14 | CEO | Cut prefs weight mirror | Mechanical | P5 | permanent dual-write for a rare rollback of a personal build | keep mirror |
| 15 | CEO | Keep 7-day trend line despite voice V3 | Taste (T4) | P1 | small once sparse mode exists | points-only v1 |
| 16 | CEO | Reject Health Connect as the weight store | Mechanical | P4/P5 | new dependency and permissions; local table needed anyway | replace table |
| 17 | CEO | Keep user's "design skill later"; sweep colour tokens only until then | Taste (T2) | user direction | single-voice challenge, not a User Challenge | run /design-consultation now |
| 18 | CEO | Keep °F and lb | Mechanical | P1 | unit setting exists; argument needed for lb anyway | cut °F |
| 19 | CEO | Accept V5, V6, V8 (CSV columns), V9 | Mechanical | P1 | cheap and each closes a real gap | skip |
| 20 | CEO | Rescue SQLite write failures in the three new write paths; capture units at sheet open | Mechanical | P1 | silent data loss otherwise | copy `saveLabel`'s log-only catch |
| 21 | Design | Skin temp: absolute value leads, delta smaller | Taste (T5) | P5 | user asked for the number; two equal numbers have no leader | equal size (CEO phase) |
| 22 | Design | Weight line falls back to raw logs when the 7-day trend is undefined | Taste (T4) | P1 | weekly weighers otherwise never get a line | trend-only, or 14-day window |
| 23 | Design | Drop prefs to weight_logs migration; fall back to profile weight | Mechanical | P5 | no invented dated point; removes migration and flag | confirm prompt with unplotted row |
| 24 | Design | Edit/Delete in selected-day row, not long-press | Mechanical | P1 | discoverable, 48dp, TalkBack path | long-press chart point |
| 25 | Design | Full colour role table plus no-purple test | Mechanical | P1 | unmapped roles leak Material purple | 7 roles only |
| 26 | Design | Readiness rows show points with largest-remainder rounding | Mechanical | P1 | rows must sum to the score | percentages |
| 27 | Design | Reject fold-before-gate and 04:00 day boundary | Mechanical | P3 | pipeline folds after approval; 00:10 check is not a real case | accept both |
| 28 | Design | 17 remaining state, copy and accessibility findings accepted as bullets | Mechanical | P1 | each was an unspecified state or target | leave to implementer |
| 29 | DX | Write functions return Boolean; refresh only on success | Mechanical | P1 | `-> Unit` cannot report failure; failed save would show as done | sealed result type |
| 30 | DX | `UnitSystem` enum with no default | Mechanical | P5 | a forgotten Boolean default compiles and shows wrong units | `metric = true` default |
| 31 | DX | One calorie-weight rule | Mechanical | P5 | two bullets contradicted; checklist needs per-day log | latest log only |
| 32 | DX | Fold stays after the gate, with a zero-"Replaces" check | Mechanical | P3 | taste answers may still change text | fold now (raised by design and DX voices) |
| 33 | DX | Defer "Remove morning check"; keep one units toggle | Mechanical | P3 | not requested; Redo covers correction | add both |
| 34 | DX | 12 remaining logging, test-seam, CSV and verify findings accepted | Mechanical | P1 | each closes an unspecified failure or contract | skip |
| 35 | Eng | Raw weights plus divisor in `ScoreBreakdown` | Mechanical | P5 | normalised weights cannot equal the legacy score at .5 boundaries | relax the equality guard |
| 36 | Eng | Sealed check result; save on IO, NonCancellable | Mechanical | P1 | Back between measure and insert loses the value | Boolean only |
| 37 | Eng | Back-dating limited to the loaded series; no extension, no 365 rule | Mechanical | P5 | one day list feeds every chart; extension needs a second data path | extend all or weight only |
| 38 | Eng | `source` column and 15..200 range on morning checks | Mechanical | P1 | the band path can store an estimate from heart rate | add in a later v6 |
| 39 | Eng | One skin temp baseline rule (3 days) in `buildDaySeries` | Mechanical | P5 | sheet would cite a delta the screen hides | display-only rule |
| 40 | Eng | Logic in plain state holder, no Compose test dependency | Mechanical | P4 | 4 listed tests could not run in this setup | add ui-test-junit4 |
| 41 | Eng | Simplify: existing `loadFailed`, single Retry | Mechanical | P5 | wrapper through three callers for a rare case | DX-phase partial load |
| 42 | Eng | Keep morning check in scores vs overnight baseline, flagged for T1 | Taste (T1) | P6 | existing behaviour today; voice says unlike measurements | keep out of scores; baseline of prior checks |
| 43 | Eng | Fold after gate answers, in this run, then re-read | Mechanical | P3 | third voice asked for fold-first; gate answers change the text | fold before gate |
| 44 | Eng | Remaining low findings (comma input, UTC picker, backup risk text, commits split, migration tests, coach line) accepted | Mechanical | P1 | each verified in code | skip |

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 1 | CLEAR (via /autoplan) | scope accepted in full; 0 unresolved |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | CLEAR (via /autoplan) | score 2/10 → 7/10; 26 findings, 24 accepted |
| DX Review | `/plan-devex-review` | Developer experience gaps | 1 | CLEAR (via /autoplan) | score 5/10 → 7/10; 20 findings, 15 accepted |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR (via /autoplan) | 21 findings, all dispositioned; 0 critical gaps |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | unavailable | Codex not installed; every phase had one reviewer |

- **UNRESOLVED:** 0
- **GATE:** approved by the user 2026-11-17 (T1a, T2a, T3a, T4a, T5a; plan approved)
- **VERDICT:** CEO + DESIGN + DX + ENG CLEARED. Ready to implement, starting with R1.
