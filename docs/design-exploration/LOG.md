# Health++ design exploration — log

Open exploration started 2026-11-17. Instrument (DESIGN.md, R6.2) is the control, not the default winner.
Rules: no edits to android-app/ or DESIGN.md before "lock"; exploration files live here only.

## Round 0 — Discovery (in progress)

### Inputs read
- DESIGN.md (Instrument), design-prototype/DECISIONS.md (v2 prototype: Manrope, 3 sizes, 27-word fold), ui/ code (Theme.kt, DashboardModels.kt, Components.kt, TodayTab.kt, MainDashboardScreen.kt).
- Screenshots (13:33, SM-G990E 1080×2340 @480dpi): `screens/hp-*.png` (today, today-expanded, today-scrolled, trends, sleep, sleep-2, coach, settings) and references `screens/ref-weather-1..3.png` (Samsung Weather, the app the user called "Google weather"). Oura is not in the main profile (likely Secure Folder, which adb can't reach). Old `.gstack/tmp/screens/` shots predate Instrument; ignore them.

### Audit of Instrument (from code; visuals to confirm on device)
Works
- Colour discipline: every colour is a token, contrast + no-purple tests. All text pairs pass AA by a wide margin (TextSecondary on card 7.6:1, coral 6.4:1). — colour carries meaning; WCAG 1.4.3.
- Every number has a personal reference ("↓ 19% vs your normal", normal band). — "compared to what?" (Tufte).
- Progressive disclosure: Daily Score expands to Readiness/Sleep/Strain; reasons sheet behind the verdict. — progressive disclosure.
- 48dp targets; quiet loading and empty states. — Fitts; calm feedback.

Fails
1. No single primary number. Daily Score and Recovery get equal panels and equal ~29sp numbers; the actual answer (verdict line) sits last at body size. — visual hierarchy / inverted pyramid.
2. Type scale has no ratio: 11/13/16/40 (×1.18, ×1.23, ×2.5). ScoreGauge sizes text at size×0.4 → 28.8sp and 22.4sp, off its own scale. Titles, steps line and verdict all render 16 Medium. — modular scale.
3. Figure/ground too weak: card vs ground 1.11:1; level-bar track 1.25:1 and normal-range band 1.64:1 are under the 3:1 non-text minimum, yet the band carries meaning. — gestalt figure/ground; WCAG 1.4.11.
4. Colour meanings collide: AccentAmber #FFB86B (action) vs ScoreAmber #FBBF24 (caution) are near-identical; Strain bar uses the action amber as data; sub-metric bars are always green whatever the value; green also = Light sleep stage. — one colour, one meaning.
5. Steps gauge colours % of daily normal with the score scale, so early morning reads coral/"bad". — data honesty; time-aware context.
6. Four rows of system status (greeting, date, sync, battery, check line) before any health data. — above-the-fold priority.
7. Spacing has no base: 16/14/12/10/8/6/4 mixed. — spacing rhythm.
8. TalkBack: one onClickLabel in all of ui/; expandable cards don't announce expanded/collapsed; chevrons are "›" text glyphs that get read aloud. — WCAG 4.1.2.

### Device check (confirms audit 1, 4, 5, 6)
- Steps at 13:34 show coral "26%" and red "Today 2,464" in Trends: the day isn't over, yet it reads as failure (audit 5).
- Sleep has no number on collapsed Today; it is only inside the expanded Daily Score. Weight is at the bottom of Trends. Both are part of the user's morning routine. (New: IA mismatch with real use.)
- Red is also "late night" in Sleep Trends; green is both a good score and the light sleep stage (audit 4).
- The first health number sits ~750px down, under greeting, sync, battery and the check line (audit 6).

### Interview, part 1 answers (2026-11-17)
- Use: morning = sleep, readiness, morning check, weight (when a scale is near). Day = few-second glances at steps and other data. Under 60 s. First second: "activity level, and where am I today vs my baseline".
- Screen rank: Today > Sleep > Trends > Coach > Settings > Morning check.
- References: Oura (visuals that aren't just numbers, beside real data; open mountain image) and Samsung Weather (sky blue, illustrated scene, openness).
- Feel: Clean, Precise, Open.
- Dark-only acceptable, but the feel should decide the ground colour, derived from the main feel (sky / openness).

### Tensions to resolve
- The user's own anti-slop rule bans blue gradients; a sky is a blue gradient. Proposed: a sky is allowed only as a meaning-bearing ground (it carries state), never as decorative chrome.
- Imagery "like Oura/Weather" must be original (their art is not ours to reuse).

### Interview, part 2 answers (2026-11-17)
1. Sky background allowed: it is the goal.
2. The scene changes with time of day AND readiness; many graphics that look good and carry meaning (Samsung Weather model).
3. Soft, illustrated, weather-app colours, not photos. Claude chooses the method → code-drawn scenes (Compose Canvas / SVG in prototypes): no assets, smooth between states, sharp at any density, and the colour under text is known, so contrast can be checked.
4. Today keeps the same metrics and order all day. Daily Score says it firms up as activity is logged.
5. Oura refs saved: `screens/ref-oura-readiness.webp`, `ref-oura-myhealth.png`, `ref-oura-activity.png`. Liked: the pictures, and data drawn as art (readiness arc over the mountain, My Health arcs from "needs care" to "thriving"), serif headline sentence over the scene, sheet rising from below.

### Brief v1 (sent for approval)
See chat 2026-11-17; recorded in BRIEF.md once approved.
Defaults built into the brief: panels are solid tints sampled from the scene (no blur/frost); a serif display face is a Round 1 axis, not a decision; any font is a dependency to approve.

Brief v1 approved 2026-11-17 → BRIEF.md. Round 0 closed.

## Round 1 — Diverge (in progress)
- Directions defined: ROUND1-directions.md (A–F + Instrument control). Lookups: ui-ux-pro-max colour "Weather App", typography "News Editorial", "Geometric Modern", "Soft Rounded", "Wellness Calm"; chart "Performance vs Target → Gauge / Bullet", "Trend Over Time → Line".
- Figma: file s2d0UL8fEIH34jU2jlmknL, page "R1 · Diverge · 2026-11-17" (2:2). Built: A Open Sky (frame 2:4, tokens 2:80), B Altitude (frame 2:45, tokens 2:114). B mountain is crude; refinement pending.
- Figma Starter-plan MCP call limit hit while building C + D. User chose the HTML board fallback.
- Board: design-prototype/r1-board.html (served by .claude/launch.json "design-board", http://localhost:8765/r1-board.html; `?only=A`…`?only=G` shows one). Reads data.js live; contrast ratios computed live under each phone.
- Board result: all pairs in A–F pass (text ≥4.5, large ≥3, UI ≥3; context-only tracks flagged as such). Only G (Instrument) fails: card vs ground 1.11:1, bar track 1.27:1.
- Fixes after first render: A rows overflowed the nav → 2×2 grid; A "normal" tick label moved off the number. F was a sibling of B (dark ground, two columns, warm button) → F made light (mist ground, ink text, near-ridge blue as action, draw-in motion instead of parallax).
- Figma A/B frames (2:4, 2:45) are earlier drafts; the board is the current Round 1.
- STOP: waiting for the user to pick, kill or mix.

### Round 1 decision (2026-11-17)
- A Open Sky = current winner, "a lot of work to do". Wants to test glass cards (Apple look) instead of no cards.
- B Altitude survives to Round 2.
- D Forecast survives; user likes its cards.
- New mix requested: A's design + D's card layout, in an Apple glass treatment.
- Killed: C Horizon Line, E Daylight Letter, F Strata. G stays as control.
- Tension: brief v1 and the anti-slop list ban glass/blur. The user explicitly asked for it, so it is now allowed for this mix; the contrast rule still holds (tint floor so text passes on the worst scene colour behind it).
- Round 2 setup answers: screens = Today, Sleep, first run. A+D glass replaces plain A. Glass strictness: "try both" (floor and full shown side by side).

## Round 2 — Converge (2026-11-17)
- Board: design-prototype/r2-board.html (http://localhost:8765/r2-board.html; `?only=today|sleep|first`). 4 columns × 3 screens: A+D Glass floor (45% day / 40% night tint), A+D Glass full (22% / 25%), B Altitude, D Forecast.
- A+D Glass: A's sky + sun-path arc + D's unequal tiles as glass (blur 22, sat 1.6, light edge, soft offset shadow). Sleep turns the scene to night with dark glass. Stage colours ordinal (Awake warm → Deep dark blue) and lane position repeats the stage.
- B: Sleep = moonlit summit, hypnogram as a single white step line, no stage colours. First run = empty dashed dial, "The climb starts tonight."
- D: Sleep = night forecast (hour-by-hour depth glyphs, four stage tiles, 7-night outlook). First run = "Day 1", empty forecast strip with dashed markers.
- Contrast (live): everything passes except A+D full glass, ink-2 over the darkest meadow = 4.32:1 (needs 4.5). That is the floor/full trade-off, shown on purpose.
- Fixes after render: A+D "normal" label moved outside the arc; 7-night "usual" label moved left; B Sleep dropped the 7-night bars (overflowed); D Sleep hero 96 → 68 (hit the moon); D first run hero restructured (hit the sun).
- STOP: waiting for the user to pick one direction for Round 3 and the glass level.

### Round 2 decision (2026-11-17)
- User picked A+D Glass, FULL glass (22% day / 25% night). B and D are out.
- Open issue carried in: full glass fails AA for ink-2 over the darkest meadow (4.32:1).

## Round 3 — Refine (A+D Glass, full)
### 3.1 · Keeping full glass readable
- Board: design-prototype/r3-board.html. Today + first run, side by side.
- Option 1: darker ink-2 #28445C → 4.74:1 worst case (ink/ink-2 gap 1.59 → 1.45).
- Option 2: lighter bottom meadow #86B9A0 → 5.04:1 worst case, text unchanged, landscape one step flatter at the bottom.
- Reference: Round 2 full glass at 4.32:1.
- STOP: waiting for the pick. Proposed next decisions, one per round: hero (sun arc vs plain number), tile density, type, accent colour.
- 3.1 decision: Option 2 (lighter bottom meadow #86B9A0; text colours unchanged). Rule carried forward: any scene colour that sits under day glass must be at least as light as #86B9A0.
- User priority (2026-11-17): the illustrations are the centerpiece, like the weather app. They must be crisp, good-looking and give a good feeling. Every later round is judged on the scene first.

### 3.2 · The scene (in progress)
- Board: design-prototype/r3b-board.html, scenes from design-prototype/scene-kit.js (SCENE(subject, time, readiness, {noSun})).
- Scene craft: per-layer gradients with aerial perspective, light rim on every ridge, volumetric clouds (6 puffs + soft base shadow), 7% grain against OLED banding, haze + desaturation for low readiness. Times: dawn / day / dusk / night. Weather: ≥80 clear, 65–79 some cloud, <65 overcast; first run = neutral.
- Today change: the sky's sun and the readiness marker are now one sun, sitting on the arc at your readiness (removed the double sun).
- Option 1 Rolling meadow: hills, tree line, path into the near meadow.
- Option 2 Mountain lake: snow peaks, still lake, reflection, shoreline pines kept to the edges above the cards.
- Contrast: lake mountains lightened (#ADC7DC) so the 12 sp line on them passes (5.3:1); shoreline raised to y 322 so pines never sit under glass. Hills worst case under glass 5.04:1, lake 5.87:1.
- Known limit: the cards cover the lower ~60% of the scene on Today, so most of the illustration shows above y 330. Candidate for a later density round.
- STOP: waiting for the subject pick.
- 3.2 decision: neither landscape good enough; both need far more detail. User wants realistic clouds and a real sky colour like the weather app. Meadow is closer to the vision but "not even half baked".

### 3.3 · Meadow rebuilt as a GPU shader (2026-11-17)
- Technique change: SVG primitives can't make realistic clouds. New design-prototype/scene-gl.js = one WebGL fragment shader (GLSL ES 1.0, ports to Android AGSL RuntimeShader, API 33+; needs a static fallback image below 13).
- Sky sampled from screens/ref-weather-1.png (#B5DEFA top, #7BC4F5 mid, #5CB8DD low); clear zenith #3E9BEA.
- Clouds: big fbm shapes eroded into billows, flat bases, lit toward the sun (3-tap self-shadow), silver rim, haze toward the horizon, high cirrus. Land: 5 layers with aerial perspective and sun-facing slope light, tree line, scattered sphere-lit trees with cast shadows, path, grass foreground with flowers, drifting cloud shadows, dithering. Night: stars + crescent moon.
- Bugs found and fixed while building: reversed smoothstep edges are undefined in GLSL (returned 0, no clouds); noise median measured at .40–.46, not .5 (thresholds recalibrated); scene now draws on mount, not on the first animation frame.
- Contrast is now MEASURED, not estimated: design-prototype/glass-check.js blurs the rendered canvas at 22 px like the glass, adds the 22% white tint, and finds the worst point for ink-2 in every card. Day at readiness 86 / 70 / 52 / 40: lowest 4.91:1 (sleep tile). A large near tree first gave 4.31:1; trees lightened to fix it.
- Board: design-prototype/r3c-board.html (Today with full glass + scene only + the weather reference side by side; time and readiness controls; state strip). Single-scene view: design-prototype/scene.html?time=day|dawn|dusk|night&r=86&anim=0.
- Honest gap: still below the weather app's hand-made art (cloud volume, foreground detail, characterful elements). Open question for the user: keep pushing the shader, or use painted illustration assets per time of day with shader effects on top.
- STOP: waiting for feedback.

## 3.4 · Volumetric sky (2026-11-17)
- Feedback on 3.3: no ceiling on clouds (the band cap felt artificial); use Perlin noise for formations; high fidelity, real-looking but clean.
- Path chosen: physically based volumetric clouds in the shader (the method used in current game skies: Schneider/Guerrilla "Nubis"), not painted assets. Reason: it reaches photographic cloud form and light while staying fully data-driven (readiness = weather, time = light) and sharp at any size.
- Built (design-prototype/scene-gl.js, now WebGL2):
  - One-time GPU noise generation into tileable 16-bit float textures: 128³ Perlin-Worley shape volume, 64³ Worley billow volume, 512² Perlin weather map (coverage, tower height, cirrus). 8-bit textures produced wall-like cloud sides once coverage was thresholded, hence 16-bit.
  - Ray march from a ground-level camera through a cloud layer from 1.5 km to 8.5 km; no screen cap, near towers leave the top edge. Open sky overhead, clouds start ~4 km out.
  - Lighting: Beer-Lambert toward the key light, two-lobe Henyey-Greenstein phase (capped), 3-octave multiple-scattering approximation, powder term, sky/ground ambient, aerial perspective (26 km).
  - Sky: elevation gradient from the sampled weather-app colours + Mie halo; all in linear light, soft highlight shoulder, sRGB out.
  - Progressive accumulation: 16 jittered sub-pixel frames averaged (removes ray noise and grazing-angle hair, anti-aliases land). Timer fallback when the pane throttles frames. ~39 ms per frame at 824×1830 on this PC.
  - Land: far range now has broad sun-facing slope light and soft mottling; cloud shadows on the ground are sampled from the volume.
- Measured glass contrast (day, Today, 22% white, ink #2E4A62): worst 4.92:1 (sleep tile) across readiness 86/70/52/40/first run. AA holds.
- Android note: AGSL has no 3D textures. Options for the app: pack volumes into a 2D atlas and march at reduced resolution, or bake frames per state on a desktop GPU and drift them. Decide at Lock.
- Open: meadow is now the weakest layer next to the clouds (flat bands, simple trees); low-readiness weather reads only slightly greyer.
- STOP: waiting for feedback.

## 3.5 plan · Sky types and body mapping (2026-11-17)
- User: tune cumulus first; add cirrus, stratus and fog; a different sky each time, driven by body state. Plan in PLAN-sky.md.
- Checked the app data: readiness, recovery, sleep (score, minutes, deep, REM, awake, efficiency), HRV, RHR, skin-temp change, SpO2, stress, strain, steps, calories, weight. minSdk 26, so the app needs a non-AGSL fallback.
- Decided: each signal owns a sky layer (D1); light rain allowed, no storms (D2); a new formation each day, steady within it (D3); tap the sky to explain, TalkBack text always (D4).
- Open: D5 cumulus priorities, D6 rain trigger.
- STOP: waiting for D5 and D6.
- D6 decided: rain only for likely illness (skin temp and RHR both up). D5 defaults to all eight cumulus items in order.

## 3.5 · Cumulus refinement (2026-11-17)
- Board: design-prototype/r3d-board.html (A = Round 3.4 kept as scene-gl-34.js; B = refined, today's and tomorrow's formation; time-of-day buttons; "Another day").
- Changes in scene-gl.js:
  1. Bases: sharp level base (12% of the old ramp), lighter erosion at the base, base curls up at cloud edges (no flat skirts or pancake shelves; tiny clouds that would be thin plates are dropped).
  2. Crowns: erosion now cuts between Worley cells (round cauliflower lobes instead of pits), crown height varies with the shape noise (no flat plateaus), more weight on smaller billow octaves, extra fine billows on near clouds.
  3. Horizon: coverage thins 75% from 16 to 40 km; small-fragment threshold raised.
  4. Hero clouds: one or two large cumulus per day, seeded by the date, placed either side of the sun in the mid distance.
  5. Shading: sky occlusion in the creases between billows, lower blue ambient, warmer sun (#FFF9EE).
  6. Silver lining: forward-scattering term on thin edges near the sun.
  7. Motion: shapes rise slowly, billow detail churns.
  8. Stripes: step length varies per frame; 24 frames averaged.
  - Light now comes from a point placed over the cloud field at the sun's position, so clouds either side of the sun are lit on the side facing it.
  - Day seed (D3): weather map offset and hero placement come from the date; the board shows two consecutive days.
- Cloud shadows on the meadow now actually appear (sampled from the volume). At full strength they pushed some card spots under AA (4.29:1 at readiness 52); softened on the near layers.
- Measured glass contrast after softening: worst 4.66:1 over 10 consecutive days × readiness 86/70/52/40 (40 skies). AA holds.
- Checked dawn, dusk (warm-lit bellies) and night (moonlit) on the board: no errors.
- A/B for the user: A = 3.4 (busy cloud field) vs B = 3.5 (hero clouds, clean horizon).
- STOP: waiting for feedback.
- 3.5 decision: B (hero clouds, clean horizon) wins over A. Cumulus locked as the base for later rounds; scene-gl-34.js kept only for comparison.

## 3.6 · Stratus, stratocumulus, fog and light rain (2026-11-17)
- Board: design-prototype/r3e-board.html (7 presets, time-of-day buttons). scene.html also takes ?sky=<preset>.
- SkyState (scene-gl.js): cover, tall, grey, haze, sheet, sType, sb/st (sheet base/top km), fog, fogH, rain. Presets: fair, partly, stratocumulus, stratus, valleyFog, mist, rain.
- Sheet layer: Perlin-Worley cells flattened into a layer with an undulating base and top; sType 0..1 runs from stratocumulus (cells, blue gaps) to stratus (unbroken). A deep-diffusion light term keeps thick sheets grey-white, not black.
- Overcast flattens the land light: no sun-facing slope light, softer tree crowns, no cast tree shadows.
- Valley fog pools in the hollow of each land layer (just above the next ridge), drifts in banks, sits deeper far away, glows toward the sun; fog also veils the horizon.
- Light rain: three depths of thin slanted streaks, a veil over the distance, under low stratus.
- Readiness now picks the regime: 80+ fair cumulus, 65–79 partly cloudy, 50–64 stratocumulus, under 50 low stratus.
- Measured glass contrast, day, 3 days per preset: fair 4.72, partly 4.70, stratocumulus 4.93, stratus 4.82, valley fog 5.78, mist 6.09, rain 5.61. All AA; fog and rain lighten the backdrop and help.
- Note: the board runs 7 live GPU scenes; a second browser tab with it open was dropped by the pane. Fine for review, but the sky lab (3.8) should render thumbnails one at a time.
- STOP: waiting for feedback.

## 3.7 · Cirrus as the main cloud, sky-first camera (2026-11-17)
- User: cirrus was missing and should be the main cloud; camera POV like the weather app: lower horizon, clouds the large focus.
- Camera: horizon from 40% to 58% down the screen; the land is drawn in its own frame below it (compressed, as when looking up); palette suns remapped. The sky now fills the upper 58% of every screen, behind the hero and the top card row.
- Cirrus layer (scene-gl.js `cirrus()`): ice cloud on a plane at 9.5 km, perspective-correct. One upper-wind direction per day (turns day to day), gentle bend, long anisotropic fibres from the Perlin weather map, wisps fading along their length, hooked heads (uncinus), milky veil with a 22° halo (cirrostratus). Forward-scattering light: bright near the sun, pink at dusk.
  - Bug found and fixed: rotating coordinates by an angle that varies across the sky smeared the cirrus into marbling far from the origin; replaced by one wind direction plus an additive bend.
- Default sky is now cirrus with a few fair-weather cumulus; hero cumulus appear only when the sky has cumulus.
- Overcast sheet undersides brightened (real overcast is a bright even grey); without it low stratus measured 4.35:1 and rain 4.03:1 behind the top card row under the new camera.
- Measured glass contrast (day, 3 days each): readiness 86 5.12, 70 4.77, 55 5.05, 40 4.94; cirrostratus 5.11, dense cirrus 5.11, valley fog 5.49, rain 5.31. All AA.
- Boards: r3f-board.html (8 sky types with proposed body states), r3c-board.html shows Today on the new camera. Boards now load the shader cache-busted.
- Open: cirrus as "main" changes D1 (HRV was to own cirrus). Proposal on the board: cirrus is always the base sky and its form follows HRV and overnight stress; readiness adds low clouds beneath.
- STOP: waiting for feedback.

## 3.8 · Painted sky, after the weather app (2026-11-17)
- User asked for a version of the clouds that looks like the weather app's. Built as a second, switchable style (`style: 1` in SkyState; presets painted / paintedClear / paintedCloudy), matching the reference's style, not tracing its artwork.
- Reference read (ref-weather-1..3): a flat, soft cloud layer seen almost straight up; airbrushed white masses, pale-blue shade, misty edges, saturated blue gaps, fine grain, no sun disc.
- Built `painted()` in scene-gl.js: screen-space layer from the same Perlin-Worley billow textures as the 3D clouds (value-noise fbm swirled into marbling; the billow textures read as cotton), lit side from a smooth offset sample toward the top left, misty light halo at the edges (a darkened edge read as an outline), cover set by `paintCov` (body-data ready). Haze takes over just above the horizon; land unchanged.
- Board: design-prototype/r3g-board.html: reference screenshot, painted at three cover levels, volumetric 3.7 for comparison.
- Measured glass contrast (day, 3 days each): painted 4.72, few clouds 4.66, cloudy 5.03. All AA.
- Open: painted "mostly cloudy" still shows more blue than the reference; dawn and dusk in the painted style not tuned yet.
- STOP: waiting for feedback.

## 3.9 · Game technique 1: colour grade by hour, bloom, light shafts (2026-11-17)
- User: make the game techniques one by one, right, no shortcuts. Started with grading + sun glow.
- Bugs found and fixed on the way:
  - Since 3.7 the sun's uniforms (uSun, uSunR, uShowSun) sat inside a JS comment, so the sun disc and halo were never drawn.
  - New render targets were created on texture unit 2, which replaced the weather map: cirrus and weather-driven cumulus vanished. Targets now bind on unit 6.
  - A day's seed could leave the sky without cirrus. Cirrus is the main cloud, so the band is now anchored so mid-sky always carries some.
- Pipeline (scene-gl.js): scene accumulates into a 16-bit float buffer (linear light + an open-sky mask in alpha) instead of 8-bit blending; post pass: soft-threshold bright pass, two Gaussian-blurred bloom levels (1/4 and 1/16 res), light shafts (48-step radial march toward the sun over the open-sky mask, off under closed sheets and in the painted style), then per-hour grade (white balance, shadow tint, saturation, gentle S-curve), highlight shoulder, sRGB, dither. Falls back to the old direct path without float render targets.
- Hour keyframes: palettes blend in linear light (night 0–5, dawn 6.5, day 9–16.5, dusk 19, night 21+); the sun follows a path from left at dawn to right at dusk, sets, and the moon fades in. `hour` option; named times map to 6.5 / 13 / 19 / 23.
- Board: r3h-board.html (hour and readiness sliders, volumetric/painted, before/after, live contrast). scene.html takes ?h= and ?post=0.
- Measured: glass contrast with the post pass on, 25 cases (08, 10, 13, 15, 17 h × readiness 86/70/55/40 + painted): worst 4.77:1 (08 h, painted, steps tile). All AA. Frame cost incl. post ~25 ms at 618×1373 on this PC.
- Not checked: dark glass for dawn/dusk/night (not designed yet).
- STOP: waiting for feedback.

## 3.10 · Game technique 2: depth layers with tilt parallax (2026-11-17)
- Built the cached-layer way, not by re-rendering per tilt: the scene renders into four depth slices, each overscanned 28 px past the screen edge and accumulated (24 jittered frames) on its own:
  0 sky, sun, stars, cirrus, clouds farther than 14 km, far ridge (opaque; alpha = open-sky mask for light shafts)
  1 clouds nearer than 14 km (premultiplied; may now sit in front of the far ridge)
  2 middle land: ridges 1-2 and their trees (premultiplied, soft edges)
  3 near land: ridge 3, path, near trees, foreground meadow
- A compose pass slides each slice by its depth (3 / 9 / 14 / 22 CSS px at full tilt; vertical 60%), stacks them, adds rain, then the 3.9 post pass runs. Tilting only re-composites: 1.3 ms per frame at 618×1373 on this PC (re-render of the slices 15 ms).
- Tilt input: device orientation (re-centred over about 3 s, so any holding angle is neutral; 12° reaches full parallax); pointer position as the desktop stand-in; a critically damped spring smooths motion; off with reduced motion. Handle gets setTilt(x, y) and bench().
- Verified per layer by cross-correlating pixel rows at tilt −1 vs +1: sky 7 px, near clouds 19, far ridge 6, middle land 26, near land 44 (targets 6 / 18 / 6 / 28 / 44).
- Fixed in passing: buffers reallocating on resize now restart the average.
- Dropped: the 8-bit direct path; without float render targets the scene shows a message (shortcut noted in code).
- Board: r3c-board.html (Today) now tilts: move the mouse over the Today phone, or "Play a tilt sweep".
- Measured glass contrast on the sliced pipeline, 33 cases (08/10/13/15/17 h × readiness 86/70/55/40 + painted, and 13 h at readiness 86 and 40 at all four extreme tilts): worst 4.77:1. All AA.
- Not tested on a real phone yet (needs the board opened in Chrome on the device).
- STOP: waiting for feedback.

## 3.11 · Phone test of tilt, then game technique 3: wind in the meadow, birds (2026-11-17)
- Phone test (Galaxy S21, Mali-G78, Chrome, via adb + DevTools; new test page design-prototype/phone.html: full-screen scene, tilt on, readout of frames, GPU cost, tilt and orientation):
  - Tilt works on the device: real deviceorientation events reach the scene, 12° of roll gives full parallax, re-centring holds. Driven through the DevTools orientation override: tilt −1 / 0 / +1 at 116–119 fps (the screen's 120 Hz).
  - Found: a full slice render costs ~140–240 ms on this GPU (PC: ~10–20 ms). Bug: the drift interval used the CPU-side time (~1 ms), so the phone re-rendered every 120 ms and the page fell to 1–4 fps, tilt included.
  - Fixed: the scene now times one frame on the GPU (frame 4; frames 1–3 carry driver warm-up, which read 103 ms on the PC against a true 10 ms). Cloud drift only runs where a slice render is under 60 ms; on the phone the clouds hold still once converged. Technique 5 (pre-rendered drift) is the real fix.
  - Still slow on the phone: the first ~5 s while the 24 frames converge.
- Technique 3, built on the cached layers so the phone never re-renders the scene for it:
  - The land slices write a second render target (MRT): what the wind moves, r = grass (not the path), g = tree crowns, premultiplied and averaged with the colour. Fog-veiled distance is masked out.
  - The compose pass, per frame: gust patches (two octaves of noise on the ground plane, long across the wind, reshaping as they travel) move with the low clouds' wind (to the left, slightly toward the eye). Grass in a gust shows its paler side (+30% plus a little sky); fine ripples run inside it; crowns silver as leaves turn. Noise is filtered by its pixel footprint (computed analytically: derivatives are undefined in branchy code) so the receding field does not shimmer.
  - Wind follows the sky: fair .45, partly .60, stratocumulus .65, stratus .50, rain .90, valley fog .05, mist .15. Even a light breeze shows its gusts.
  - Birds: every 90 s a loose group of 2–4 crosses in 28 s, flapping a few beats then gliding. Day only, none in rain, fewer in fog and grey. Their lane is the strip between the header and the top of the readiness arc: never behind the hero number (first try crossed right behind "86").
  - Wind and birds run at 30 fps when nothing else moves (battery); tilt still runs at full rate. Off with reduced motion.
- Measured (PC): compose + post 0.6 ms per frame. Glass contrast on Today (readiness 86, day): wind 0 → 5.26, .45 → 5.33, 1 → 5.45, each the worst of 6 moments. The wind only brightens (provably: (1+.3k)(1−.08k) ≥ 1), so contrast cannot drop.
- Board: r3c-board.html (Today) has "Show the gusts" (magenta gust map), "Birds now" and a wind slider. phone.html for the device.
- Phone test of technique 3 (Galaxy S21): runs, no GL errors. Wind animates the land only (frame difference over 1.5 s: land bands 4.7–7.5, sky 0.7 = dither). Gust map follows the field, path excluded; birds fly in their lane. Idle page 59 fps with wind and birds on; clouds hold still (slice render timed at 323 ms, over the 60 ms drift limit).
- Cost on the phone, A/B against the 3.10 build, alternating to control for heat: compose + post 20.6–21.4 ms (3.10) vs 22.1 ms (3.11), so wind + birds add ~1–1.5 ms. Both builds were slower than the cool-phone 11 ms measured at the start of this round: the phone reached thermal status 2 (moderate) after ~20 min of convergence bursts. With tilt the page ran 42–46 fps hot, against 117–119 cool.
- Open: heat. Each convergence is 24 slice renders of ~150–320 ms on this GPU. Technique 5 (pre-render) should take that off the phone, and the 30 fps ambient loop could drop to 20 fps.
- phone.html takes ?src=<file> to load another build for A/B timing.

## 3.12 · Game technique 5: bake the sky, keep the motion live (2026-11-18)
- Goal: a phone gets the PC's sky without rendering it. The PC renders each keyframe once with the full shader (24 frames) and saves the four depth layers; the phone loads them and runs only the cheap passes (compose: tilt, wind, birds, sun; post: bloom, shafts, grade).
- Format: per layer a colour PNG (RGBM in sqrt space, 0..8 linear; sqrt gives the dark night sky more steps) and an aux PNG (coverage or open-sky mask, wind material). Own PNG writer (a canvas would premultiply alpha and wreck RGBM); load with createImageBitmap(premultiplyAlpha none, colorSpaceConversion none). Round trip verified byte-exact on random RGBA incl. transparent pixels.
- Tools: design-prototype/bake.html renders and saves through bake-server.py (PUT into baked/ only, 127.0.0.1; launch config design-bake, port 8768). ab.html compares two builds or a build against a bake (PSNR, per tenth of the screen). phone.html takes ?baked=<set>.
- Frame: 412×915 at 2x; the phone shows it cover-fit (canvas px → frame px in compose). The near-cloud layer is baked 72 CSS px wider: baked clouds drift by sliding into that room at 0.8 px/s, easing to a stop (~100 s; sessions are shorter).
- Moved from the baked sky to the live pass, because they switch with the hour and a blend of baked hours cannot follow them: the sun/moon disc, the sun's halo, stars (now soft points that twinkle, were 6 px squares).
- Keyframes per weather: 05:00, 05:45, 06:30, 07:30, 09:00, 11:00, 13:00, 15:00, 16:30, 17:45, 19:00, 20:00, 21:00 (21:00 holds the night to 05:00). Densest where the light changes fastest.
- Bugs found by measuring baked vs live (PSNR):
  - Hero clouds chose their side from the sun, so they jumped across the sky when the sun passed the middle (live too), and keyframes either side were different skies (11:00 read 29 dB). Now chosen per day.
  - Blend weight was linear in the clock, but palettes move on a smoothstep: at 05:23 the light is 16% of the way to 05:45, not 50% (05:23 read 22.8 dB). Now the weight projects this hour's palette and sun onto the line between the two keyframes' (least squares). 05:23: 22.8 → 36.3 dB, 20:30: 26.7 → 39.7.
  - Near/far cloud split moved from 14 to 30 km: the nearer hero cloud spans ~5–17 km and was cut in two; drifting or tilting the near layer would separate the halves. (Faint grey wisps beside the big right cloud are in the live render too: a cloud-shape matter, not a tear.)
- Fidelity, baked vs live (40+ dB reads identical, 30–40 close): keyframes 48 dB; day blends 38.9–48.3; twilight blends 34.4–39.7 (worst 06:08, 34.4; visually the same side by side).
- Phone (Galaxy S21, hot: thermal status 2, skin ~40 °C, charging):
  - Time to a full-quality picture: live ~9.0 s of full GPU load (24 renders × ~410 ms), baked ~0.45 s (fetch, decode, upload, blend; blend pass 30 ms).
  - Per frame both paths cost the same, and it was all post: 24 ms, of which light shafts ~13 ms (48-step march on every full-resolution pixel). Shafts now march at quarter resolution and are blurred: PSNR vs full resolution 48.8–48.9 dB (= dither noise), post 19.9 → 7.0 ms, frame 22.6 → 11.8 ms.
  - Tilt on the baked page 59–61 fps hot (was 42–46). Over 3 min of baked playback with wind and drift, skin went 40.5 → ~39 °C (it had been climbing during live testing).
  - Clouds drift on the phone now (live mode had to hold them still).
- Card contrast (Today, day, live path with the moved halo, stars and shafts): readiness 86 5.19, 70 5.04, 55 4.91, 40 5.26. All AA.
- Asset budget (open): 2.5–3.6 MB per keyframe as lossless PNG, ~33 MB per weather per day at 13 keyframes. Lossy WebP of a tone-curved image only reaches 1.4 MB per keyframe (sky shrinks, grass grows). Shipping many baked days is too heavy; options are a small set of formations, a daily download, or baking on the phone once per day while charging and caching it.
- Not checked: dark glass at dawn/dusk/night (not designed yet); Android 8–12 natively (the baked path needs only GLES 3.0 passes, no AGSL, but it ran here in Chrome).

## 3.13 · Bake on the phone (2026-11-18)
- User chose: the phone bakes its own sky (no download). Design: a background worker with its own GPU context bakes today's keyframes while charging, stores them on the device, and later opens play them.
- Built:
  - design-prototype/sky-baker.js: a Web Worker (the browser stand-in for the app's background job) with WebGL2 on an OffscreenCanvas. Loads scene-gl.js (now worker-safe), renders each keyframe with renderSliced, packs PNGs (same format as the PC bake), stores them in Cache Storage ('sky-v1', sky-cache/<seed>-<regime>/) and updates the manifest after every keyframe, so whatever is ready can play.
  - renderSliced: converges the 24 frames in horizontal bands, each sized to ~8 ms of GPU (measured per band, adapted), with a 16 ms pause between bands, so the visible scene keeps the GPU most of the time.
  - Order: the current hour's two keyframes first, then the later hours; hours already past are skipped (not needed again today). Only today's regime; older days are deleted from the cache.
  - phone.html?device=1: plays the device cache when the current hour's two keyframes are there, else renders live; starts the baker when charging (Battery API; ?force=1 to bake without), after the live picture converges, or at once when the screen is off. HUD shows mode, progress and storage.
  - scene-gl: KEYFRAMES and bracketHours shared (bake.html uses them), cache-first loading, manual mode for the baker.
- PC: 7 keyframes (13:00 to 21:00) baked in 0.5 min in the background while the page held 144 fps; per keyframe ~2.5 s render + 1.4 s PNG encode (in the worker). Reopen: plays from the device cache in 170 ms, baker not started (nothing missing). Device bakes vs live: 13:00 47.7 dB, 14:00 48.1, 18:23 36.9 (same as the PC bakes).
- Phone, screen off: the worker keeps baking but Chrome throttles and then freezes hidden pages (4 of 24 frames in 80 s, then stalled). In the browser the screen-off case cannot stand in for the Android background job (WorkManager with an offscreen GLES context is not throttled this way); the visible case is the one to measure here.
- Noticed: on the PC's USB port the phone drains while charging under these loads (25% → 17% over the session, average current negative).
- Phone, screen on (Galaxy S21, battery 19%, on the PC's USB, skin 35.7 °C at start), today's 7 remaining keyframes from 13:00:
  - Per keyframe: 43–60 s wall clock, 13.7–18.8 s of GPU, PNG encode 4.1–5.7 s in the worker, 2.65 MB.
  - The visible scene held 44–60 fps while baking (mostly 53–60; it holds ~60 without the baker).
  - Skin 35.7 → 40.6 °C over 4 min of baking, 42.0 °C a minute later. Stopped after 4 keyframes (13:00 to 17:45) for battery and heat.
  - Reopen at 14:00: played the layers baked on the phone (4/13 hours cached), ready in 1.1 s (cache read + decode of two keyframes; the PC bake over the network read 0.43 s).
- Takeaways: baking on the phone works and keeps the screen smooth, but costs ~16 s of GPU per keyframe, ~2 min of GPU for a full day of one weather, and warms the phone by ~5 °C while it runs. In the app it belongs at night on the charger with the screen off (WorkManager: charging + idle), where the browser could not test it. Today's regime is only known after the morning check, so a night bake needs all four regimes (~8 min of GPU, ~130 MB) or a guess plus the live render as fallback.
- Not checked: battery drain of a night bake on a wall charger; Android-native background baking.

## 3.14 · Clouds, more natural (2026-11-18)
- User: the clouds still don't look natural enough. Looked at full-resolution captures (new `design-prototype/look.js`: load phone.html from bake-server, `LOOK.run(prefix)` saves partly cloudy 13:00 with two close-ups, fair 17:45 and stratocumulus 13:00 to `baked/_look/`).
- What read as fake, and the cause found for each:
  1. Every billow had its own round highlight, like bubble wrap (worst at dusk). The clouds were lit by a point light placed just above the field (3.5), so each lobe was lit from a different angle. Now one directional key light (the palette's key), as the sun at infinity.
  2. Lobes were smooth balloons. The detail noise ran 325–1300 m, the same scale as the shapes, so nothing smaller existed. Detail now 140–800 m; silhouettes break up into smaller billows.
  3. Grainy, stippled patches in thin cloud bases. Ray-march sampling noise left after the 24-frame average (it shrank with smaller steps). Steps inside cloud are now 0.55× (empty air 1.5×, was 1.3×).
  4. Thin stalks rising off cloud rims. Crown height followed sqrt(coverage), so faint rims grew tall. Now coverage^0.75 (linear made small clouds into flat lenses).
  5. Cirrus looked like rippled, marbled paint, and blew out into a white blot round the sun. Every fibre zigzagged in step because the flow bend, warp and hooks used noise channels with fine octaves. They now use the smooth 4-octave channel only. Fibres also carry more fine striation (a fourth, finer octave), the ice is thinner (opacity cap 0.85, 0.70×) and forward glare near the sun is capped at half.
- Tried and dropped: flat cumulus bases (they came back as the dark pancake shelves 3.5 removed); shorter light march with detail (blew everything to white); denser edges (clouds broke into dark grey lumps); finer stratocumulus detail (no visible change).
- Cost (PC, full frame): 8.2–9.2 → 9.1–9.9 ms. Glass contrast at readiness 86/70/55/40: 5.19 / 5.03 / 4.91 / 5.26 (unchanged, AA).
- Baked skies: Cache Storage renamed `sky-v2` (phone.html deletes older `sky-*` caches), so phones drop bakes made by the old shader; `cached()` now reads only the current cache. PC set `baked/today` re-baked (14 keyframes, 37.6 MB); baked vs live 48.3 dB at 13:00 and 14:00, 42.3 at 18:22.
- Fixed in passing: bake.html had its keyframe list commented out by a misplaced comment (syntax error; the page baked nothing).
- Not done: phone check (phone not connected). Stratocumulus still reads as smeared blobs with bright specks; needs its own pass.

## 3.15 · Samsung-reference refinement pass (2026-11-19)
- Board: `tools/visual-loop/out/board-3.15.png` (and `.html`): Today, first run, then every state as ours | reference. Generated by `node tools/visual-loop/board315.js` into the gitignored `out/`, so the reference screenshots never reach a commit. Live Today + first run: `r3c-board.html`.
- Baseline first (`out/baseline/`, 3.14 build kept as `design-prototype/scene-gl-314.js`; `scene.html?src=` loads either). Baseline critique: day skies 11–27 L* too pale (cirrus sheet and a wide Mie glow whitened the upper sky), clouds a field of small hard puffs, sun a large white blot, night a grey slate (not ultramarine), dusk orange-peach (reference: lavender with a white sun), rain and storm neutral grey, no snow or thunder, horizon at 58% with a deep meadow.

### What changed
1. **Horizon 37%.** `HOR = .37` in the scene shader and in COMPOSE. `FOC = .64` (= .37/.58) keeps the sky's vertical field of view, so cloud tuning carried over. The old five-layer meadow, trees and path are gone. New land, all code-drawn: two low ranges of pale blue hills on the horizon (sun-facing slope light, haze thickening toward the base, snow on the crests); a lake strip that mirrors the sky and the hills (Fresnel, ripples that fade out toward the horizon so they don't alias, long light streaks, a glittering sun or moon path); a rolling grassy lip at ~40% with two parallax rows of tufts, flowers on short stems, flowering shrubs and warm sandstone stones; under it the page meadow runs green → teal → blue (`pg` per palette, weather-adjusted), so the glass sits on a calm gradient. Slices: 0 = sky, far clouds, hills, lake; 1 = near clouds; 2 = the lip's back row; 3 = turf, stones, front tufts, flowers, page. Wind still moves the lip only. Sun path, moon, birds' lane (now .285–.315, under the hero), star fade and the fog/rain veil were all moved to the new horizon.
2. **Hero.** The readiness number is 89 px Roboto Light (−3.5% tracking) at the top left (y .12–.23), then the word (23 px), then one detail line (15 px). Arc, normal tick and the sun-on-the-arc link (`SUN_AT`/`sunAt`) are removed; the sun is weather only. First run uses the same slot: "Day 1", "Learning your normal", "Your first readiness score arrives tomorrow morning." Cards start on the lip at 384 px (42%), with heights unchanged; the last card now runs under the nav (the page scrolls). Glass unchanged (22% white, blur 22, sat 1.6, light edge, shadow). New rule, measured: **text sitting straight on the sky uses ink #0E2A43, never ink 2**, because ink 2 on the new azure is ~3.8:1. The header's second line now uses ink as well.
3. **Sky ramps.** New hour palettes. Day: azure zenith #4F9EEE → #7DBBF3 → #ACD7F7 haze, sat 1.08. Dusk: lavender (#8C86D6 / #9D8DDA / #C6A0E4) with a white sun (#FFF6F2) and no orange grade. Night: ultramarine (#0F2668 / #16357F / #2457AE), brighter toward the horizon. The wide Mie lobe is cut from .012 to .004. Weather palettes (`pal`, `palK` in SkyState, blended over the hour's palette and only 25% at night): overcast blue-grey, rain violet-grey with a lilac horizon (#B39CD2), storm blue-grey, snow icy. Grey weather leans cool (×(.95, .96, 1.06)) instead of going neutral.
4. **Clouds: a blend, decided on evidence.** Side by side (`out/s4/`): the painted style matched the reference's softness and composition but had no form or light; the volumetric had form and light but read as many small hard puffs plus full-sky streaks. Kept the volumetric pipeline (light, bakes, slices, tilt, AGSL path) and re-tuned it toward the painted look:
   - coverage cells 1.6× larger with a narrower threshold, so there are fewer fragments;
   - the open-sky zone starts at 2.5 km, so the first hero mass sits near (6–8.5 km) and runs off the top edge, always on the left behind the readiness number (dark ink on white cloud), with its size following low-cloud cover;
   - softer density ramp (×1.15, was ×1.6) and gentler erosion, for misty edges;
   - high-key ambient: low self-shadow contrast, pale blue-grey shade;
   - cirrus thinner (opacity cap .55, was .85), finer and sparser; fair-day cover .50 (was .66).
5. **Sun optics (FINAL pass, linear light).** The disc is now ~7% of the screen width (sunR .017, was 13%). Ten thin diffraction spikes (with ten faint short ones between), three faint tinted lens ghosts on the line through the screen centre, and a silver rim on cloud edges near a hidden sun. All are scaled by the sun's measured visibility (9 taps of the open-sky mask), off under a closed sheet, and faint for the moon. Shafts are reduced (day .18).
6. **New states.** `snow`: three flake depths, near ones large and soft (depth of field), far ones 1–2 px and sharp, slow sway and drift; snowy lip, hills and page. `thunder`: a review visual only (D2 is unchanged), with denser streaks and a branching jagged bolt in the upper sky (it shows on still frames; live, it flashes every ~9 s). Rain streaks: ~10° slant (was ~6°), ~1 px wide, three depths at ~14–32% opacity, near ones longer and brighter. `shoot.js` maps thunder → `sky=thunder&h=16` and snow → `sky=snow&h=11`.
- Bake cache renamed `sky-v3` (the look changed). PC bakes in `baked/today` are 3.14 and stale; no re-bake until the look is approved.

### Measurements
- Sky ramps, right edge, ours vs reference, dL* (top / mid): sunny +27/+13 → **+13/+7**; partly +18/+12 → **+3/+8**; cloudy −13/−9 → **−6/−2**; rain +13/+12 → **+9/+9**; thunder +6/+5 → **−5/−1**; snow +2/+9 → **+4/+12**; dusk 0/+11 → **+4/+12**, and lavender now instead of peach; night +11/+6 → **+12/+12**, ultramarine instead of slate. The "near horizon" spot hits the reference's taller hills in cloudy, rain, thunder and fog, so it is not a like-for-like sky sample there.
- Glass contrast, AA 4.5 (`tools/visual-loop/contrast.js`, real render, blur 22, 22% white, ink 2). Readiness 86 / 70 / 55 / 40 at 13:00: 5.30 / 5.28 / 5.25 / 5.25. 08:00: 5.36. 17:00: 5.11. Partly 5.28, stratus 5.38, rain 5.12, thunder 4.89, snow 5.74, valley fog 5.35, painted 5.24, first run 5.29. At all four extreme tilts the worst was thunder 4.47 (the dark storm turf slid into the first card). Storm turf lightened: **worst now 4.70** (thunder, tilt down); rain 5.02.
- Text on the sky, ink, no glass (needs 4.5; the number needs 3): header 4.92–8.67, hero number 4.74–10.44, word + detail 4.91–9.8. Worst in every case is thunder.
- GPU cost on this PC (RTX 4060, headless 412×915 @1x, alternating builds twice): full slice render fair 2.3–2.5 ms (3.14: 2.9), partly 3.5–4.6 (3.4–4.0), rain 3.6–4.2 (4.0–4.2), snow 3.3–4.3, thunder 4.8–5.2. Compose + post 0.13–0.19 ms (3.14: 0.13–0.15). The bolt and the rim are culled to their regions.
- Phone risk, not measured (phone not connected): the per-frame passes gain the sun optics (~16 taps per pixel), three snow layers and a fourth rain layer. On the S21 the 3.12 post pass was ~7 ms, so expect +1–2 ms in snow and storm. The near hero mass makes partly slices ~+1 ms on the PC; slices are baked, so this costs bake time only.
- Cold shader compile is ~27–30 s in headless Chromium with D3D11, the same for 3.14 (27 s). It is not a regression, but worth knowing for the phone's first live render.

### Tools added (tools/visual-loop)
- `sample.js`: hex ramps, ours vs reference, with dL*.
- `crop.js`: close-ups.
- `contrast.js`: glass and sky-text contrast; `cost` adds timings.
- `bench.js`: A/B GPU cost.
- `board.js`: element screenshot of a board, with web fonts stubbed when offline.
- `board315.js`: this board.
- `scene.html` exposes `window.S` and takes `?src=`.

### Still differs from the reference
- Fog: ours is morning valley fog under a fair sky, by design (sleep → morning fog, D1). The reference is evening fog with a navy sky (+44 L*). Fog banks over the lake are subtle.
- The sunny top is still ~13 L* paler at the right edge: bloom and the remaining cirrus near the sun. The reference sunny sky is nearly cloudless.
- Night is ~12 L* lighter than the reference, and less contrast-rich toward the horizon.
- Cumulus lobes still read a little as separate round billows with a grey band in the middle of the mass. The reference is more airbrushed.
- The reference's hills are taller and more layered (several misty ranges), and its foreground lip is larger and more painterly. Ours stays small so the first card can start at 42%.
- Rain and storm ground stays lighter than the reference's dark teal (#3F5950), because anything under the glass must stay lighter than #86B9A0 (3.1).
- Samsung proportions (radius 26–28, margin 12, no border) are not applied: the glass was kept exactly as designed this round.
- Today screenshots use Segoe UI as a stand-in when the web font is offline (headless).
- Not done: phone test, re-bake, dark glass for dusk and night.
- STOP: waiting for feedback.

## 3.16 · Fog, night, storm rule, card proportions (2026-11-20)
Feedback on 3.15: every state approved except fog. Clouds: right style. Night: darken it. Fog: keep it a morning fog, and it should hide the sun. Thunder: keep it, but only for really bad stats. Samsung card proportions: apply now. Terrain: we design our own next.

**Changed**
- **Fog (morning)**, rebuilt after the reference "patches of fog" (07:38). New `PAL_FOG`: steel-blue sky that darkens upward, a soft milky band on the horizon lying in drifting horizontal layers, the lake and hills faintly showing through, meadow desaturated. The `valleyFog` preset clears the cumulus and cirrus.
- **The fog hides the sun.** Sun visibility is cut by `w.fog` (smoothstep .45–.85), so it is driven by SkyState, not by a preset flag. The disc, halo, starburst, ghosts and shafts go; a faint glow stays in the sky shader (20%).
- **Night** is about 12 L* darker. Palette darker (zen #081850, mid #0D266A, hor #1A4AA2); grade saturation 1.04 → 1.30; bloom .40 → .22; moon glow, halo and starburst cut further (×.06–.07); no lens ghosts from the moon (they showed as rings on the page); turf leans blue.
- **Silver rim** now shows only when the sun is actually hidden, and only above the horizon. In 3.15 it outlined the hill line and the bottoms of clouds near the sun.
- **Thunder (D2 revised):** allowed only for really bad stats: likely illness (the D6 rain trigger) and readiness under 35. The threshold is my proposal (PLAN-sky.md D2 plus a new row in the mapping table). The scene has no illness input yet, so this is a rule, not code.
- **Cards (r3c-board):** Samsung proportions. Radius 22 → 26, side margin 16 → 12, gap 8 → 12, no border hairline. Tint, blur, ink and shadow are unchanged. Tile grid on Today: week 384–488, sleep and steps from 500, hrv 612, daily 724, resting HR 820. `GLASS_RECTS` updated.

**Measured**
- Glass contrast (day states, `contrast.js`, new rects): worst 4.88 (thunder, week strip). Fog 5.07; text on the fog sky 5.31 or above. Every other state is 5.1 or above. The tilt sweep was not re-run (thunder 4.70 at full tilt in 3.15; thunder is unchanged).
- Night with day glass fails (1.25), as before: dark glass for dusk and night is still not designed.
- Sky dL* (top/mid/near horizon, ours minus reference): night +2/+4/+6 (was +12/+12/+11); fog +23/+9/+21 against the morning reference (was +32/+16/+28).
- Cost (bench, alternating with 3.14, same session): fog full render 1.45 ms (no clouds); night 3.4 ms; compose and post 0.13–0.17 ms. No new per-frame work.

**Still differs**
- The fog sky top stays about 23 L* lighter than the reference: dark ink on the sky needs a background luminance of about .27 or more for AA, and the reference's #446889 would fail. The horizon band is lighter too.
- The fog layering is subtle; the hills are too low to read through it. Terrain is being redesigned next.
- Night ground (the lip) is darker and greyer than the reference's lit meadow.
- Not done: phone test, re-bake (the look is approved, so re-bake once the terrain is settled), dark glass for dusk and night.

Tools: `board316.js`; `sample.js` compares fog against `reference/video/patches-of-fog.png`.
STOP: ready to start the terrain design.

