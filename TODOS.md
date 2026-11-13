# TODOS

Deferred from the CEO review of `docs/plans/2026-11-12-health-plus-fixes.md` (2026-11-12).

- [ ] **Data provenance under scores** (D5). A small line such as "Based on 23 nights · synced 07:12", and tapping a score shows its inputs. Reuses the baseline engine's day counts. Effort S. Deferred to keep the trust-fix release small.
- [ ] **Morning "scores ready" notification** (D6). After the background sync pulls last night's data: "Daily 82 · Good day for a hard session". Needs Phase 1d background sync and notification permission. Effort S.
- [ ] **Bedtime reminder** (D7). An evening nudge based on your usual bedtime, tied to the sleep timing score. Needs the validated timing score and notifications first. Effort M.
- [ ] **CSV export** (D8). Settings → Export all data through the Android share sheet. Effort S. Sterver already backs the data up, so this is nice-to-have.

From the 1a on-device test (2026-11-13):

- [ ] **Sleep duration far too long** (P0, #10). 13 Nov: Sleep Detail said 10h 34m, 23:10 – 06:30; real night was asleep ~01:00–02:00, awake 05:50. The 23:10 – 06:30 window and stage minutes were hardcoded (fixed in 1b, which shows the band's real start/end). The total is the band's own data: the stitcher merges every 0x53 block with gaps of 45 min or less, and the J2208 logs long runs of plain "light" sleep while you lie still awake (e.g. 11 Nov 00:05–08:51 is all light, zero deep/REM). 1b also stops overlapping blocks (02:24:01 vs 02:23:01) counting the same minute twice. Next: compare 1b's "Fell asleep / Woke" with reality for a few nights, then trim leading/trailing light-only runs using HR and steps (sleep onset = first deep/REM or HR drop; wake = first steps or HR rise). Effort M.
- [ ] **Unpair / forget the band** (P1). Settings → Band → "Forget band": disconnect and close GATT, clear the saved band address (today `KNOWN_BAND_MAC` is hardcoded in `J2208BleManager`), and drop the CompanionDeviceManager association once 1d adds it. The next sync then scans for any J2208. Fits with 1c (it touches connect). Effort S.
