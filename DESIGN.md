# Health++ design: Instrument

Chosen 2026-11-17 (R6.2) from three variants. The app should read like a measuring instrument: flat, neutral, numbers first. No bubbles.

## Rules

- **Ground:** flat graphite, no gradient, no glow. Colour is reserved for meaning (score green/amber/coral, one amber accent for actions).
- **Panels:** a panel is a lighter surface on the ground. No border, no shadow. Two boxes deep at most; a row inside a panel uses `BgDeep` or no fill.
- **Radius:** one scale in `HealthShapes`: `Panel` 8dp (cards, buttons, fields, rows), `Small` 4dp (bars, tags), `Sheet` 8dp top corners. No pills.
- **Status:** a 6dp dot and text (`StatusChip`), never a filled chip. The text carries the meaning; colour only repeats it.
- **Scores:** a large number over a 3dp level bar (`ScoreGauge`). No rings.
- **Type:** four sizes in `HealthType`: `Caption` 11, `Small` 13, `Body` 16, `Display` 40. Weights: Medium for titles and values, SemiBold only for the primary button and chevrons.
- **Colour:** every colour is a `HealthColors` token; a test fails on a raw `Color(0x…)` in `ui/`. Contrast and no-purple tests in `ThemeContrastTest` must pass.
- **Touch and access:** 48dp targets, TalkBack labels, errors never by colour alone (unchanged from R1–R5).

## Tokens

| Token | Value |
|---|---|
| `BgDeep` | `#0D0F13` |
| `BgCard` | `#181B21` |
| `BgCardElevated` | `#20242C` |
| `TextPrimary` | `#F8FAFC` |
| `TextSecondary` | `#A6ADBA` |
| `AccentAmber` | `#FFB86B` |

Source of truth: `ui/DashboardModels.kt` (`HealthColors`) and `ui/Theme.kt` (`HealthShapes`, `HealthType`).
