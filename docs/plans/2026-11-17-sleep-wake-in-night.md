# Health++: waking up in the night (W1–W3)

## What happened on 17 Nov

You slept 02:00–05:20, were awake until about 06:10, then slept 06:10–10:45: **7h 55m asleep, 50m awake**.

What the band stored (`sleep_blocks`, read from the phone):

| Band block | Stages |
|---|---|
| 02:19–05:54 | light sleep, 2 awake minutes |
| 05:54–06:22 | **no data** |
| 06:22–10:46 | light sleep, 9 REM minutes |

- **The band missed the wake.** It scored 05:20–05:54 as light sleep and recorded nothing for 05:54–06:22. No setting fixes that; only you can say when you were awake.
- **The edit can't say it either.** The dialog has one "Fell asleep" and one "Woke", so the stored edit is 02:19–10:19. Inside an edited window, minutes with no band data count as asleep. That is why the total doesn't match what you remember, and it doesn't match the window either: the window is 8h 00m, minus the band's 2 awake minutes, so 7h 58m.
- **A second, related bug in history.** Band sessions more than 45 min apart are separate sessions, and each day keeps only the longest one, so the shorter part of the night is dropped. History has 6 such nights out of 124, for example 26 Sep (00:08–01:58 lost) and 11 Oct (00:26–01:17 lost).

## Fix

### W1. A night can contain a wake (S)
In `stitchSleepSessions`:
- Join blocks up to **90 min** apart, instead of 45. In history this joins exactly the 6 real wake-ups. Evening dozes 91 and 109 min before bed stay separate.
- Minutes with no band data inside a night count as **awake** (`STAGE_AWAKE`), not as nothing. Then asleep + awake = time in bed, always.

One function, so every caller gets the change: the dashboard, the sleep-label notification and the bedtime reminder.

Effect on history:
- 6 nights gain their missing part.
- Nights with no-data gaps get a slightly lower efficiency, a 10% part of the sleep score.
- Duration doesn't change, because those minutes never counted as asleep.

### W2. "Awake in the night" in the edit (M)
- **Dialog:** under Fell asleep / Woke, a list of awake periods (from–to), each with remove, and an **Add awake time** button. The dialog scrolls.
- **Storage:** DB v6 adds `sleep_labels.awake TEXT NOT NULL DEFAULT ''`, holding ISO ranges separated by `;`. An older build ignores the column, because it selects columns by name.
- **Engine:** `applyEditedWindow(s, onset, wake, awake)` marks minutes inside the awake ranges as awake. No-data minutes outside them stay asleep and unstaged, as now: inside an edit, your times win.
- **Times:** each awake time goes on the first instant at or after "Fell asleep", the same rule as "Woke".
- **Checks in `checkEdit`:**
  - An awake period must lie inside the night, and its end must come after its start.
  - Overlapping periods are merged.
  - A night that would be all awake is refused, with the message "No sleep left in this night".

### W3. Show the arithmetic (S)
Under "Fell asleep 02:00 · Woke 10:45", the detail shows **"In bed 8h 45m · awake 50m"**, so the big number (7h 55m asleep) adds up. Copy only; it reuses `awakeMin`.

## Tests
- **Stitching:**
  - Blocks 50 min apart are one night, with the gap counted as awake.
  - Blocks 100 min apart are two nights.
  - asleep + awake = end − start + 1 for a stitched night.
- **Edit:** 02:00–10:45 with awake 05:20–06:10 gives 475 min asleep and 50 min awake. These are your 17 Nov numbers.
- **Checks:**
  - An awake period outside the night is refused.
  - Overlapping periods are merged.
  - An all-awake night is refused.
  - A wake time after midnight anchors to the next day.
- **DB:**
  - A v5 to v6 upgrade keeps the existing labels, with no awake periods.
  - Awake periods survive a save and a reload.
- **Golden file:** regenerated once in W1. The diff should show only the 6 joined nights and the efficiency changes.

## Order
W1 → W2 → W3, one commit each, each with its tests. Then on the phone: re-edit 17 Nov to 02:00–10:45, awake 05:20–06:10, and expect 7h 55m.

## Not doing
- Detecting the wake automatically from heart rate.
- Naps as separate sleep.
- Awake periods in the sleep-label notification, which stays as Confirm / Edit.
- Changing which session is the "main" sleep beyond the 90-min join.

## Risks
- **The 90-min join can join an evening doze to the night.** History has none within 90 min (the closest is 91), but if it happens, the night starts earlier and the timing part of the score drops. Edit the night to fix it.
- **Old labels keep matching.** Existing edited labels are keyed to the band's first minute. A newly joined night starts earlier, but label matching already falls back to the night with the most overlap.
