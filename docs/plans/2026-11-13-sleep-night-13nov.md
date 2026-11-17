# S0: the bug night (pulled 2026-11-16)

Source: `tools/ble_verify/phone-2026-11-16-v3-backup.db` (phone DB before the S1 install, git-ignored).

## Result: the band was right

The user confirmed both nights (2026-11-16):

| Night | Band window | Asleep | User |
|---|---|---|---|
| 12 → 13 Nov | 01:48 → 05:51 | 3h 43m | right (~4 h) |
| 13 → 14 Nov | 21:00 → 08:21 | 11h 12m | right (over 11 h) |

The current build shows both nights correctly. The "10h 34m on 13 Nov" from TODOS.md **can't be reproduced** from the stored band data: no stitch of the stored blocks gives 10h 34m for the night ending 13 Nov. The most likely cause was the build or sync state at the time (13 → 14 at 07:34 = 10h 34m, read as the 13 Nov night).

## What S0 did find

- **Junk 5-min "sleep" records.** 233 records come from the 34-byte stride parser. 231 hold codes like 82/109/215 and dates from 2000 to 2035. They padded nights by up to 2 h (12 Nov started at 00:59 instead of 02:24). **Fixed in S1:** the stitcher ignores 5-min records.
- **Light-only nights are common** (15 → 16 Nov: 23:41 → 09:58, all light). S1's no-stages handling applies.
- **Dense HR lags block start** (13 → 14: blocks 21:00, dense 5-min HR 21:35). Keep the onset rule from the eng contract: a stretch before the first dense HR sample is "no evidence".

## Decision (per plan S0.3)

- **No evidence the band's window is wrong** on the reported night. S2 shrinks to logging plus the harness. A refiner rule ships only if labeled nights show the band off by more than 30 min (≥ 3 EDITED nights, the existing gate).
- The phone screen/unlock signal (d4) stays deferred.
- 4 nights labeled CONFIRMED-of-BAND so far (12 → 13 through 15 → 16 Nov).
