# Sky plan: more cloud types, fog, and a sky driven by the body (draft, 2026-11-17)

Status: D1–D4 decided 2026-11-17 (see section 7). D6 decided too. D5 defaults to all eight cumulus items in the listed order unless the user strikes some.

## 1. What the app can actually feed the sky
These come from the app's own models (android-app, `ui/` and the data layer):
- **Daily scores:** readiness and its parts; recovery and its parts; sleep score and its parts.
- **Sleep:** total sleep minutes, deep, REM, awake, sleep efficiency.
- **Overnight signals:** HRV (ms), resting heart rate (bpm), skin-temperature change from baseline (°C), SpO2 (%).
- **Day signals:** stress, strain, steps, calories.
- **Body:** weight log.
- **Constraint:** minSdk is 26 (Android 8), but AGSL shaders need Android 13. The app therefore needs a fallback whatever we pick (see section 6).

## 2. The sky grammar (what we will be able to draw)
| Level | Cloud | How it is drawn | Cost |
|---|---|---|---|
| Ground | Fog and mist: valley fog sitting in the hollows between meadow layers, low banks drifting, a general haze | Height fog shaped by noise, lit by the sun (glows toward it) | Low |
| Low, 0.3–2 km | Stratus: a flat grey sheet with a low ceiling and soft undulations | A volume with near-even density in a thin slab | Low |
| Low, 0.5–2.5 km | Stratocumulus: lumpy sheets with gaps of blue | A volume using a "type" profile between stratus and cumulus | Medium |
| Low to mid, 1.5–8 km | Cumulus: humilis (small), mediocris, congestus (towers) | The current volume, refined (section 3) | High |
| Mid, 3.5–5 km | Altocumulus, "mackerel sky" (optional) | A thin volume slab with fewer steps | Medium |
| Precipitation | Light rain or drizzle under nimbostratus/stratus: fine streaks with depth, a soft rain veil hiding the far range, a darker wet meadow | Screen-space streaks plus a falling-density volume under the cloud base | Low–medium |
| High, 8–12 km | Cirrus (streaks, mare's tails), cirrostratus (milky veil, halo round the sun), cirrocumulus (ripples) | A 2D layer with flow ("curl") noise; the halo is analytic | Low |

All of these are driven by one **SkyState**:
- **Low clouds:** coverage, cloud type, tower height, stratus ceiling.
- **Higher clouds:** altocumulus cover, cirrus cover, cirrus streakiness.
- **Ground and air:** fog density, fog height, haze.
- **Motion and variety:** wind, seed.

The same struct later feeds Android. No genus is hard-coded into the shader; a sky is a SkyState.

## 3. Cumulus refinements (my list; add or strike)
1. **Bases:** flatter, more defined and a little darker, so each cloud sits on a clear level line.
2. **Tops:** crisper cauliflower billows. Right now they read slightly as cotton wool.
3. **Horizon clutter:** too many tiny distant clouds. Thin them so the far sky reads clean, like the weather-app reference.
4. **Composition:** one or two "hero" clouds with real size variety, not an even field.
5. **Shading depth:** deeper self-shadow between billows, blue sky light in the shade, and a warmer, brighter sunlit side.
6. **Silver lining:** brighter, thin rims where a cloud passes near the sun.
7. **Motion:** the billows should slowly change shape (boil), not only slide.
8. **Fine stripes:** clear the faint row stripes still visible in lit areas when zoomed in.

## 4. Body to sky mapping (proposal, depends on D1)
"Weather of the body". Each signal owns one layer of the sky, so the sky never fully contradicts a number on screen.
| Body signal | Sky element | Reads as |
|---|---|---|
| Readiness | The low-cloud regime: clear with fair cumulus (≥80), partly cloudy (65–79), stratocumulus (50–64), low stratus (<50) | Overall state at a glance |
| Sleep (score, minutes against need) | Morning valley fog. Short or broken sleep leaves fog in the hollows at dawn, which thins through the morning | "Still waking up", not a punishment |
| HRV against baseline, overnight stress | High cirrus. Balanced gives a clean high sky; suppressed gives streaks, then a milky veil with a halo | Subtle, for those who look |
| Strain and steps today | Cumulus growth through the day. Activity builds the clouds taller by afternoon | Energy spent, a positive build |
| Skin temp up and RHR up together (possible illness) | Humid haze, softer light, lower visibility | Body under load |
| Likely illness (skin temp and RHR both up) | Light rain under low stratus | The floor of the scale; training load never triggers it |
| Likely illness and readiness under 35 (really bad stats, D2) | Thunderstorm: dense rain, a lightning bolt in the upper sky | The rare worst case |
| Missing data or first run | A neutral fair sky | No implied judgement |

Each state also gets a text equivalent for TalkBack, for example "Clear sky, light morning mist: readiness 86, sleep 6 h 10 m". The brief's rule is that the visual never carries meaning alone.

## 5. Rounds (each ends with a STOP)
- **3.5 · Cumulus refinement:** the section 3 items, then A/B on whichever ones you want to compare.
- **3.6 · Stratus, stratocumulus, fog and light rain:** new presets shown on the board. Fog is checked against the glass contrast (fog lightens the backdrop, so it should help).
- **3.7 · Cirrus family and altocumulus**, including the sun halo.
- **3.8 · Sky lab:** a board with sliders for each body metric that shows the resulting sky live, plus a gallery of real states (good day, short sleep, sick, heavy training).
- **3.9 · Transitions:** how the sky changes when new data syncs or the time of day moves.

**Every round, measured:**
- Glass contrast on every preset, AA at 4.5:1 or above.
- Frame cost.
- A side-by-side with photo references.

## 6. Android path (decided at Lock, noted now)
- **Android 13 and up:** live shader. The 3D noise is packed into a 2D atlas, rendered at reduced resolution and cached between data changes.
- **Android 8 to 12:** pre-rendered frames per (SkyState bucket × time of day), with cheap drift on top.
- **Size estimate:** grows with the number of distinct skies, which is the reason for D3.

## 7. Open decisions
- **D1 · Mapping depth: decided.** Each signal owns a layer (section 4).
- **D2 · Worst sky: decided, revised 2026-11-20.** Light rain is allowed. A thunderstorm is allowed only for really bad stats: likely illness (the D6 rain trigger) *and* readiness under 35. Low readiness alone still never brings rain or a storm. (Threshold proposed in 3.16, open to change.)
- **D3 · Variety: decided.** A new formation each day, seeded by the date and held steady within the day. It evolves with the time of day and with new data.
- **D4 · Explaining: decided.** Tap the sky to get a short note on why it looks that way. TalkBack always has the text.
- **D6 · Rain trigger: decided.** Rain appears only for likely illness: skin temp and resting HR both up against baseline. Low readiness alone never brings rain.
- **D5 · Cumulus refinement (open):** which of the section 3 items matter most, and anything missing.

## 8. The land (terrain lock, 2026-11-22)
Rounds T0–T9 (LOG.md) settled the ground under the sky. One land, no variants left in the code:
- **Open calm loch** (T3 `lochCalm`): wide still water mirroring the sky and the far shore, two long low ranges (the far mountain was removed in M8, 2026-11-23), a shore with sand coves, near grass with reeds at the water.
- **Two maple trees** on the lip, right of the hero number: grown branching, fine leaf clumps (T4), thinning without holes (T7).
- **A flat page under the cards** (T8 A): the land stops at the lip; the cards keep the calm gradient, everything under the glass ≥ L\* 71.
- **What the land shows of the body and the day:** nothing new. Weather (fog banks, rain veil, overcast flattening, snow) and hour (sun side, dusk mirror, night silhouette) reach the land through the same SkyState and palette. The **season** is the land's own signal: grass green → straw (`DRY`), the maples' year (bare, bud, green, yellow → red, bare), all by the day of the year.
- **Data, not branches:** the land is numbers in uniforms (`LAND`, `TK`, `LEAF` in `scene-gl.js`), so it ports to AGSL as data. The shader keeps one call site per heavy function (T9 compile rule in CLAUDE.md).
