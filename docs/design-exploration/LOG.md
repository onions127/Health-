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


## T0 · Terrain discovery (2026-11-20, in progress)
Brief: `docs/design-exploration/NEXT-SESSION-PROMPT-terrain.md`. Terrain = everything from the horizon (y .37) to where the cards start (.42), plus the page the cards sit on. The 3.15 land is the control.

**Audit: what the land does now** (`design-prototype/scene-gl.js`, FRAG)
- Slice 0: `hillFar` (≤ 2.0% of screen height) and `hillNear` (≤ 4.2%), both fbm crests on the horizon line; `hillCol` = palette `hill` + sun-facing slope light + haze toward the base + snow on crests; `water` below .37 (sky and hills mirrored, Fresnel, ripples fading to the horizon, streaks, sun/moon path).
- Slice 2: back row of grass blades on `lipTop` (LIP .398 ± .03). Slice 3: turf under the lip, `shrubs`, `stones`, front `blades`, `flowers`, then `page` (pg0..3 from .42 to 1). Cloud shadows on the turf from one `density()` sample. Wind (MRT) moves the lip only.
- Weather/hour: palette swaps (`hill`, `water`, `h[4]`, `pg[4]` per hour and per PAL_FOG/RAIN/STORM/SNOW/OVC); `landFinish` adds fog banks and the rain veil to the far band, cools grey weather, adds haze; snow whitens crests, lip and page; overcast removes slope light; night darkens 10%.
- What the glass sits on: only the page gradient (meadow green → teal → blue), never the hills or lake.
- Cost (PC, headless 412×915, `contrast.js cost`): full slice render fair 2.7 ms, rain 5.7, snow 5.6, fog 1.9, stratus 5.9 (clouds dominate); compose + post 0.16–0.26 ms. Land cost: the blade loops (5 cells × `lipTop` fbm, two slices) and the shadow `density()` tap.
- Glass contrast baseline (same run): fair 5.30, rain 5.12, snow 5.75, fog 5.07, stratus 5.38. Sky text ≥ 5.32.
- Shots: `tools/visual-loop/out/t0/` (all 9 states, `ours-full.png`, `ours-land.png`, `ours-lip.png`, `today-sheet.png`).

**Measured value structure (bands.js, sunny 13:00):** horizon sky L\* 86; hills L\* ~84 (2 under the sky); lip L\* 79–83; page L\* 74–82 down to the nav. The whole lower 60% of the screen spans ~9 L\*. Night: horizon sky L\* 34, land a flat L\* 18–19 slab.

**References:** 36 photos + 26 stylised stills in `reference/terrain/` (gitignored). Rules written to CLAUDE.md "Terrain reference specs". New tools: `tools/visual-loop/montage.js` (contact sheets, band crops), `bands.js` (band ramps with L\*).

**Audit verdict**
- Works: (1) scene and page read as one picture (Samsung principle: land continues into the page); (2) the high-key page keeps AA with margin (≥ 5.07); (3) SkyState reaches the land (fog banks, rain veil, snow, cool grey); (4) wind lives in compose, cheap.
- Fails: (1) **No aerial perspective**: far hills sit 2 L\* under the sky (references: 8–20), so depth vanishes. (2) **No massing**: two thin parallel crests at 2–4% height, then a near-flat lip; references show 3–5 overlapping silhouettes. (3) **Clip-art lip**: one row of same-scale tufts, round flowers and brown stones in a 46 px band reads as a sticker strip, and is below legible size on a phone. (4) **Generic page**: "green bump + blue gradient"; nothing below .45 has material or responds to weather except hue. (5) **Water is a line** (~1.5% of height), so its optics (mirror, path) barely show. (6) **Night has no silhouette**: the land is one navy slab; the crest line, the only thing night land should show, is lost. (7) **Snow is flat white**: no form drawn by shading.
- Constraint found: under the cards the scene must stay ≥ L\* 71 (`#86B9A0`). Deep near values (L\* 25–45) can only live above y .42, so real depth contrast must happen between the horizon and the lip, or by letting land rise above the horizon line into the sky band.

**Interview answers (2026-11-20):** places = lake + layered hills, sea coast / cliffs, open downs / steppe (not mountains/fjord); style = painterly-real; land may also change with the season (date), Mediterranean year; hour of day kept as now; room = stay in the strip, far ridges may rise to ~30%; no-gos = no buildings, roads or other human marks (trees allowed).
**Terrain brief v1 (approved 2026-11-20):**
- Places: lake with layered hills, sea coast with cliffs, open downs or steppe (each a T1 direction family).
- Style: painterly-real. Real light and haze, few flat planes, detail only in the nearest plane, fine grain, no outlines.
- Room: ridge tops may reach y .30; land lives in .30–.42. Below .42 the page stays a light gradient (≥ L\* 71), no terrain detail under the cards.
- Depth: 3–5 overlapping silhouettes; far ridge 8–20 L\* under the horizon sky; dark values only above .42.
- Changes: weather (SkyState) and hour as now, plus season by date on a Mediterranean year (green winter–spring, gold straw summer, amber autumn; snow only as weather). Body data stays in the sky.
- No-gos: no buildings, roads, fences, boats, figures or other human marks. Trees allowed.
T0 STOP passed: user said "Yes, brief OK".

## T1 · Terrain directions, diverge (2026-11-20)
Board: `design-prototype/r4a-board.html` (each direction: token sheet with swatches sampled from the render by the page, Today under the full glass at sunny 13:00, then sunny / rain / night without UI). Renders in `design-prototype/t1/`; single scenes: `scene.html?terrain=<name>&dry=<0..1>`.

**Built (scene-gl.js):** `?terrain=` / `opts.terrain` (control, loch, chalk, downs, steppe, bay) and `opts.dry` (season, 0 green .. 1 straw; default `dryAt(date)` on a Mediterranean year: Nov 20 = .40). Terrains are **numbers in uniforms** (`TERRAIN_P`: per-layer crest shape `uLy`, props `uLz`, lip shape `uLip`, colour tokens `uTk`), one branch-free program. The first version branched per direction inside the grass loops and the D3D compiler stalled (> 5 min); parametric also ports to AGSL as data. Lighting `tLight` (sun or moon on a plane + sky light; overcast trades sun for sky), aerial perspective `tAir` (toward the horizon colour by distance, glowing toward the sun), mist at each layer's foot, crest light, print grain, one cloud-shadow sample per pixel from the volume. Water `tWater` mirrors the far shore about its foot. Steppe ground plane `tPlane` with footprint-filtered texture and seasonal pools. Bay pines `tPines`. Page top colour lifted from the near land (`gPgTop`, L .80). Control path unchanged (lipTop now reads uniforms; control numbers reproduce the 3.15 formula).

**Directions (thesis):**
1. **Loch:** depth by air, three ridges stepping back into the haze over a mirror lake (alternating highs right/left/right, wooded near ridge, reeds).
2. **Chalk coast:** a bright sea horizon and white cliffs that take the sky's colour (two headlands right, open sea left, clifftop lip, surf).
3. **Downs:** long slow swells where light and season do the work (far swell, big swell right with a copse, near swell high left; no water).
4. **Steppe:** almost nothing, a plain with seasonal sky-mirror pools; the sky does the talking.
5. **Pine bay:** a sheltered Mediterranean bay, turquoise shallows over sand, a pale beach, stone pines on a low headland.
Pairwise each differs on ≥ 3 axes (landform, water, vegetation, layer count, colour strategy, motion).

**Visual loop (fixed before showing):** far ridges vanished in haze (D 26 → 42 km); night land glowed olive (moon light × .2); land light ~1.3× albedo flattened the values (now ~1×); chalk cap read as an outline (now a soft shoulder ~⅓ of the cliff, weathered faces, soft surf); steppe was a blurred band (texture frequencies raised, pools made the feature); lip → page fade ran under the first card (now complete by y .419).

**Measured** (`contrastT.js`, one page, 9 day cases each incl. 08/13/17 h, partly, overcast, rain, snow, fog, first run; `floor.js`; `bands`-style sampling on the board):
- Glass AA: worst 5.06 (control) / 5.07–5.08 (all directions), always fog/nav. Sky text worst 5.32.
- Floor (≥ L\* 71 under the cards): every direction passes (sunny 74.4, rain 71.5). **Control rain fails: L\* 66.7 at the first card's top edge (pre-existing since 3.15).**
- Values vs the T0 rule (far 8–20 L\* under the L\* 85 sky): loch 76 / 70 / 55; downs 77 / 75 / 73; bay 72 / 59; chalk inverted by design (faces 81–86); steppe plain 64–70.
- GPU (PC, RTX 4060, headless 412×915, rain): full slice render 5.5–6.4 ms (≤ 8 ms budget), compose + post 0.20–0.25 ms (≤ 0.3). Fair-sky render 2.65–3.1 ms (3.16: 2.5).
- **Regression to fix before lock:** cold shader compile in headless D3D11 went 17 s → ~110 s (plus a second ~110 s for the MRT variant). Bisected: slice 0 terrain code ~70 s, slices 2/3 ~40 s (inlining of crest/colour functions in unrolled loops). Phone GLES compile not measured. Shoot tools now compile once per run and switch states with `set()` (`shootT.js`, `contrastT.js`).
- Season shown at `dry=0.15` (spring) on the board for like-for-like; today's date gives .40 (olive).

**Known weaknesses (for T2/T3):** the lip's grass-tuft rows still read as stickers on loch, chalk and downs; chalk faces still slightly graphic; steppe is the weakest in a 46 px strip; rain lips sit darker just above the cards; motion (mist drift, surf, grass waves) is only partly live (water and surf animate in the slice, not in compose).

Tools added: `tools/visual-loop/shootT.js`, `contrastT.js`, `floor.js`, `probe.js`, `montage.js`, `bands.js`.
STOP: waiting for the user to pick, kill or mix directions.
**T1 decision (2026-11-21):** Loch is the only survivor. The baseline (control) and Downs "somewhat compete". The trees from Pine bay are liked, but they need not be pines. Killed: chalk, steppe, bay (as directions). Asked for T2: Loch with less water and a bit more landscape variation; Loch with two small trees; more directions from me.

## T2 · Loch, converge (2026-11-21)
Board: `design-prototype/r4b-board.html` (each loch: what changed, sampled swatches, Today under the full glass at sunny 13:00, then sunny, partly, overcast, rain, snow, morning fog, dawn, dusk, night, first run, and the season across a Mediterranean year: winter .05, today .40, summer .95). Renders in `design-prototype/t2/`.

**Five lochs** (`?terrain=`):
1. `loch`: the T1 survivor, reference.
2. `lochLand` (asked): less water (strip 3.1% → 2.3%, a grassy promontory from the left hides about a third of it) and more land variation (a fell with two summits and a saddle, a mid hill with woodland patches, a smaller wooded knoll).
3. `lochTrees` (asked): the T1 loch plus two small broadleaf trees (holm oak / olive habit) on the shore at 74% and 86% across, 3.6% and 2.6% of the screen high.
4. `lochDowns` (mine, for "Downs competes"): a small loch held between grass swells, copse on the big swell, one tree; season strongest here.
5. `lochOpen` (mine, for "the baseline competes"): the control's layout (wide water, two long low ranges, flat quiet shore) rebuilt with T1 light and air.

**Built (scene-gl.js):** `tTree` (four crown masses per tree with leaf-clump edges, lit toward the sun and darker underneath, short leaning trunk, contact shadow on the turf, sways with the gusts via the wind material), woodland patches per layer (`uLw`), grass rows as numbers (`uGr`: back/front density and height, mask, wind-comb), trees as numbers (`uTr`, `uTrC`). The T1 per-direction grass branches are gone.

**Fixed on the way (visual loop):**
- Dusk and dawn drew a bright outline along the far ridge tops: the post pass's silver rim (3.16) only excluded the old low hill line. Now it only acts above y .285, over the highest ridge.
- Near clouds drew in front of the far fell (physically possible, a cloud base below a 2 km summit, but it read as clouds cut into the mountain). The near-cloud march now stops at the terrain (`clouds(..., fd)` with the front layer's distance), so ridges hide what is behind them.
- Trees first read as dark cut-outs: lighter albedo, stronger sun-side light. Woodland patches were large camouflage blobs: smaller and broken up.

**Measured:**
- Glass AA: worst 5.07 for all five (always fog, nav), spring and (lochTrees, lochDowns) summer. Sky text worst 5.31.
- Floor (≥ L\* 71 under the cards): every state shot passes for all five (`floor.js` over 11 states each, incl. summer).
- Values under an L\* 85 sky (far / mid / near): loch 76/70/55, lochLand 76/65/51, lochTrees as loch, lochDowns 77/76/69, lochOpen 81/69 (far only 4 under: weakest depth).
- GPU (PC, rain): full render 4.3–6.2 ms, compose + post 0.17–0.26 ms. Cold compile still ~110 s per program variant (headless D3D11), open item.

**Open:** the back grass row still reads as a row of tufts (all variants); lochOpen's far range sits too close to the sky value; motion (mist drift) not shown on stills.
STOP: waiting for the user to pick the loch (or a mix) for T3.

## T3 · Loch, refine: merge, mountain, near trees, shore (2026-11-21)
**T2 decision (2026-11-21):** likes Loch · less water (more land), the small trees but much closer to the front and something beautiful (sugar maple, cherry blossom, or other species that fit the place), the closeness of Loch in the downs, a high mountain on the far horizon for depth, the calm of the open loch. Add sand or brown soil. "We can roll back if need be; we haven't landed the landscape yet."

Board: `design-prototype/r4c-board.html` (Decision 1: water A/B, each with Today under the full glass, the land band, ten weather/hour states and the year; Decision 2: six tree species across the year). Renders in `design-prototype/t3/`. Single scenes: `scene.html?terrain=lochHeld|lochCalm&tree=<species>&doy=<day of year>`.

**Two merged lochs (the A/B):** `lochHeld` = the downs' closeness + less water (near swell high left, big swell close right, a small loch between, the mountain framed between them). `lochCalm` = the open loch's wide still mirror, long low ranges, land rising only at the left edge. Shared: mountain, trees, shore.

**Built (scene-gl.js):**
- Far mountain (`MTN`, layer 0, 46 km, ~7% of screen high, centre-left): a range from two massings + a cosine of summits + ridged detail; rock on steep faces; a snow line that follows the season (`uLm`, table `MSNOW` by day of year: deep in Jan, patches in Jul, first falls late Oct); gullies carry snow down. Mirrored in the lake.
- Near trees (`tTree` rewritten): trunk flaring at the root, six bowed limbs, twigs, nine leaf clusters on a golden-angle spiral, each lit as a soft sphere toward the sun, darker underneath; autumn colour varies per cluster (sun side first); blossom among the leaves with a little translucency; a thinning crown opens up and a bare crown reads as a soft twig haze. Species and the year are data (`SPECIES`, `phen(species, doy)` → uniforms `uTs`): maple, cherry, Judas tree, almond, oriental sweetgum, oriental plane (holm oak keeps the T2 trees). The large tree is 11.8% of the screen high (3× T2), the second 5.6%, both right of the hero.
- Shore (`uSd`): sand beaches in coves where the lip meets open water (wet edge), a broken fringe of earth behind them, worn earth patches (more in the dry months), leaf litter or petals under the trees by season; grass tufts thin out on the beaches.
- Near trees are no longer veiled as "distance" by fog and rain (`gNear` in `landFinish`).
- `?doy=` / `opts.doy` drives trees, mountain snow and grass dryness together; `?tree=` picks a species. `shootT.js`: `terrain:species` specs, year states (jan … oct), states no longer carry `doy`/`dry`/`tree` over from the previous shot.

**Visual loop (fixed before showing):** the first beach was one continuous band along the lip with a dark edge (read as a path: banned human mark) → coves only, broken earth fringe. First mountain was a lone cone (read as clip-art Fuji) → a range. Trees read as lollipops with antenna twigs → lower, wider crowns, bowed limbs, twigs hidden in full crowns, twig haze when bare. Rain and fog washed the near trees out like the far hills → exempt.

**Measured:**
- Glass AA worst: lochHeld 4.98, lochCalm 5.05 (overcast, week strip; T2 was 5.07). Still ≥ 4.5. Sky text worst 5.32.
- Floor under the cards: ≥ L\* 71.5 for both (rain, fog, overcast, snow, summer, spring cherry).
- GPU (PC, rain): full render 5.9 / 6.4 ms, compose + post 0.23 / 0.24 ms.
- **Cold compile regression:** headless D3D11 compile + first converge went from ~110 s to ~310 s (the tree loops are unrolled per call site). Must be fixed before lock (phone GLES compile not measured).

**Critique (impeccable, degraded single-context, scene judged under the glass):**
1. Hierarchy: the near tree is now the only saturated warm mass on the screen and competes with the readiness number for the first read (most in autumn red and Judas magenta).
2. Crown edges are lumpy at 110 px: painterly, but the leaf-scale texture is missing, so a crown reads as a cut-out on close look.
3. Bare winter (Dec–Mar for most species) still shows a few straight limb ends through the haze.
4. The mountain does its depth job best on B (open, mirrored); on A the swell hides most of it.
5. Unchanged: the land is still a ~5% band; the green lip → page fade under the first card is flat.

STOP: waiting for the user's pick on water (A held / B calm / mix) and tree species.
**T3 decision (2026-11-21):** water B (open and calm, `lochCalm`), tree: maple. Asked: the mountain more of a range and not the whole horizon; the tree textures are the weakest part ("they look really bad").

## T4 · Mountain range, tree foliage (2026-11-21)
Board: `design-prototype/r4d-board.html` (one decision: leaf clump size A fine / B medium / C painted, each with Today under the full glass, the tree through the year, and ten weather/hour states). Renders in `design-prototype/t4/`. Single scene: `scene.html?terrain=lochCalm&tree=maple&leaf=fine|medium|painted&doy=`.

**Mountain:** a bounded range (u ~.08–.62, about half the horizon), five or six summits under a cosine of peaks, sharp ridged crests (`1 - |2n - 1|`, two octaves) whose detail is scaled by the massif's own height, so the range ends in open horizon on both sides instead of running along it (T3's ridge detail was added everywhere). 38 km (was 46) so it reads; mirrored in the loch; snow line by season as T3.

**Trees, rebuilt (`tTree`):** three versions this round, two rejected in the loop:
1. Separate cellular leaf clumps over a dark core → read as orange sprinkles / polka dots.
2. Overlapping clumps kept by their centre, crevice along each cell border → read as cobblestones / bubble wrap (a visible Voronoi grid).
3. **Shipped:** painted foliage. The crown is a lobed silhouette lit as one form plus its masses, in four tones (underside, shade, mid, light); the leaf texture lives only in the edges: clump bumps (cellular, two scales) break the silhouette and the borders between tones. Autumn thinning opens rounded gaps from the rim inward; sun through the brightest edge; sparse leaf glints; shade side pulled below sky-light level for a clear light/shadow split. Wood: trunk flaring at the root, five limbs fanning up, side branches; bare months keep the twig haze. `TREE_STYLE` (`?leaf=`): clump cell .040 / .060 / .090 of the tree's height.
- Big tree moved to u .865, 10.8% high (was .83, 11.8%); small tree .655, 5.2%.

**Measured:**
- Sky text: **failed at first, 2.31:1** (overcast): the wider crown reached under the end of the readiness detail line. After the move: worst 5.32 (header, fog). Rule kept: the crown top stays below the hero block (y ≥ .29) right of x .66.
- Glass AA worst 5.02 (overcast, week strip). Floor under the cards ≥ L\* 71.5 (rain, fog, overcast, snow, summer).
- GPU rain full render 4.2–6.7 ms between runs (noisy under parallel jobs), compose + post 0.15–0.26 ms.
- Cold compile + converge, headless D3D11: 324–384 s per run. Still the open regression from T3 (was ~110 s in T2); the new tree removed the nested loops but not the cost. Must be fixed before lock; phone compile unmeasured.

**Still differs / open:** the crown edge is slightly ragged on the sun side; the land is still a thin band; the lip → page fade under the first card is flat; compile time.
STOP: waiting for the user's pick of leaf clump size (A / B / C).
**T4 decision (2026-11-21):** fine leaves are better but not yet to standard; the bare tree is the worst part (grey blob, branches not real). The range should take twice its share of the screen, run to the left edge and be a little taller.

## T5 · Wider range, grown trees (2026-11-21)
Board: `design-prototype/r4e-board.html` (T4 → T5 before/after on the land band at 1:1: January bare, today, July; then fine leaves with Today, the year and ten states; medium for comparison). Renders in `design-prototype/t5/`.

**Mountain:** one continuous range from the left edge to ~u .55 (about twice T4's span), main summit ~8% of the screen high (`MTN` broad massing c .25 w .37 + summit .40, cosine summits only .14 deep so saddles stay high, two-octave ridged crest .016). First try (cos .30) read as three separate snow cones. Snow follows gullies (two-octave vertical streak noise, deeper cut), rock ribs stay dark.

**Trees, grown (`treeSkel` in JS → `uTreeTex`):** the branching is grown once on the CPU per tree (seeded): trunk to the fork, four scaffold limbs, then breadth-first: each branch ends in a zigzagging leader (0.74 length) and throws 0–2 laterals on alternate sides along its outer half; widths by da Vinci's rule; a tip that would leave the species' crown envelope is shortened along its own direction. ≤ 400 segments per tree, two RGBA32F texels each (ends; widths + the scaffold limb's mass centre), one row per tree, texture unit 15; regrown only when species or seed changes. Previewed as SVG first (`skelsvg` in the scratchpad) to iterate without the 6-min compile: projecting tips onto the envelope made hoops → shorten instead; depth-first growth starved the right-hand limbs at the cap → breadth-first.
- Shader (`tTree`): one dynamic loop over the segments with a per-segment box reject; each branch a tapered cylinder lit on its sun side; sub-pixel twigs fade by width and add up (union), so the bare crown is a real twig mesh. **The grey twig haze is gone.** Twigs redder and paler than bark.
- Foliage hangs on the outer branches (segments thinner than .0105): crown = union of leaf reach around them (reach shrinks as the crown thins), edge broken by the fine clump field; shading = crown form + the limb's own mass + clump; autumn gaps open in a few clusters (first try left salt-like sky specks).

**Measured:** glass AA worst 5.03; sky text worst 5.32; floor ≥ L\* 71.5; GPU rain full render 3.7 ms, compose + post 0.15 ms. Cold compile + converge 375–482 s (open regression since T3).

**Still differs / open:** autumn gap edges are a little pixel-ragged; leaf-out (Apr) crowns patchy; compile time; the land band is still thin and the lip → page fade flat.
STOP: waiting for the user.
**T5 decision (2026-11-21):** keep refining the trees.

## T6 · Tree foliage, refined (2026-11-21)
Board: `design-prototype/r4f-board.html` (T5 → T6 on the land band at 1:1: July, today, April leaf-out; then the maple with Today, the year and ten states). Renders in `design-prototype/t6/`.

**Close-up critique of T5 (5× crops):** the crown read as one smooth blob; inside, hard tone steps on cellular facets read as crumpled paper; autumn gaps were salt specks.

**Changed (`tTree` foliage, `treeSkel`):**
- Leaf domes: every level-2 branch group carries a mass centre; its twigs hold leaves only inside a dome around that centre (radius .20–.28 of the tree height, shrinking as the crown thins). The crown is a cluster of overlapping lobes with a few deep gaps where limbs show.
- Shading: dome normal first, then crown form and the leaf clump; soft three-step painted ramp (no hard posterize); each dome's underside and the crown's underside darker; a soft crevice between clumps; sun through the brightest clumps.
- Texture: round leaf clumps (fine cellular, 4% of tree height) that also scallop the outline.
- Thinning: whole clumps fall, chosen by a cluster noise at the clump's centre plus a per-clump hash; holes are round with varied, slightly ragged radius.
- First try (domes .17–.24, reach .066) was too open (an umbrella with a hole): domes .20–.28, leaf reach .082, mass centre closer to the branch.

**Measured:** glass AA worst 5.02; sky text worst 5.32; floor ≥ L\* 71.5; GPU rain 6.2 ms full (shared GPU; T5 3.7), compose + post 0.23 ms; compile 350 s.

**Open:** autumn holes still read a little as pinholes near the crown top; compile time; land band thin, lip → page fade flat.
STOP: waiting for the user.
**T6 decision (2026-11-21):** keep refining; the holes are the main problem: a crown that isn't full looks shot with a shotgun, perfectly round holes.

## T7 · Thinning without holes (2026-11-21)
Board: `design-prototype/r4g-board.html` (T6 → T7 for today and April leaf-out; the fall day by day 21 Nov → 28 Nov → 3 Dec → 15 Jan; leaf-out 14 Apr → 19 Apr → 15 Jul; then Today, the year and ten states). Renders in `design-prototype/t7/`.

**Cause:** T6 thinned a solid crown by removing whole leaf clumps; clumps are round, so the gaps were circles.
**Changed (`tTree`):** the hole mechanism is gone. Thinning happens in the branch loop: (1) each branch group gets its own fullness (`full` ± up to .8×(1 − full) by a per-group hash), so groups thin and go bare at different times; (2) a group's leaf reach around its twigs shrinks with `fd^1.5` (.082 → .004) and its dome with it (×.55 at bare), so leaves fall back onto the twigs and gaps take the shape of the spaces between branches; (3) along the twigs the reach is cut by a two-octave value noise in tree space (clusters, never circles). Leaf-out uses the same path in reverse. Full crowns and bare winter unchanged. First pass left late crowns solid inside → steeper reach curve.
**Measured:** glass AA worst 5.04; sky text worst 5.32; floor ≥ L\* 71.5; GPU rain 5.0 ms full, compose + post 0.19 ms; compile 418 s (open).
**Open:** one or two sky specks remain at the thinnest crown edges; compile time; land band thin, lip → page fade flat.
STOP: waiting for the user.
**T7 decision (2026-11-21):** "yes, move on": the thinning is accepted; move to the next open item (the land band and the lip → page seam).

## T8 · The ground under the cards (2026-11-21)
Board: `design-prototype/r4h-board.html` (per option: Today under the full glass at sunny 13:00 and partly cloudy, then ten states without UI). Renders in `design-prototype/t8/`. Single scene: `scene.html?terrain=lochCalm&tree=maple&leaf=fine&ground=flat|swell|fields`.

**The decision:** what the land does under the glass. Horizon (37%) and first card (42%) are fixed, and everything under the cards stays ≥ L\* 71, so the land can only continue there high-key.
- **A · Flat** (T7, control): the land stops at the lip; one calm gradient under the cards.
- **B · Swell:** the ground plane runs on under the cards: a two-octave rolling height field in ground units (near forms large, far ones small and hazed), gentle slopes (~10–15°), lit from the sun's direction with a soft tanh shoulder, crests warm and hollows cool; the clouds' shadows cross it (one low-detail density tap per page pixel). The meadow green holds ~7% lower before the page turns teal. Diffuse light (overcast, rain, fog) flattens the forms.
- **C · Fields:** B's ground laid out as a patchwork (cellular, wider than deep): one hue per field (green, sage, straw that grows with `dry`, pale), lum-matched to the page; hues and borders only in the meadow zone (y < .70).

**Built (scene-gl.js):** `gForm` / `gH` / `gField`, uniform `uGd` (mode, form strength, cloud shadows), JS table `GROUND`, option `ground` (`scene.html?ground=`, `shootT.js` 4th field, `contrastT.js` now takes `terrain:tree:leaf:ground`). Floor in the shader: by day hollows may darken to L\* 71 (`lift` to luma .715); a page already near the floor (fog) is not darkened; dusk and night keep their own page.

**Visual loop (fixed before showing):**
1. First pass: the slopes were far too steep, so the light saturated against a hard clamp: flat dark plateaus with hard edges in a repeating camouflage pattern (and the swell read as a water surface in rain). → slopes ×.16, tanh shoulder, two broad octaves (the third made mottling), weather damping.
2. Fields: one field turned cyan (hue multiplier on the teal page) and big patches read as lakes → hue laid on lum-matched, only in the meadow zone, smaller fields; then thin polygon borders showed across the blue band (cracked tiles) → borders only where the page is still meadow, softer.
3. Floor failed in fog and rain (L\* 68.8–70.2) → shader floor. A first absolute floor lifted the approved dusk page to pale lavender → floor relative near the threshold, dusk untouched. A fully relative floor left only bright crests (read as lily pads) → day hollows may go to L\* 71.

**Measured:**
- Glass AA worst: A 5.04, B 5.04, C 5.03 (all overcast, week strip). Sky text worst 5.31–5.32 (header, fog).
- Floor under the cards (`floor.js`, 10 states each): A, B, C ≥ L\* 71.5 by day (rain lowest); dusk 53–54 for all three (as in every round; dusk is not under the day glass rule).
- GPU (PC, rain): full render A 6.17, B 6.81, C 6.16 ms; compose + post 0.21–0.23 ms. Cold compile + converge 402–448 s (open since T3).

**Still differs / open:** in B the lit crests on the teal band still line up a little in rows (perspective of one noise field); C's field hues are subtle once blurred by the glass; compile time.
STOP: waiting for the user's pick (A flat / B swell / C fields, or a mix).
**T8 decision (2026-11-21):** A · Flat. The cards keep the calm page gradient; the land stops at the lip. `ground` stays `flat` by default; the swell/fields code (`gForm`, `uGd`, `GROUND`) is removed at terrain lock with the other unchosen variants.

## T9 · Compile time (2026-11-21)
**Asked:** fix the cold compile (open since T3: ~410 s headless from page load to a converged scene; T2 was ~110 s).

**Measured where it goes** (new tools in `tools/visual-loop/`: `compileT.js` compiles one program or a variant with functions stubbed out and times link and first draw; `hlsl.js` dumps ANGLE's HLSL; `loadT.js` times the scene's load phases with `window.SCENE_T`; `pixdiff.js` diffs two folders of shots; `benchT.js` repeats the GPU bench; `bakeT.js` smoke-tests the on-device bake path):
- Not loop unrolling: ANGLE already emits `SampleLevel` (no gradients in loops), and bounds FXC can't unroll changed nothing (308 s vs 282 s).
- Stubbing parts: the cloud march −163 s, the terrain layers (`tColor`/`tCover`) −141 s, the trees only −38 s. HLSL inlines every call site, and `clouds()` was inlined 3 times (`skyCol()` from two branches of slice 0, plus slice 1), so the cloud density 19 times.
- The load paid for the whole scene shader **twice**: D3D's FXC at link (~131 s), then the GPU driver's own compile at the first draw (~130 s). That second compile only shows with the real textures bound (with none bound the driver strips the code, which is why a bare harness missed it).

**Changed (scene-gl.js), the image unchanged:**
1. One call site of the cloud march for slices 0 and 1 (`skyCol()` takes the march result); one cloud-density sample per pixel for slices 2 and 3 (`cDen`, shared by the mid layer, the turf and `gForm`). Scene shader alone: 214 → 131 s.
2. The scene is **two programs** from one source (`#define SKY_ONLY` / `LAND_ONLY`): sky (slices 0, 1) and land (slices 2, 3), compiled together (`progs()`: all compiles issued before any status is read, `KHR_parallel_shader_compile`). Uniforms go to both each frame (`sceneAll`, one jitter offset per frame); `useSlice(i)` picks the program. Compile time grows faster than the code, so the halves cost far less than the whole (contended: sky 90 s, land 64 s, both stages).
3. Slices 0/1 get an unused RGBA8 second target (`pad`) so all four slices share one render-target layout (no extra shader variant); `window.SCENE_ONEPROG = true` keeps the old single program as a test switch.

**Measured:**
- Page load to converged scene (headless D3D11, `loadT.js` / `shootT.js`): **410 s → 105–114 s** (shaders 46 s, first draws to 103 s).
- Pixels: 10 states against the T8 renders: max 18–56, mean ~1.0 level, the same as two runs of the old shader (max 18–28, mean ~1.0) and as two runs of the new one (dusk max 54): differences sit in the per-frame random jitter (water glints, crown edges). Crops of dawn and dusk side by side look identical.
- GPU (PC, rain, same session): full render 4.1 ms one program / 4.2 ms split; compose + post 0.16 ms. (One earlier reading of 10.7–12.9 ms did not reproduce with unchanged code: other load on the GPU.)
- Glass AA worst 5.05 (overcast, week strip); sky text worst 5.31. On-device bake path (`renderSliced` + `bake()`) runs and writes all eight slice files.

**Open:** phone compile on the S21 not measured (the phone plays bakes; the on-device baker compiles the two live programs); measure at terrain lock.

## Terrain lock (2026-11-22)
**Asked:** "terrain lock". Locked: the open calm loch (T3 `lochCalm`), maple trees (T3), fine foliage (T4), thinning without holes (T7), a flat page under the cards (T8 A).

**Removed from `scene-gl.js`** (1751 → 1465 lines; the image unchanged):
- The other eleven terrain directions (control 3.15 land, loch, chalk, downs, steppe, bay, the T2 loch variants, lochHeld), their tokens and shapes; the `terrain` option and `uTerrain`. The land is now one table each: `LAND`, `TK`, `LEAF`.
- Shader code only they used: the 3.15 hills, lake and lip (`hillFar/Near/Col`, `water`, stones, shrubs, flowers), the steppe plane (`tPlane`), bay pines, foam and wet sand, chalk faces, surf, headland ends, crest copses and crowns, woodland patches, painterly dabs, wind-combed downs grass (`uLw`, `uTrC`, `uHill`, `uWater` gone).
- Species other than maple (cherry, Judas tree, almond, sweetgum, plane, holm oak), leaf styles medium and painted (`tree`, `leaf` options), and the T8 swell and fields (`gForm`, `gH`, `gField`, `uGd`, `GROUND`, the `ground` option).
- The `SCENE_ONEPROG` test switch (T9). `scene.html` drops `terrain`, `tree`, `leaf`, `ground`; `?dry=` and `?doy=` still preview a season.
- The T1–T8 boards are static images and still open; their `scene.html?terrain=…` notes are history.

**Measured:**
- Pixels, 10 states against the T9 renders (`pixdiff.js`, now with `BANDS=1` and `DIFFDIR=`): mean 0.97–1.09 levels, > 8 levels on 0.09–0.47% of pixels, all on edges (sun spikes, crown rims, the shore line, cloud rims) in the jitter, no area changed; the same order as two runs of one build.
- Cold load to a converged scene (headless D3D11, `loadT.js`): **~110 s → 24.5 s** (the dead branches were still compiled).
- GPU (PC, rain): full render 3.8–4.4 ms, compose + post 0.15–0.18 ms.
- Glass AA worst 5.05 (overcast, week strip); sky text worst 5.32 (header, fog).
- Re-baked `baked/today` (`bake.html`, headless via new `tools/visual-loop/bakeAll.js`): 14 keyframes (13 fair hours + stratocumulus 13:00) in 48 s, 17.5 MB (1.1–1.9 MB per keyframe; the 3.12 bakes of the old land were 2.5–3.6 MB). Baked vs live (`ab.html`, headless via new `abT.js`): 6:30 47.6 dB, 13:00 48.1, 19:00 46.8, 21:00 48.5 (worst tenth 41.2): identical to the eye.

**Phone test** (Galaxy S21, Mali-G78, Chrome over adb + DevTools, new `tools/visual-loop/phoneT.js`; on USB, 48–49% battery, start 34.1 °C battery / 39.9 °C AP, thermal status 0):
- Live, cold (cache cleared): noise 1.2 s, **both scene programs compiled by 3.8 s**, first frame 4.8 s, converged 8.8 s after navigation. Warm reload: shaders 0.8 s, converged 4.1–5.0 s. (On the PC headless D3D11 the same load is 24.5 s: the phone's GLES driver compiles far faster than ANGLE's HLSL path.)
- Full render on the phone (4 slices + compose + post): 225–229 ms, the same order as 3.11 (140–240 ms per slice render then).
- Baked (`baked/today`, PC bake): ready 1.2–1.9 s after navigation at 13:00, 1.3–1.5 s at 19:30 (a blend of two keyframes). Page **60 fps** idle; a tilt frame (compose + post) 8.7–13.0 ms of GPU; live mode after converging 48 fps.
- Three minutes of baked playback with wind and birds: 59.8–60.1 fps throughout; battery 35.1 → 36.2 °C, AP 43–45 °C, thermal status 1 (light), 1% battery.
- First run read ~1 fps: the test drove a background Chrome tab (throttled); `phoneT.js` now brings the test tab to the front. No GL errors; the only 404 is `favicon.ico`.

**Terrain lock done.** Nothing committed yet.

## G1 · Glass for dusk and night (2026-11-22)
**Asked:** dark glass for dusk and night (open since 3.12: night with the day glass reads 1.35:1).

**Measured first** (`tools/visual-loop/glassSweep.js`, `glassNeed.js`; `glass-check.js` now takes a glass tint, `saturate()` and light ink):
- The day glass (22% white, ink `#2E4A62`) fails well before night: 18:00–19:45 reads 3.2–4.2, dawn 05:15–05:45 1.7–3.7.
- At dusk the page under the cards is mid grey (L\* 55–70): dark ink on light glass and light ink on dark glass both fail at their usual strengths. The smallest glass for AA (4.6) rises to ~.5 on both sides; the two are equal at page L\* ≈ 55.
- Clear night needs almost no glass (any dark tint passes); rain and snow nights sit at L\* 37–43.
- Sky text: the hero cloud behind the number is lit by day and by the moon at night (`#7789C2`, white on it 3.15). Which ink needs less help follows the sky behind the text, not the page (rain night: light ink needs no veil; snow night: dark ink + white veil .34).

**Proposed rule (`design-prototype/glass-rule.js`, the same for every option):**
- Cards follow the page L\* under them: ≥ 56 day glass, strength from a measured table (22% → 53% as the light falls); < 56 night glass with light ink (`#FFFFFF` / `#C9D7EA`), ≥ 40%. Nav 55% day / 72% night.
- Sky text takes the ink that needs the weaker veil; the veil is a top band (full to 272 px, gone by 340 px) in the glass tint or white, at the measured strength + .03.
- The Log weight button picks its fill from the sky behind it: blue `#0B5FA5`, light pill `#DCE9F7`, or deep navy `#082F5A` (non-text 3:1), sitting on the veil.

**Board:** `design-prototype/r5a-board.html` (renders `g1/`, numbers `g1/measure.json` from `tools/visual-loop/g1.js`). One decision, the night glass's colour: **A · Navy** `#0A1E4A` (constant, like the reference) · **B · Smoke** `#0E1116` (neutral) · **C · Sky** (the zenith's hue at Y .013: navy at night, `#1D1B33` at dusk, `#1B1D2F` in rain).

**Measured (9 states: day control, 18:30, 19:45, 20:30, night clear / partly / rain / snow, dawn 05:30):** every text passes in every state for A, B and C; worst 4.78 (day glass at 18:30, secondary text). Cards at night 9.3–10.4 (A), 10.4–15.1 (B); nav ≥ 5.57; sky text ≥ 4.83; button ≥ 3.35. The night options differ in look, not in contrast.

**Open / still differs:** twilight 20:30, snow night and dawn need a white veil of 28–41% behind dark sky text (the moonlit or dusk-lit hero cloud is mid-grey): visible as a pale band. A scene-side fix (dim the hero cloud's lit side behind the text after sunset) would remove it; not in this round. Transition timing (how fast the swap animates when it crosses while the app is open) not shown on stills.
STOP: waiting for the pick (A navy / B smoke / C sky).

## G2 · Scene glass, lower contrast (2026-11-22)
**Asked:** none of G1's night glasses (navy, smoke, sky) felt right: the cards felt cut off from the scene. Lower the contrast to get the feel right.

**Found:** at night the text needs almost no glass. White ink passes AA at 12–16% on every clear, cloudy and rainy night. G1's slabs came from the **40% night floor** (a design choice) and from `saturate(1.6)`, which lifted the blue land under the cards *above* the page around them. Contrast only costs glass at dusk (18:00–20:00, page L\* 55–65), at dawn, on snow nights, and for the sky text over the moonlit hero cloud.

**G2 material (the same in every section):** the glass is only as strong as the text needs. Night glass = the land's hue under the cards at Y .02 (`#152256` clear night, `#1E263F` twilight), 22% floor (the day glass's strength), `saturate(1)` (reads a little darker than its surroundings, like the reference), faint light edge (inset 10% white hairline). Day glass unchanged (white, ≥ 22%, saturate 1.6, dark ink). Mode = whichever ink needs less glass. Sky text: veil in the same tint only where the floor needs it.

**Board:** `design-prototype/r5b-board.html` (scene renders reused from `g1/`, numbers `g2/measure.json` from `tools/visual-loop/g2.js`, `SATD=` sets the night saturate). One decision, the contrast floor for small text:
| Floor | Worst small text | Hero number | Dusk 19:45 glass | Night veil |
|---|---|---|---|---|
| 4.5 (AA, control) | 4.65 | 4.29 | white 52% | 29–32% |
| 4.0 | 4.17 | 3.76 | white 44% | 21–24% |
| 3.5 | 3.69 | 3.38 | white 36% | 11–15% |
| 3.0 (AA large only) | 3.18 | 3.29 | white 27% | 0–4% |
Night cards 9.3:1 and the button ≥ 3.32 under every floor. Deep night and day look the same in all four sections.

**Still differs:** dusk 19:45 stays a milky lavender light glass under every floor above 3.0. Twilight and snow nights keep a 19–40% veil behind the sky text: the scene-side fix (dim the hero cloud's lit side after sunset) is still open. Below 4.5, the 12 px secondary text fails WCAG AA at dusk and dawn. This rewrites the brief's "AA measured" rule for those hours: the user's call.
**Picked (2026-11-22): 3.0.** Encoded as `GlassRule.G2` in `design-prototype/glass-rule.js` (floor 3.0; day glass white ≥ 22% from page L\* 50 up, 27% at L\* 55, 31% at 50; night glass the land's hue, 22%, saturate 1, light edge; nav 55% / 30%). Checked against `g2/measure.json`: mode and strength match all 9 states. G1's navy / smoke / sky options are dropped. Still open: the 19–24% veil behind the sky text at twilight and on snow nights (scene-side fix: dim the hero cloud's lit side after sunset).

## G3 · The sky behind the text after sunset (2026-11-22)
**Asked:** remove the veil behind the sky text (number, header) that G2 at 3.0 still needed: twilight 20:30 24%, snow night 19%, dawn 05:30 12%.

**Found** (`tools/visual-loop/skyProbe.js`: L\* range under each sky-text rect, no glass): light ink reaches 3:1 only where the sky under it is L\* ≤ 59, dark ink only where it is ≥ 48. After sunset four things broke that, all in the scene:
- Cloud sky light mixed 55% white at every hour (`ambHi`): the moonlit hero cloud read L\* 61–81 behind the text at twilight and dawn.
- The key light faded linearly from dusk (I 1.1) to night (I .26): cloud tops stayed sunlit next to sky gaps that were already dark (05:30: L\* 43–79 in one rect, so neither ink passes).
- Stars at full strength: L\* 70–78 points under the header.
- Falling snow pure white at night: L\* 96 specks on an L\* 41 sky.

**Changed (scene-gl.js):** `ambHi` white mix × (1 − `AMBN`·night), `AMBN` 1. Key intensity × (1 − `TWI`·sstep(.35, .6, night)·(1 − sstep(.85, 1, night))), `TWI` .9 (`?twi=` to preview; 19:45 is untouched, the sun is still up). Stars × .30 (the reference's "low intensity"). Snow colour `uSnowCol` = white by day, the palette's lit-cloud colour × .6 at night. `glass-check.js`: header rect ends at 245 px, where its text ends (it ran under the button and the moon's starburst). Log weight button: the pale pill `#DCE9F7` failed on the moonlit sky without a veil (2.58), so its light option is now white (`#FFFFFF`, ink `#0B3A66`).

**Measured (floor 3.0, `g2.js` with `OUT=g3 SHOOT=1 FLOORS=3`):** veil 0 in all 9 states (was 24 / 19 / 12 / 4% at twilight / snow / dawn / partly). Worst sky text 3.17 (snow night), number 3.20, cards 3.18 (dusk 19:45), button 3.14. Sweep (`glassNeed.js`, T 3.05): no veil from 20:15 to 06:15; 5–14% left at 20:00 (clear 6, rain 5, partly 14), the minute the ink swaps. Clear night passes even AA 4.5 with no veil (4.61). Pixel diff vs G1 renders: day 13:00 mean 1.0 (noise); changes in the top half at twilight, night and dawn, and the flakes. Load to converged 29.3 s (baseline ~25 s; no new call sites).

**Board:** `design-prototype/r5c-board.html` (before = G2 scene `g1/` + `g2/measure.json` regenerated on the pre-G3 scene; after = `g3/`).

**Still differs:** twilight clouds are greyer and flatter than before (they lost the set sun's glow); the moon's starburst still shows at 20:30. Baked keyframes (`baked/`) are stale for twilight and night hours: rebake before phone tests.
**Picked (2026-11-22): keep** the greyer twilight clouds (`AMBN` 1, `TWI` .9). Re-baked `baked/today` (`bakeAll.js`): 17 keyframes, 20.2 MB. The twilight dip is not linear in the hour, so 20:30 read 32.7 dB as a blend of 20:00 + 21:00: keyframes 5.5, 20.25 and 20.5 added (`KEYFRAMES`), Cache Storage bumped to `sky-v4`. Baked vs live (`abT.js`): keyframes 47.5–48.8 dB; between them 5:15 44.9, 5:36 41.9, 20:06 39.1, 20:45 44.1.

**G3 phone test** (Galaxy S21, Chrome 157 over adb + DevTools, new `tools/visual-loop/phoneG3.js`; today's bakes, seed 20779; the page set to the app's full-screen 360×800 @2× through DevTools, since Chrome's address bar leaves 360×691 and moves the sky under the text; battery 36.2–36.7 °C, 58–59%):
- Bakes play at every hour tested: ready 1.8–1.9 s, no GL errors, page 57–60 fps.
- Sky text with **no veil**, measured on the phone's own canvas at 3:1: pass at 19:45 (dark ink 3.18), 20:15 (light 3.11), 20:30 (4.68), 21:00 (4.04), 23:00 (3.99), 05:15 (3.87), 05:30 (3.08). Fail at 20:00 (best 2.07) and 05:45 (2.85): both are ink-swap minutes, where the rule lays its thin veil (on the PC, 20:00 needed 6% and 05:45 none; the phone's 05:45 plays a blend of the 05:30 and 05:45 bakes).
- Log weight button ≥ 3.23 at every hour.
- Measures are approximate: the phone's canvas is 100vh (1712 px, 56 CSS px taller than the view), so the horizon sits ~7% lower in the 412-wide frame than on the PC.

## L1 · A still scene, and the sun's and moon's spikes (2026-11-22)
**Asked:** two problems with the current UI: the scene moving when the phone tilts feels odd, and the rays from the sun and the moon look bad.

**Tilt:** off. `phone.html` mounts with `tilt: false` (`?tilt=1` brings it back for comparison); no board or other page used it. The four depth slices stay: drift and the bakes use them. CLAUDE.md updated.

**Rays:** the 3.15 starburst (`sunOptics` in FINAL) draws 10 hairline spikes (`pow(cos, 600)`), ~12% of the screen long, plus 10 short ones; the moon got the same at `flare × (1 − .93·night)`, clearly visible at twilight (night .75: 30%). New `?rays=` switch (`SPIKES` in JS, `uSpk`/`uSpkS` in FINAL):
- **2 · Now:** the 3.15 starburst, unchanged (moon included).
- **1 · A soft glare** (default for now): a round glow with six broad, short petals (`pow(cos 3θ, 18)`, ~4% of the height, × .30). Reads as glare in the eye, not a camera lens.
- **0 · B none:** disc, bloom and halo only.
In A and B the moon has no spikes (`uSpk` × (1 − sstep(.25, .5, night))). Lens ghosts, light shafts and the silver rim are unchanged. Post-pass only (no bake change). Cost: two `pow` per pixel near the sun, same as before.

**Board:** `design-prototype/l1-board.html` (renders `l1/`, from `tools/visual-loop/l1.js`): 09:00, 13:00, 19:00, 19:45, 20:30, 23:00 × the three styles.

**Differs from the reference:** Samsung's sun has 8–12 thin spikes (CLAUDE.md "Light"). A or B departs from it on purpose.
**Picked (2026-11-22): A · soft glare** (`SPIKES` default 1; `?rays=2` keeps the old starburst for comparison, `?rays=0` none). The moon has no spikes. Phone (S21, `phoneG3.js` at 13:00 and 20:30): soft glare renders, no spikes on sun or moon, no GL errors, 54–60 fps, sky text still passes 3:1 with no veil (6.58 / 4.89). Still on the page by day: the two faint round lens ghosts under the cards (3.15).

## B1 · The weather of the body, and M1 · the mountain range (2026-11-22)
**Asked:** the body → sky mapping (PLAN-sky section 4, rounds 3.8 sky lab), and "work a bit on the mountains": the shape is wrong, it should be a range rather than two mountains, and the spikes on the right do not look organic.

**B1, mapping** (`design-prototype/body-sky.js`, `BodySky.bodySky(body, hour, SceneGL)`; pure, no state, ports to Kotlin): one layer per signal, thresholds against the person's own normal. Readiness → regime (`SceneGL.weather`, unchanged). Sleep score 78 → 50 or 20 → 100 min under the usual → morning valley fog (05–07 in, thinning from 10:30, gone by noon). HRV under the normal band → cirrus streaks, then a milky veil with a halo. Activity pace (steps against the usual for the hour, or strain) → cumulus build 10–19 h. Skin temp + RHR both up: +0.3 °C / +3 bpm haze; +0.5 °C / +5 bpm likely illness → light rain (D6); likely illness and readiness < 35 → storm (D2). No data → neutral fair. Returns the SkyState, the layer weights, the tap note (D4) and TalkBack text; notes only describe what the sky shows (under rain the HRV note is dropped).
- Example days (`b1-days.js`, rendered by `tools/visual-loop/b1.js` into `b1/`): good day, short sleep 07:30 and noon, HRV under / well under normal, heavy training 16:00, body under load, likely illness, likely illness with readiness 30, first run. Sky text with no veil 4.06–9.62 (storm lowest).
- Board `design-prototype/b1-board.html`: mapping table, live sky lab (sliders for hour, readiness, sleep, HRV, steps, skin temp, RHR; example-day buttons; tap the sky for the note; TalkBack text and SkyState readout), the gallery, the mountain before/after.

**M1, mountains** (`tCrest`, `MTN`): the T4/T5 massing (a broad bump plus a narrow second peak, a cos ripple) and its high-frequency ridged detail (`r1²` at 26) made two mountains with needle spikes. Now one long envelope (u 0 → ~.7) carved by a ridged multifractal at 15 (three octaves, each weighted by the coarser one, finer octaves `pow 1.5` so they make shoulders, not needles), warped and varied in height by one slow noise. Snow: an altitude line again (the peaks now differ in height, so the tall ones wear caps and the low ones stay bare), wandering along the range and down the gullies with softer streaks.
- Compile: the new crest first doubled the load to converged (29 → 57 s). Each layer's crest is now computed once per pixel and passed down (`tCoverC`, `tColor(…, c)`; the two slice loops share one cover pass): ~13 inlined crest copies → 7. One slow noise does warp and height; the fine-noise term is gone. Load **36.6 s** (G3 29.3 s).
- Re-baked `baked/today` (17 keyframes, 20.5 MB); baked vs live 47.9–48.7 dB. Phone (S21): ready 1.8 s, 60 fps, no GL errors, sky text passes 3:1 with no veil at 13:00 (6.66) and 20:30 (4.83).

**Still differs:** the snow on the main peak keeps a fine vertical streak texture; the storm's bolt is hard to see on stills; "body under load" (haze) is the subtlest state. Thresholds are proposals.
STOP: waiting for review of the mapping (thresholds, the look of each day) and the range.

## M2 · Rounder summits, a faint tilt (2026-11-22)
**Asked:** the mountains a little more round; the tilt is still too much (keep it, much smaller).
- **Summits:** the ridged octaves use a soft |.| (`sqrt(v² + k²) − k`, k .12 / .14 / .11) instead of `abs`, so each summit comes to a soft top rather than a point (a first try at k .17 on the big peaks read as a dome). Shape and snow otherwise as M1. Board `b1-board.html` shows `m1/i-*.png`.
- **Tilt:** back on in `phone.html` (`?tilt=0` holds it still), AMP `[3, 9, 14, 22]` → **`[1, 3, 5, 7]`** CSS px at full tilt (about a third). CLAUDE.md updated.
- Re-baked `baked/today` (17 keyframes, 20.6 MB); baked vs live 47.6–48.7 dB. Load to converged 40.6 s (M1 36.6 s; the four `sqrt` and run-to-run spread).

## M3 · A lower, rounder range (2026-11-22)
**Asked:** still adjust: the range a bit shorter and rounder still.
- Lower: envelope .078 → **.064** of the screen height; snow line scaled with it (`mtn` .074 → .061), so the caps stay on the tall peaks.
- Rounder: soft |.| k on the three ridged octaves .12 / .14 / .11 → **.19 / .17 / .15** (`sqrt(v² + k²) − k`).
- Board `b1-board.html`: the mountain section now shows M2 against M3 (`m1/i-*` vs `m1/k-*`); the gallery re-rendered.
- Re-baked `baked/today` (17 keyframes, 20.5 MB); baked vs live 47.0–48.7 dB. Phone not re-checked: the S21 disconnected from adb.

## M4 · A narrower range (2026-11-22)
**Asked:** the range is way too wide; make it 20 percent less wide.
- Envelope width .46 → **.368** (−20%), centre .32 → .26 so it still starts at the left edge: where the range is a quarter of its height it now ends at u ≈ .69 (was ≈ .86). Peak size and rounding as M3; fewer peaks.
- Board `b1-board.html`: M3 against M4 (`m1/k-*` vs `m1/l-*`); gallery re-rendered.
- Re-baked `baked/today` (17 keyframes, 20.3 MB); baked vs live 45.8–48.7 dB. Phone not re-checked (not connected).

## M5 · Layered ridges, after a photo reference (2026-11-22)
**Asked:** redesign the mountain simply, after a photo of layered blue alpine ridges (far ridges pale into the haze, nearer ones darker, the detail on the crest lines); keep the footprint.
- Same outline (M4 footprint and crest). Inside it, two lower ridges stand in front (`tColor`, mountain branch): heights .70 and .50 of the massing, soft-ridged with two octaves each, clamped under the outline. Each takes its own distance through the existing `tAir` (far 38 km, mid × .55, near × .35), so the tone steps come from the haze, at every hour and weather, with no new colours. Foot mist and crest light per ridge (crest light on the far ridge only). Snow on the far ridge only; no far sliver under 3 px where a front ridge reaches the outline.
- Board `b1-board.html`: M4 against M5 (`m1/l-*` vs `m1/p-*`); gallery re-rendered. Load to converged 19.0 s (one run; earlier runs today 36–57 s, so treat as noisy). Re-baked `baked/today` (17 keyframes, 20.3 MB); baked vs live 47.1–48.8 dB. Phone not re-checked (not connected).

## M6 · The range shaped after the photo, no layering (2026-11-22)
**Asked:** M5 looked very bad: use the reference photo for the shape, discard the layering.
- M5's front ridges removed. The mountain's massing (`tMass` for the mountain layer) is now five designed peaks, `PEAKS` = [u, height, left slope, right slope] (slopes .24–.50, ~30–48° on this screen), joined by a ridge at half the envelope so it reads as one range: straight flanks, V notches, an uneven main peak centre-left, M4 footprint (u 0 → ~.67, top .064). New uniform `uPk[5]`.
- `tCrest` adds the photo's broken edge: fine ridged teeth on the crest (amp .0035, freq 70 and 161, none at the foot) and a slow ridged octave that puts shoulders on the flanks (± 12% of the height). Slope light now follows the peaks (lit and shaded flanks). Snow: the altitude line, on the three tall peaks.
- Board `b1-board.html`: M4 against M6 (`m1/l-*` vs `m1/s-*`). Load to converged 17.1 s. Re-baked `baked/today` (17 keyframes, 20.2 MB); baked vs live 47.8–48.7 dB. Phone not re-checked (not connected). CLAUDE.md terrain note updated.

## M7 · Lower peaks, higher snow line (2026-11-22)
**Asked:** the peaks a bit too high, the snow line too low.
- `PEAKS` heights × .85 (main .064 → .054). Snow line `mt.x·(1 − .55·snow)` → `(1 − .40·snow)`: in late November only the main peak's top keeps snow; the season still moves it (deep in winter, gone in summer).
- Board `b1-board.html`: M6 against M7 (`m1/s-*` vs `m1/t-*`); gallery re-rendered. Re-baked `baked/today`; phone not re-checked (not connected).

## M8 · Mountain removed (2026-11-23)

**Asked:** remove the mountains altogether.

**Changed:** `MTN` dropped from `LAND.layers` in `scene-gl.js` (the shader already loops over `uNL` layers, so the two low ranges become layers 0 and 1; `uLm` is all zero, the peak branch never runs). `MTN` and `PEAKS` are kept in the file, unused, so the M7 range can come back with one line. CLAUDE.md terrain note updated.

**Measured:** shots `m1/u-*.png` (13:00, 18:30, 23:00): only the two long low ranges on the horizon, no seam where the mountain was. Rebaked `baked/today` (17 keyframes, 21.5 MB); baked vs live 47.3–48.3 dB PSNR. B1 gallery re-rendered. Board: `b1-board.html` mountain section shows M7 vs M8.

**Not checked:** phone (not connected), load time.

**Picked (2026-11-23):** keep the horizon without the mountain.

## X1 · Sky transitions (PLAN-sky 3.9, 2026-11-23)

**Asked:** continue the redesign with transitions: how the sky changes when data syncs or the hour moves.

**Built:** `set({ ..., transition: 'fade' | 'front' | 'patches' })` in `scene-gl.js`. On set, the canvas's last frame (it keeps its buffer) is copied into one 8-bit target `RT.o`; after post, one small pass (`XF`) lays it over the new picture with an alpha per pixel. The old picture **holds until the new one is complete** (live: 24 frames converged; baked: both keyframes decoded), so no half-drawn frame shows, then gives way:
- **A · Fade** 1.8 s, eased.
- **B · Front** 3.5 s, even pace: the new weather crosses from the left behind a soft, cloud-torn edge (fbm).
- **C · Patches** 3 s, even pace: the new sky opens in cloud-shaped patches (fbm spread to 0..1).
- Reduced motion: any style becomes a 1.2 s fade. The hour: `phone.html ?device=1` now sets the clock hour once a minute with a fade (invisible step; hides the live re-accumulation).
- Cost: one full-screen pass only while a transition runs; one 8-bit canvas-sized target (~3 MB at 824×1830). No shader change to the scene programs (no compile cost).

**Cases** (`x1-cases.js`, from the B1 days through `BodySky`): morning sync (no data → short sleep, 07:30), signs of illness (good → light rain, 13:00), readiness recalculated (61 → 86, 13:00). Frames at 0/25/50/75/100% shot by `tools/visual-loop/x1.js` into `x1/`. First run: front and patches did most of their change between 25 and 50%; retuned to an even pace.

**Measured:** live in the pane, illness case, front: old picture held 2.3 s while the new sky converged, then 3.5 s run, done at 5.4 s. Baked (`phone.html?baked=today&h=13`, 86 → 58): new keyframes decoded in < 0.2 s, run done at 3.6 s. No console errors.

**Not done:** the hero number and the glass do not animate yet (the number swaps at once; the glass follows the palette). Morph (gliding SkyState so clouds really grow and fog really lifts) left out: it re-renders the sky every step, so it is live-only and cannot run on baked phones (API 26–32). Phone not tested (not connected).

**Board:** `x1-board.html` (play each case in each style, frame strips). STOP for the pick.

**Picked (2026-11-23):** A · Fade (1.8 s, eased) for data syncs; the hour tick already uses it. Front and patches stay in `XF` for comparison only.

## S1 · Sleep on the scene (2026-11-23)

**Asked:** continue the redesign with the Sleep screen (second by importance in the brief).

**Shipped today (control):** a graphite Sleep Detail (one night: "You slept", score gauge, stages chart, time in each stage vs normal, "Is this right? Looks right / Edit") and Sleep Trends (duration, bedtime → wake, score, stage mix by week).

**Built:** `s1-board.html`, three directions on the same scene, glass (G2) and type as Today. Same content in all three: duration as the hero (number, word from the sleep score, detail vs usual), the night's stages, time in each stage vs normal, and the times check. Trends continue below the fold (not drawn this round).
- **A · Last night:** the sky at bedtime (23:10), moon and stars, dark land glass, light ink. Stages as labelled rows (position carries the stage, not colour) with a faint step line.
- **B · This morning:** the sky at the wake time (06:30). After a short night the valley mist of the body mapping is there too (shown with the `valleyFog` preset), so Sleep and Today show the same morning. Day glass, dark ink.
- **C · The night drawn:** the night sky with the whole night as one light line across it (low = deep sleep, high = awake), the number smaller; the first screen also holds the last 7 nights.

**Measured** (`g2.js` now takes `STATES` and `RECTS`; renders and values in `s1/`, `s1c/`), floor 3.0, no veil anywhere: bedtime cards 9.21, sky text 4.59, number 4.06; wake 06:30 cards 5.32, sky 7.47; wake with mist cards 5.36, sky 5.43; C's night line over the sky 4.11, its smaller number 4.89.
- **Found:** with the number as wide as "7h 20m" at 89 px, its box reaches the moon (x ≈ 300) and needs a 37% veil at night. The hero is 72 px here (box to x 260). Also seen: at 02:30 the moonlit cloud is as bright as at 23:00 in this seed; the narrow box clears it.

**Not done:** trends below the fold, the Today ↔ Sleep tab change, very early wake times in B (night sky), phone check (not connected).

**Board:** `s1-board.html`. STOP for the pick.

**Picked (2026-11-23):** A · Last night. Asked for next: an indication of the time in the stages chart (non-intrusive, e.g. hour lines), and switching between nights with back/forward buttons (from C's idea of other nights).

## S2 · Sleep: hours in the night, and other nights (2026-11-23)

**Asked:** A, with a non-intrusive indication of the time in the stages chart (e.g. hour lines), and back/forward buttons to switch nights.

**Built:** `s2-board.html` (A only, the phone is live: tap the buttons).
- **Hours:** a 1 px line at each full hour (ink at 16%, behind the stage bars) and the hour under it as two digits (00 … 06, 10.5 px, secondary ink). Bedtime and wake stay in the card's corner. The stages card grew 20 px; the stage list below it is 20 px shorter.
- **Other nights:** back and forward, 48 dp circles at the top right (the slot Today uses for Log weight), ink at 12% with a 28% ring. Forward is dimmed (not hidden) on last night, back on the oldest of 30. An older night takes its date as the title and "N nights ago" in the detail line; every number, the stages, the stage totals vs normal and the times follow it. TalkBack: "Earlier night", "Later night".
- Prototype data: only last night has stages; older nights are made from it, scaled to each night's real sleep time with a varied bedtime. Last night's wake time now follows its stages (23:10 – 06:52: 7 h 20 m asleep + 22 m awake), not the 06:30 of S1.

**Measured** (floor 3.0, no veil): back/forward icons over the sky 7.93, cards 9.21 (weakest text, the hour labels use it), sky text 4.59, number 4.07. Shots: `tools/visual-loop/s2.js` → `out/s2-last.png`, `s2-back1.png`, `s2-nov4.png`.

**Not done:** the sky does not change between nights (a night's own bedtime sky and seed would come from the renderer), swipe between nights, trends below the fold, phone check.

**Board:** `s2-board.html`. STOP.

**Feedback (2026-11-23):** hour lines and night buttons approved; the faint line joining the stage bars removed (bars only).

## S3 · Sleep: trends below the fold (2026-11-23)

**Asked:** design the trends below the fold of the Sleep tab.

**Built:** `s3-board.html` (A from S2; both phones scroll and work). The scene scrolls away; the page keeps its night colour under the cards (sampled from the scene's bottom row, #0E1A55-ish). Under "Last 30 nights", four glass cards in the same ink, each replacing a shipped Sleep Trends chart:
- **Sleep:** 30 bars from 4 h, hour grid 5–8 h, your normal as a faint band (mean ± 1 sd), usual in the header.
- **Bedtime and wake:** one bar per night from falling asleep to waking, on a clock 22:00 → 09:00. Nights asleep after midnight are **hollow** (outline), not red as shipped, and the header counts them: shape carries the meaning, not colour.
- **Sleep score:** 30 bars from 40, normal band.
- **Stages by week:** four weeks, deep / REM / light as one segmented bar each (ink strength by stage), the shares written above.
- **One selection:** the night shown above is the bright bar in every chart and its week the bright row. Tap a night in any chart to open it (tested: tapping 4 Nov opens it and moves every highlight); back/forward move it too. Charts are hidden from TalkBack; each gets a one-line text summary when built.

**Measured** (ink over the glass on the sampled page colour, sRGB blending as CSS): night shown 14.36, other nights and the hollow outline 5.24, weakest stage segment 3.33 (first try 2.67 failed: light segment raised .40 → .46, other weeks dimmed .85 not .80), secondary text 9.84; grid lines 1.59 and the band 1.32 are decoration. Shots: `tools/visual-loop/s3.js` → `out/s3-full.png`, `s3-trends.png`, `s3-trends-nov4.png`.

**Prototype data:** older nights' bedtimes spread wider (±70 min) so late nights occur (10 of 30). Bars are 9.8 px wide: the tap target is the chart column; a list view for TalkBack and larger targets is for the build.

**Not done:** phone check, the Trends tab itself (the shipped Trends now overlaps this), swipe between nights.

**Board:** `s3-board.html`. STOP.

## S4 · Sleep: easier trends (2026-11-23)

**Asked:** the bedtime/wake and sleep charts were hard to read; make sleep a graph instead of columns; bedtime and wake can stay or be redesigned.

**Built:** `s4-board.html` (two scrolled phones, A and B, both live).
- **Sleep:** one 2 px line through the 30 nights, a dot and a dotted guide on the night shown, the normal band behind. Scale 5 h 30 → 8 h 30 (the range the nights use; first try 4 → 9 h left the bottom half empty).
- **Bedtime and wake, A · Two lines:** falling asleep and waking as two lines on a clock 21:30 → 09:00, the night filled faintly between them, nights asleep after midnight as hollow dots on the bedtime line (counted in the header), dots on both lines for the night shown, "asleep" / "awake" at the right end.
- **Bedtime and wake, B · Bars:** S3 unchanged, for comparison.
- Score and stages by week unchanged.

**Measured** (ink over the glass on the page): lines and dots 11.82, secondary text 9.84, other-night bars 5.24, weakest stage segment 3.33; grid 1.59, night fill 1.59, band 1.32 are decoration. Tap to open still works (tested on 4 Nov: the dots and guides move to it). Shots: `tools/visual-loop/s4.js` → `out/s4-a.png`, `s4-b.png`, `s4-a-nov4.png`.

**Board:** `s4-board.html`. STOP for the pick (A two lines / B bars).

**Picked (2026-11-23):** B · Bars for bedtime and wake (S3 bars, late nights hollow), with sleep as the S4 line. Score and stages by week as S3. The Sleep tab is: last night (S2) + these trends (`s4-board.html`, phone B).

## TR1 · Trends on the scene (2026-11-23)

**Asked:** push, then continue to the Trends tab.

**Shipped today (control):** graphite list, one row per metric (HRV, resting HR, sleep, readiness, steps, strain, blood oxygen, skin temp, then weight) with a 30-day sparkline; today's value in red when outside normal; Calendar button; each row opens the metric detail.

**Built:** `tr1-board.html`, three layouts on the same sky as Today at this hour (15:10, fair), G2 day glass. Common: header "Trends · Last 30 days · against your normal", Calendar as Today's top-right button, and in the hero's place a **count**: "5 of 8 within your normal", then which are outside. Outside normal is a word ("Above normal", "Ahead of your pace") plus a ring round today's dot, never colour alone. Steps are judged against the usual pace for the hour (brief rule), so 8,420 at 15:10 is "ahead of your pace". The Sleep row opens the Sleep tab (its charts are not drawn twice).
- **A · List:** the shipped list on one glass panel, 56 dp rows: name, value (+ the word when outside), 30-day line with the normal band, chevron.
- **B · Tiles:** a 2-column grid of 8 tiles (value large, line under it, a short tag when outside); everything above the nav.
- **C · Outside first:** each metric outside normal as a card with its normal written out and a full 30-day line (days outside the band as hollow dots); the rest in a compact "Within your normal" list below (below the fold with three outside).

**Measured** (`g2.js` on `tr1/day1510.png`, floor 3.0): card text 5.57, sky text 6.85, number 7.04, Calendar button from the G2 rule, no veil; marks over the glass on the worst page band: lines, dots and rings 6.18, hollow dots 8.75; dividers and the band 1.19 (decoration). Shots: `tools/visual-loop/tr1.js` → `out/tr1-0..2.png`.

**Not done:** weight (no prototype data yet), the metric detail and Calendar, night state, phone check.

**Board:** `tr1-board.html`. STOP for the pick.

**Picked (2026-11-23):** A · List, with colour added for good or bad when a metric strays from your normal (the word stays: colour never alone).

## TR2 · Trends list with good and bad (2026-11-23)

**Asked:** the list (A), with some colour to show good or bad when a metric strays from baseline.

**Built:** `tr2-board.html`. Per metric, a direction sense: good = HRV up, resting HR down, more sleep, readiness up, steps ahead of pace; bad = the opposite, skin temperature up, blood oxygen down; neither = strain (stays in ink, word only). When a metric is outside its normal, the colour goes on the arrow + word ("↑ Above normal"), today's dot and its ring, and a 3 px bar at the row's left edge. The hero says it in words ("… better than usual", "… need a look"; past three, "6 numbers need a look today"). Colour is never alone: the arrow and word carry the meaning.
- Colours per glass: day green `#17703C`, coral `#B23A2E`; night green `#7BE3A4`, coral `#FFA095`.
- Shown on today at 15:10 (three changes, all good) and on 4 Nov, a poor day, at 23:10 on night glass (six bad, strain neither).

**Measured** (colour against the card on the worst page band, floor 3.0): day good 3.66, day bad 3.54, night good 8.78, night bad 7.04. Shots: `tools/visual-loop/tr2.js` → `out/tr2-0.png`, `tr2-1.png`.

**Board:** `tr2-board.html`. STOP.

**Approved (2026-11-23):** the good/bad colour as built.

## CO1 · Coach on the scene (2026-11-23)

**Asked:** commit Trends, then the Coach tab.

**Shipped today (control):** graphite chat: header with a recovery chip, three suggested questions, a text field with Ask, answers of three lines at most grounded in your own normal, and "Ask in Claude Pro App ($0 API)" which copies 14 days of metrics into the Claude app.

**Built:** `co1-board.html`, on the same sky as Today at 15:10 (G2 day glass). In the hero's place: **the coach's read of today in one sentence** ("A good day to train hard.", 36 px light) and the numbers it rests on under it. Every answer ends with a line naming its source ("From HRV and readiness, this morning"). Your questions are blue bubbles (the action colour), the coach's are glass. The handoff stays as a quiet link ("Ask in the Claude app ↗ copies your last 14 days").
- **A · Chat on the sky:** the conversation under the horizon, chips and the field docked above the nav.
- **B · Briefing, then a sheet:** opens on "Today's read" (readiness, HRV, sleep with their comparison) and the questions as chips; asking raises a glass sheet over the sky holding the conversation, with the read kept small above it.

**Measured** (`g2.js`, `co1/measure.json`, floor 3.0, no veil): sky text incl. the sentence over the sun's glare 6.85; glass text 5.57 below the horizon and 3.74 where B's sheet covers the land and water (same 22% glass); send button from the G2 rule. Shots: `tools/visual-loop/co1.js` → `out/co1-0..2.png`.

**Not done:** night state, typing/keyboard state, error and offline states, phone check.

**Board:** `co1-board.html`. STOP for the pick.

**Picked (2026-11-23):** A · Chat on the sky.

## SE1 · Settings on the scene (2026-11-23)

**Asked:** commit Coach, then Settings.

**Shipped today (control):** a long graphite page: Profile (name, height, weight), Band (status, battery, background sync, Pair as companion, Sync now, Forget band), Backup to home server, Recent syncs, Units, Data (export), Morning summary, Bedtime reminder, About.

**Built:** `se1-board.html`, same sky (15:10) and G2 day glass. Every setting kept, grouped (Band, You, Reminders, Data; About follows), rows ≥ 48 dp, Forget band last in its group and asks first. **Placement:** the new nav has four tabs (Today, Sleep, Trends, Coach), so Settings is no longer a tab; proposed: a gear at the top of Today opens it as a page with a back arrow (drawn here, no tab lit). The prototype uses a placeholder name.
- **A · Band as the hero:** battery "61%", "Band connected", last and next sync; Sync now as the top-right button; groups under the horizon, scrolling.
- **B · List over the sky:** no hero; the grouped list starts under the header over the sky, the band row holding battery and Sync now; four groups on the first screen.

**Measured** (`g2.js`, `se1/measure.json`, floor 3.0, no veil): glass text 3.75 at its weakest (B's cards over the sky and the tree crown, 22% glass), sky text 6.83, number 7.04, Sync now vs sky 3.54. Shots: `tools/visual-loop/se1.js` → `out/se1-0.png`, `se1-1.png`.

**Not done:** band disconnected / low battery hero states (A), the gear on Today, night state, phone check.

**Board:** `se1-board.html`. STOP for the pick.

**Picked (2026-11-23):** A · Band as the hero (Settings opened from a gear on Today).

## MC1 · Morning check on the scene (2026-11-23)

**Asked:** design the Morning check screen (last in the brief's order).

**Shipped today (control):** graphite flow: Intro ("Lie still, 60 seconds", Begin), Measuring (countdown ring, live HR), Result (updated recovery gauge, HRV and resting HR with chips, Done), plus "Couldn't save" and "Check failed".

**Built:** `mc1-board.html`, the 07:10 sky (renders in `mc1/`), G2 day glass, three steps per direction. Tied to the scene: the check updates readiness (78 from last night → 82 with this morning's HRV), so the sky goes from partly cloudy to fair with the X1 fade, and the result says it in words ("78 → 82"). No ring, no gauge.
- **A · The check on the sky:** its own page with ✕; the hero is the countdown ("60 s · Lie still", then "42 s · Keep still"), the 60 s run as a 3 px line along the shore; live heart trace in a glass card; result leads with HRV ("64 ms · Above your normal"), then readiness and HRV against the normal band.
- **B · A sheet over Today:** Today stays; a glass sheet rises with Begin / Not now, holds the countdown, progress and trace, then lowers while Today's own hero changes 78 → 82.

**Measured** (`g2.js`, `mc1/measure.json`, floor 3.0, no veil): glass text 5.45 (partly and fair, incl. B's sheet over land and water), sky text 6.58 / 7.28, hero number 5.69 / 7.62. Shots: `tools/visual-loop/mc1.js` → `out/mc1-0..5.png`.

**Not done:** failed and couldn't-save states, the entry point on Today, night/early-dark mornings, phone check.

**Board:** `mc1-board.html`. STOP for the pick.

**Picked (2026-11-23):** B · A sheet over Today (the check rises over Today; Today's own hero changes 78 → 82 as the sky clears).

## MC2 · Morning check: the rest of B (2026-11-23)

**Asked:** continue with B (the sheet over Today).

**Built:** `mc2-board.html`, nine phones; Today's cards now drawn behind the sheet. Rule: readiness and the sky change only when a check is **saved**; a failed or unsaved check leaves 78 and the partly cloudy sky, and says so.
- **Starting it from Today (pick one):** E1 · a card on the lip ("Morning check · 60 s lying still · updates readiness", Begin; the cards below move down 76 px) or E2 · a blue button under the hero ("Morning check · 60 s"). Before the check the header line reads "Morning check not done"; after it, "✓ Morning check 07:11 · HRV 64 ms ›", which reopens the result (Redo there), and the entry is gone until tomorrow.
- **When it goes wrong:** "No clean reading" (cause, how to wear the band, readiness stays 78; Try again / Not now), "Measured, not saved" (keeps 64 ms, no need to measure again; Retry save / Later: retry on the next sync, new behaviour: shipped forgets it on Done), "Band not found" (before it starts).
- **Before sunrise, 05:20:** the same sheet on night glass (land tint, light ink, white button); measuring, result (78 → 82, the low cloud clears from the night sky), and Today after.

**Measured** (`g2.js`; 07:10 from `mc1/`, 05:20 in `mc2/measure.json`, floor 3.0, no veil): day glass text 5.45, sky 6.58, number 5.69; night glass text 5.69–5.76, sky 4.53–4.57, number 5.12–5.14. Measured on the scene alone; the cards behind the sheet only add glass under it. Shots: `tools/visual-loop/mc2.js` → `out/mc2-0..8.png`.

**Not done:** phone check; the result reopened from the header (with Redo); TalkBack labels for the live trace.

**Board:** `mc2-board.html`. STOP for the entry pick (E1 / E2).

**Picked (2026-11-23):** E1 · the card on the lip (Begin), until the check is saved; then the header line holds it.

## WT1 · Log weight (2026-11-23)

**Asked:** design Log weight (one tap from Today, per the brief).

**Shipped today (control):** graphite bottom sheet: decimal field prefilled with the last weight and selected, unit suffix, Date button (any day in the series), "Replaces 64.2 kg" + Update when the day has an entry, range check 30–300 kg, save error with Retry, "Saved … · Undo" snackbar.

**Built:** `wt1-board.html`, a glass sheet over Today at 07:15 (after the morning check, `wt1/am0715f.png`), every shipped rule kept.
- **A · Type it:** the shipped field with the system number keyboard; Date chip and Save in one row above the keyboard. Earlier day: the prefill selected, "Replaces 64.2 kg, logged on 17 Nov", Update.
- **B · Nudge it:** no keyboard; opens on the last weight; drag a ruler (0.1 kg ticks) or − / + (48 dp); the change since last time shows as you move ("−0.3 since 17 Nov"); tap the number to type it (A's keyboard).
- **After saving (both):** a solid-ink snackbar "Saved 63.9 kg for 23 Nov · Undo" (glass melted into the cards under it), and Today's top-right button shows "✓ 63.9 kg" for the rest of the day (tap to change).

**Measured** (`g2.js`, `wt1/measure.json`, floor 3.0, no veil): sheet glass text 3.79 at its weakest (the taller sheet reaches the land and water), sky text 7.13, number 7.55; snackbar white on #0E2A43 at 94%.

**Not done:** night state, lb, the range error and save-error states drawn, phone check.

**Board:** `wt1-board.html`. STOP for the pick.

**Picked (2026-11-23):** B · Nudge it (ruler and − / +, tap the number to type), with the solid snackbar and the "✓ 63.9 kg" button after saving.

## MD1 · A metric's page, and the Calendar (2026-11-23)

**Asked:** continue (open items after Log weight: the Trends metric page and Calendar).

**Shipped today (control):** metric detail: back and name; hero card (today's value, Normal chip, insight chip); Week / Month / 3M / Year; chart card (picked day's value and chip, smoothed line, daily dots, outliers, tap to pick); how the score is made (score metrics); 30-day average. Calendar: a 14-day grid with a coloured dot per day; tap opens that day's Today.

**Built:** `md1-board.html`, HRV on today's sky (15:10, `md1/day1510.png`), G2 day glass, TR2 good/bad colours (with arrow and word). Data: last 30 days of `data.js` (today 64 ms, normal 55–63).
- **A · Chart in a card:** hero = today's value and word; range control in glass; one chart card with a light grid (labels outside the plot), band = your normal, 3-day line, daily dots, days outside ringed in their colour; picking a past day adds "Open 4 Nov". The About card did not fit above the nav and is dropped (the shipped page has none).
- **B · The line on the sky:** the 30 days drawn under the number on the sky itself (x 22–236, clear of the trees), band and ringed outliers; cards hold the picked day ("lowest in 30 days", Open 4 Nov), the average and About HRV.
- **Calendar:** a month sheet over Trends; each day carries its sky as the week-strip glyph from its readiness (sun 80+, sun and cloud 65–79, cloud 50–64, grey cloud under 50); shape carries it, not colour. Tapping a day opens it in Today's layout under its own sky (4 Nov = stratocumulus, `md1/nov4.png`) with "Back to today".

**Measured** (`g2.js`, `md1/measure.json`, floor 3.0, no veil): glass text 3.75 (sheet reaching the land), sky text incl. B's line 5.55, number 7.04; past day (stratocumulus) glass 3.29, sky 5.07.

**Found:** `data.js` ends on 12 Nov while the other screens say 23 Nov; the Calendar follows the data.

**Not done:** Week / 3M / Year ranges drawn, other metrics (score metrics' "how it's made", weight with Log), night state, phone check.

**Board:** `md1-board.html`. STOP for the pick (A / B; the Calendar is one proposal).

**Picked (2026-11-23):** A · Chart in a card, with a Today range added to Week / Month / 3M / Year.

## MD2 · A metric's page: Today to Year (2026-11-23)

**Asked:** A (chart in a card), and add a Today range to Week / Month / 3M / Year.

**Found:** the band measures HRV (and HR, SpO2, temperature) on the sync interval (30 min, `buildSetAutoIntervalFrame`); a day's HRV is the mean of that day's readings (`DailySeriesRepository`), the morning check when there is one. So a Today range can show the readings themselves.

**Built:** `md2-board.html`, five phones (one per range), same page and sky (15:10). The range control has five segments (76 px each, ≥ 48 dp); the hero stays today's value in every range, the chart card and the average card change.
- **Today:** the readings every 30 min from midnight to now (prototype data), asleep shaded (runs higher), the morning check as a marked dot ("check 64", used for readiness), the rest of the day faded after a "now" line; average so far in the header; "Asleep and awake" averages below. No single reading is marked outside normal: the normal is for the day's value (said on the card).
- **Week:** raw daily values with each day's number over its dot (shipped: no smoothing); 7-day average and how many days were outside normal.
- **Month:** MD1 A (3-day average).
- **3M:** 90 days, 7-day average, month labels; only days outside normal get a ring.
- **Year:** weekly averages placed on the year (J–D), the months before the band started shaded "no data yet"; "Average this year, since 15 Aug".

**Measured:** as MD1 (`md1/measure.json`): glass text 3.75, sky 5.55, number 7.04; green / coral on the card 3.66 / 3.54 (TR2). Shots: `tools/visual-loop/md2.js` → `out/md2-0..4.png`.

**Not done:** other metrics (score metrics' "how it's made", weight with its Log button, skin temperature as a difference), night state, phone check. The Calendar (MD1) is still unanswered.

**Board:** `md2-board.html`. STOP.

**Approved (2026-11-23):** the five ranges as built.

**Calendar (MD1) approved (2026-11-23):** the month sheet over Trends with each day's sky glyph; a tapped day opens under its own sky with "Back to today".

## NT1 · The new screens at night (2026-11-23)

**Asked:** continue (open item: night versions of the screens drawn by day only).

**Built:** `night.js` (shared): any of `co1`, `se1`, `wt1`, `md2` boards opened with `?night=1` draws its screen at 23:10 (fair, `nt1/night.png`) on G2 night glass: land hue #152256 at 22%, saturate 1, faint light edge, light ink, white buttons with dark ink, TR2 night green/coral. Day versions are unchanged (shots byte-identical). `nt1-board.html` shows the picked designs at night: Coach (CO1 A), Settings (SE1 A), Log weight (WT1 B) and after saving, HRV Today and Month (MD2). Shots: `tools/visual-loop/nt1.js` → `design-prototype/nt1/*.png`.
- Settings' on-toggles take a lighter blue (#5B8FD6) at night (the button colour is white there); the weight snackbar turns light; the metric page's "after now" slab darkens instead of lightening.

**Measured** (`g2.js`, `nt1/measure.json`, floor 3.0; rects = all four screens' cards, sheets and nav): glass text 6.96, sky text and numbers 4.11 with no veil, white button 8.62.
- **Found:** with Coach's sentence box out to x 380 the moon falls inside it and the sky text needs a 37% veil (3.36). Sky text kept left of the moon (x ≤ 262, as S1) needs none. Rule: at night all sky text boxes end at x 262 (`SKY_RIGHT`); Coach's sentence wraps to two lines, Settings' detail line is shortened.

**Not done:** Coach's line at night still reads as a day plan ("A good day to train hard"); dusk and dawn states of these screens; phone check.

**Board:** `nt1-board.html`. STOP.

**Approved (2026-11-23):** the night versions as built, with the x 262 rule for sky text at night.

## MD3 · Three more metrics (2026-11-23)

**Asked:** continue (open item: the other metrics' pages).

**Built:** `md3-board.html`, the approved page (MD1 A + MD2 ranges, Month shown) for metrics that work differently from HRV; also opens with `?night=1`.
- **Readiness** (a score): hero 86 with "↑ Above normal" and the normal 72–85; a shorter month chart (30-day average in its header); "How today's 86 is made": the shipped parts (`readinessParts`: HRV vs your average 40%, resting HR 25%, sleep 25%, blood oxygen and skin temp 10%) as one bar split by points (34 / 22 / 21 / 9, the shipped `shownPoints` rounding), ink strength per part and every part named with its reading.
- **Weight** (logged): no band and no good/bad; entries as dots with a 7-day average through them; hero "63.9 kg · −0.9 kg since 14 Oct · Latest today 07:15"; the top-right button is WT1's ("✓ 63.9 kg"); the picked day shows Edit · Delete on an entry, "No entry · Log" otherwise (as shipped `WeightDayActions`); 30-day average "used for calories".
- **Skin temperature** (a difference): hero "+0.1 °C · Near your average · 36.4 °C vs your average 36.3"; bars up and down from zero per night, band ±0.3, a night outside it hollow in coral either way (closer to zero is better); a card "Not core body temperature" that ties a rise with resting HR to the hazy sky (D6).

**Measured:** as MD1 (glass 3.75, sky 5.55, number 7.04); part swatches are decoration (every part is named). Shots: `tools/visual-loop/md3.js` → `out/md3-0..3.png`.

**Not done:** Daily score, Recovery and Sleep score pages (same pattern as Readiness), steps against pace, blood oxygen; phone check.

**Board:** `md3-board.html`. STOP.

**Approved (2026-11-23):** the three pages as built (Readiness with its parts, Weight, Skin temperature).

## CO2 · Coach through the day (2026-11-23)

**Asked:** continue (open item: Coach's line at night still read as a day plan). Phone not connected (adb lists no device), so the device check waits.

**Built:** `co2-board.html`, CO1 A at four hours, each on its own sky and glass (07:15 `co2/am0715.png`, 15:10 `tr1/day1510.png`, 19:45 `co2/dusk1945.png`, 23:10 `nt1/night.png`). The read, its detail, the opening exchange and the two chips follow the hour:
- 07:15 the plan: "A good day to train hard." (readiness, HRV).
- 15:10 progress: "Ahead of your usual day. Keep it going." (steps by now, strain vs usual).
- 19:45 recovery: "A hard day done. Ease off from here." "Will I recover by tomorrow?" answered from strain, HRV and usual sleep.
- 23:10 bedtime: "Bed by 23:30 tonight." (usual sleep time); "How did today go?". First try ran to three lines left of the moon; rule: the read is one sentence, at most two lines.

**Measured** (`g2.js`, `co2/measure.json`, floor 3.0, no veil): 07:15 glass 5.66, sky 7.14; 19:45 white glass at 26%, glass text 3.16, sky 3.33 (dark ink; the send button and your bubbles are white at dusk, by the G2 button rule); 23:10 as NT1 (glass 6.96, sky 4.11 with the sentence left of the moon).

**Not done:** the rules that pick the line (thresholds, what counts as "ahead"), a poor day's lines, phone check.

**Board:** `co2-board.html`. STOP.

**Approved (2026-11-23):** the read following the hour, as built.

## MD4 · The last metrics (2026-11-23)

**Asked:** continue (open item: Daily score, Recovery, Sleep score, steps against your pace, blood oxygen).

**Found (shipped):** Daily score = readiness 42% + sleep score 36% + strain 22% (`dailyParts`); Recovery = HRV × 0.92 + (90 − resting HR) × 0.72, today's values only (`recoveryParts`), not against your averages; Sleep score = duration 40% (full at 7h 30m), deep + REM share 20% (full at 38%), efficiency 10%, regular timing 15%, asleep by 02:00 15% (`sleepScoreParts`, staged night; capped at 70 when asleep after 03:00). A metric's normal is the 30 days before today, mean ± max(sd, floor): scores ± 3, SpO2 ± 1, steps ± 10% of the mean (`MetricInsights.baseline`). No step goal or pace exists in the app; the band stores step records with times, so "usual by this hour" can be built from them.

**Built:** `md4-board.html` (also `?night=1`), six phones on the MD2 page:
- **Daily score, Recovery, Sleep score:** Readiness's pattern (MD3): month with your normal, then "How today's N is made", one bar split by points (shipped `shownPoints` rounding) and a row per part with its reading. Daily score notes that strain still adds through the day. Recovery says why it can differ from Readiness. Sleep score's chart is shorter (60 px) so its five parts fit above the nav.
- **Steps, Today** (opens here): the running total against your usual day by the hour (dotted, with a band for most of your days), "now", and the rest of today drawn as usual: "about 9,600". Hero "5,370 steps · ↑ Ahead of usual · Usually 4,100 by 15:10 · 1,270 ahead". At night the clock is 23:10 and the projection label is dropped (the day is done).
- **Steps, Month:** full days as bars with your normal; today as a solid "so far" bar inside a dashed outline to the projection.
- **Blood oxygen, 3M** (low nights are rare; none in the last 30 days): 7-day average, your normal as the band, the 95% line Today's alert uses; only nights under 95% are ringed (the ± 1 band alone ringed 15 nights at 96%, noise). The 93% night is picked with "Open 6 Oct" and that skin temp and resting HR were up too.

**Measured:** same layout and screen areas as MD1/MD3, so the MD1 and NT1 measures hold (not re-run): day glass 3.75, sky 5.55, number 7.04; night glass 6.96, sky 4.11 (sky text kept left of the moon). Green today line and coral 95% line are non-text and always named in words.

**Found:** `data.js` sets today's Daily score to 84 by hand; the shipped parts give 83 (86 × .42 + 82 × .36 + 78 × .22), so the page shows 83. Last night's sleep parts (27% deep + REM, 85% efficiency, midpoint 2h 06m late, asleep 00:40) are made up to give the data's 82.

**Not done:** dusk/dawn versions of these and the earlier new screens; the steps-by-hour curve from real step records; phone check (no device on adb).

**Board:** `md4-board.html`. STOP.

**Approved (2026-11-23):** the five pages as built (Daily score, Recovery, Sleep score with their parts; Steps against your usual pace, Today and Month; Blood oxygen on 3M with the 95% line).

## DD1 · The new screens at dawn and dusk (2026-11-23)

**Asked:** continue with dusk and dawn (open item: Settings, Log weight and the metric pages were drawn by day and at night only).

**Built:** `night.js` takes `?t=dawn` (06:30, the dawn keyframe) or `?t=dusk` (19:45, Coach's dusk in CO2) besides `?night=1`; `se1`, `wt1`, `md2`, `md3`, `md4` boards follow it. `dd1-board.html` shows Settings, Log weight (and after saving), HRV Month, Readiness, Steps Today and Blood oxygen at both hours; shots `tools/visual-loop/dd1.js` → `design-prototype/dd1/<t>-<name>.png`. Day shots and the other night shots are byte-identical to before; `nt1/settings.png` is re-shot for the sync times.
- **Glass:** both hours keep the white day glass and dark ink. `g2.js` gained `MODE=light|dark` (force the glass) and records `needLight` / `needDark`; over all three screens' glass areas (x 12–400, y 286–851, and the nav) dusk needs 32% either way, so the day look wins: dawn 22%, dusk 34%.
- **Buttons:** the button over the sky follows the measured sky button (dawn blue, dusk white with dark ink); buttons and toggles on the glass stay blue. Before, Settings' toggle went white at dusk and Log weight's header button stayed blue.
- **Good/bad words at dusk:** TR2's green and coral fell to 2.35 / 2.27 on the dusk card, so dusk uses `#0E5A2C` / `#8A2A20`; dawn keeps the day pair.
- **Hour fixes:** Log weight's morning check reads 06:24 at dawn (it said 07:11, after the clock); Steps before the day has started (usual by now under 500) says "Your day is starting · A full day is usually 8,400" and "too early to compare" instead of "Usually 0 · Ahead of usual"; Settings' "Last" and "Synced" times follow the hour (they said 15:02 at every hour, at night too).

**Measured** (`dd1/measure.json`, floor 3.0, no veil): dawn glass 3.49, sky 7.46, number 7.59, blue button 3.23, green / coral 3.74 / 3.61; dusk glass 3.18, sky 3.88, number 3.33, white button 3.14, green / coral 3.19 / 3.30.

**Open:** Coach at dusk uses 26% (CO2, measured over its own layout) and these screens 34%: one value for the hour would look the same across tabs. Phone check.

**Board:** `dd1-board.html`. STOP.

**Approved (2026-11-23):** dawn and dusk as built (white day glass with dark ink at both hours; dusk 34%, white buttons over the sky, darker green and coral).

## CO3 · Coach's dusk glass (2026-11-23)

**Asked:** Coach dusk glass (open item from DD1: Coach at 19:45 used 26% white, the other screens 34%).

**Changed:** `co2-board.html` draws Coach at 19:45 on 34% white glass, as every other screen at that hour (DD1); dark ink, white send button and your white bubbles as before. Other hours unchanged.

**Measured** (`g2.js` now takes `ALPHA=` to measure a set strength; `MODE=light ALPHA=.34`, dusk 19:45, the whole chat glass x 12–400, y 236–839, and the nav, floor 3.0): glass text 3.19 (was 3.16 at 26% over CO2's smaller areas; over the whole chat glass it needs 32%), primary 5.06, nav 3.29, sky text 3.37 (unchanged), white send button vs sky 3.13.

**Shots:** `tools/visual-loop/co2.js` → `out/co2-2.png` (before / after with Settings and HRV at dusk: `out/co3-sheet.png`).

**Board:** `co2-board.html`. STOP.

**Approved (2026-11-23):** Coach at dusk on 34% white glass, one strength for every screen at that hour.

## PH1 · The new screens on the phone (2026-11-23)

**Asked:** phone check (open since CO1: none of the new screens had been seen on the Galaxy S21).

**How:** `night.js` takes `?solo=N`: only that board's Nth phone, fitted to the device (`min(width/412, height/915)`; Chrome's address bar stays, so the screen shows at about 85% of the size the app would have full screen). `tools/visual-loop/ph1.js` opens each view in Chrome over adb (`adb reverse tcp:8765`) and takes a device screenshot: Coach at 07:15 / 19:45 / 23:10, Settings day / dusk / night, Log weight day / dawn, Readiness, Steps day / dusk, Blood oxygen at night → `out/ph1-*.png`, sheet `out/ph1-sheet.png`.

**Seen on the device:** the glass blur, the white frost by day, the lilac glass at dusk and the land-hued glass at night render as on the PC; text over the sky and on the glass reads at every hour; the white buttons at dusk and night hold against the sky. The scene here is the saved render (static), not the live renderer, so this says nothing new about frame cost.

**Fixed:** Steps at dusk: "now" sat under "about 9,600" at the top of the chart; after 17:00 "now" goes left of its line (day shot unchanged).

**Found for lock:** chart legends and card footnotes are 11–11.5 px on a 412 px mock, about 10 dp on the S21 and below 9 dp in this check; they read, but only just. At lock, no text under 12 sp.

**Note:** each view opened a new Chrome tab on the phone (13 tabs).

**Board:** `out/ph1-sheet.png`. STOP.

**Approved (2026-11-23):** the phone check as done; at lock, no text under 12 sp.

**Gaps (2026-11-23, after the phone check):** the last card on Steps sat 3 px *under* the chart card and on Blood oxygen 9 px under it; the other metric pages ran 7–23 px apart. The cards under the range control now stack at the standard 12 px on every metric page (`stackCards()` in `night.js`, MD2–MD4 boards, the app's Column spacedBy 12 dp); all end above the nav (lowest 835). Re-shot: MD2–MD4, `nt1/metric-*.png`, `dd1/*-{metric-month,readiness,steps,spo2}.png`, phone Steps and Blood oxygen.

## UH1 · Unhappy states (2026-11-23)

**Asked:** the unhappy states (open since SE1, CO1, WT1, MD1: errors, offline, a disconnected or low band).

**Found (shipped words):** sync pill `Band not found · Retry`, `Couldn't connect · Retry`, `Sync incomplete · Retry`, `Bluetooth off · Turn on`, `Allow Bluetooth`, stale after 6 h `Last sync … ago`; battery low under 20% ("charge your band"); Coach `Coach is offline, so here are your numbers.`; weight `Enter 30.0 to 300.0 kg`, `Couldn't save 63.9 kg for 23 Nov`, `Couldn't delete · Retry`; a metric's normal needs 7 days with data (`MIN_BASELINE_DAYS`). The morning check's failed / unsaved / no-band sheets were already drawn in MC2.

**Built:** every board opens its unhappy states with `?uh=1` (also `&t=night`); approved views are unchanged. Shots `tools/visual-loop/uh1.js` → `out/uh1-{day,night}-0..7.png`, sheet `out/uh1-sheet.png`.
- **Settings (`se1-board.html?uh=1`):** the hero carries the band's state: *Band not found* (battery when last seen, Retry), *Bluetooth is off* (Turn on), *Charge your band* (14%, Sync now). The Recent syncs row says the same.
- **Today and Log weight (`wt1-board.html?uh=1`):** a failed sync puts one glass card on the lip (Band not found · last synced 13:40 · Retry) and the tiles move down 76 px, the numbers staying the last good ones; a typed 639 says *Enter 30.0 to 300.0 kg · did you mean 63.9?* in coral with a mark and the underline in coral, Save outlined until it is in range; a failed save keeps the snackbar with *Retry* instead of Undo.
- **Coach (`co2-board.html?uh=1`):** the read on the sky still works (rules on the phone); a question gets the shipped fallback with your numbers, *From this phone · nothing was sent*, a *Try again* chip; the Claude app hand-off is replaced by "No connection".
- **A new band (`md4-board.html?uh=1`):** HRV on day 4: *Building your normal · 4 of 7 days so far*, Week range, values plain (no band, no colour, no arrow), the days before the band shaded, and why it waits.

**Measured:** no new colours or surfaces: the glass, ink and sky are the measured ones for each screen (Settings 15:10 3.75 / night 6.96; Today 07:15 3.79; coral on the day card 3.54, TR2). Sky text at night ends left of the moon.

**Open:** with the alert card, Today's last tile (Daily score) goes under the nav, as with MC2's E1 entry card: Today scrolls, but the first screen loses it. Proposed: the sky leaves HRV's cirrus at its default until a normal exists (the "Why wait" card says so); not built in the scene.

**Board:** `?uh=1` on the four boards. STOP.

**Approved (2026-11-23):** the unhappy states as built (Settings hero states, Today sync card, weight range and save errors, Coach offline, a new band's metric).

## PD1 · A poor day (2026-11-23)

**Asked:** a poor day (open since CO2: a poor day's lines and how the screens read when readiness is low).

**The day:** today with 4 Nov's body (`data.js`): HRV 46 ms (normal 55–63), resting HR 58 (normal 51–56), 6h 05m asleep (score 58), skin +0.4 °C, SpO2 96%. The shipped `readinessParts` give **58** (HRV 19, resting HR 15, sleep 15, vitals 9; the data's hand-set 54 for 4 Nov does not follow the formula), *Take it easy*, so the sky is the stratocumulus regime (50–64, D1).

**Sky and glass** (`g2.js`, stratocumulus at 07:15 / 15:10 / 19:45 / 23:10, all screens' glass from y 236 and sky text to x 380, floor 3.0, no veil; `pd1/*.png`, `pd1/measure.json`): 07:15 white 22%, glass text 3.15, sky 8.24; 15:10 white 22%, 3.29, sky 8.08; 19:45 white **37%** (34% on a fair dusk), 3.19, sky 3.55, the white button falls under 3.1 against the grey dusk so the button is dark blue `#082F5A` (3.24); 23:10 land glass `#11225A` 22%, 5.73, sky 3.26.

**Built:** `?poor=1` on three boards (also `&t=night`), shots `tools/visual-loop/pd1.js` → `out/pd1-*.png`, sheet `out/pd1-sheet.png`:
- **Today** (`wt1-board.html?poor=1`): 58, *Take it easy*, "18 below your normal 76"; the tiles hold the day plainly (6h 05m · score 58 · 1h 07m less than usual; 3,240 steps · an easy day; HRV 46 ms · morning check), no colour, as on a good day.
- **Coach** (`co2-board.html?poor=1`), the read eases off all day: 07:15 "An easy day will pay off tomorrow." (Should I still train? → keep it light, with the numbers); 15:10 "Easy so far. Keep it that way."; 19:45 "An easy day done. An early night next." (if skin temp and resting HR are still up tomorrow, another easy day); 23:10 "Bed by 23:00 tonight." (half an hour early: last night was short).
- **Readiness** (`md3-board.html?poor=1`): 58, ↓ Below normal in coral, the 30 days to today, and the parts by the shipped formula (19 / 15 / 15 / 9).

**Found:** the glass at dusk depends on the weather (34% fair, 37% stratocumulus) and so does the dusk button (white on fair, dark blue on grey). One rule for the hour would take the worst weather: 37% and a dark blue button at dusk. Not changed here.

**Not done:** a very poor day (under 50, low stratus) and likely illness (haze, light rain, D6); the Calendar and Trends on a poor day.

**Board:** `?poor=1` on Today, Coach and Readiness. STOP.

## SH1 · The low cloud sheet repeated (2026-11-23)

**Asked:** on the PD1 board "the clouds look way too repetitive".

**Found:** the stratocumulus/stratus sheet (`sheetD`) took its cells from one 4.5 km tile of the 128³ shape noise, the same everywhere: overhead every lump had the same size, and toward the horizon the tile, seen edge-on and at full resolution, turned into a glittering band of identical tiny cells in rows. Nothing in the sheet used the weather map (the cumulus layer does).

**Changed (`scene-gl.js`, `sheetD` only):**
- The weather map at 34 km bends the cells by up to ±0.75 km and the cells are turned off the texture axes (36.9°), so the tile no longer lines up toward the horizon.
- Seen edge-on the gaps close up: from 6 km out to 22 km the sheet merges into one soft band (`far`), and the fine detail erosion fades with it. This is what real stratocumulus does at the horizon, and it removes the glitter.
- Tried and dropped: a second, larger cell scale blended in by region (made the sheet one flat white mass overhead) and patches of thicker/thinner cover from the weather map (same, and fewer lumps).

**Checked:** fair, partly, night and the other cumulus skies are unchanged (sunny differs from the old shader by less than two runs of the new one differ from each other); stratus, rain, thunder and fog change in their sheet only. Load to converged 26.1 s (was ~25 s): compile cost holds (`loadT.js`). Shots `out/sh1-*.png`; renders before / after for the PD1 hours in `out/sh1-crop.png`.

**Re-measured** (`pd1/measure.json`, floor 3.0, no veil): 07:15 white 23%, glass 3.18, sky 8.72; 15:10 22%, 3.27, 9.72; 19:45 37%, 3.18, 3.85, dark blue button; 23:10 land glass 22%, 6.29, sky 3.26. PD1 screens re-shot (`out/pd1-sheet.png`).

**Not done:** the baked keyframes for the sheet weathers (stratocumulus, stratus, rain, thunder, fog) and `md1/nov4.png` (the Calendar's past day) still hold the old sheet: re-bake (`bakeAll.js`) and re-render before the phone sees them.

**Board:** `?poor=1` on Today, Coach and Readiness. STOP.

**Approved (2026-11-23):** the reworked sheet (SH1) and the poor day as built (PD1: Today, Coach through the day, Readiness; dusk at 37% with a dark blue button on grey skies).

**Re-baked (2026-11-23, after SH1):** `bakeAll.js` re-baked the day's 17 keyframes (16 fair hours and stratocumulus 13:00, seed 20780 as before) in 78 s; the baked stratocumulus plays back like the live render (`out/bk-cmp.png`). The on-device cache goes to `sky-v5`, so phones drop skies they baked with the old sheet. `md1/nov4.png` (the Calendar's past day) re-rendered with MD1's screen areas (the fair sky reproduces 3.75 / 5.50 / 7.04): past-day glass 3.28 (was 3.29), sky text 4.75 (was 5.07), number 13.29; the boards' inlined values follow.

## VP1 · A very poor day, and likely illness (2026-11-23)

**Asked:** continue (open since PD1: a very poor day under 50, and likely illness, D6).

**The days** (shipped `readinessParts`, normals from `data.js`):
- **Very poor:** HRV 38 ms (normal 55–63), resting HR 59 (average 54), 4h 40m asleep (score 42), skin +0.1 °C, SpO2 96% → **46**. Not ill (skin under +0.3), so BodySky gives the stratus regime with full valley fog in the morning; HRV's veil is set but hidden.
- **Likely illness:** 6 Oct's body (HRV 44, resting HR 60, 6h 05m score 56, skin +0.8 °C, SpO2 93%) → **49** (the vitals part drops to 54: skin far from average, SpO2 under 95). Skin ≥ +0.5 and resting HR ≥ +5 → light rain (D6); not under 35, so no storm (D2).

**Sky** (`g2.js` now takes a 5th STATES element, a body, and renders `BodySky.bodySky(body, hour)` instead of a preset; `vp1/<day>-<hour>.png`). Control: PD1's stratocumulus 15:10 reproduces 3.27 / 9.69. Under stratus the HRV veil changes the sky by under one level per channel (veil vs none, top 37%, mean |Δ| 2.3 summed over RGB), so the sky note no longer claims "a milky veil of high cloud" in the stratus regime (`body-sky.js`; it already dropped it under rain).

**Glass** (`vp1/measure.json`, floor 3.0, no veil, all screens' glass y 236–851 + nav, sky text to x 380):

| | 07:15 | 15:10 | 19:45 | 23:10 |
|---|---|---|---|---|
| Very poor (stratus) | white 22%, 3.40, sky 7.15 | white 29%, 3.19, 8.38 | **white 41%**, 3.18, 3.19 | land `#11225A` 22%, 8.06, 9.99 |
| Likely illness (rain) | white 22%, 3.23, 5.21; button `#082F5A` | white 22%, 3.28, 5.65; `#082F5A` | white 29%, 3.20, 3.71; `#082F5A` | land `#1E2739` 22%, 5.76, 6.64 |

Dusk under stratus needs 41% white (34% fair, 37% stratocumulus); dark glass would need 31–33%. Kept white (the dusk rule, DD1/PD1).

**Built** (`?bad=1` on two boards; shots `tools/visual-loop/vp1.js` → `out/vp1-wt1-*.png`, `out/vp1-co2-*.png`, sheets `out/vp1-wt1-sheet.png`, `out/vp1-co2-sheet.png`):
- **Today** (`wt1-board.html?bad=1`): under 50 the word is **Rest** (Ready ≥ 80, Steady ≥ 65, Take it easy ≥ 50). Four hours per day, then the sky tapped (D4). Illness puts one card on the lip in words, *Your body may be fighting something* · *Skin +0.8 °C · resting HR 6 above usual* · **Why** (opens the same note as the sky), so the rain never carries it alone. Tiles stay plain.
- **Coach** (`co2-board.html?bad=1`), very poor: *Rest today.* → *Resting so far. Good.* → *A quiet day done. Sleep early.* → *Bed now, if you can.* Illness: *Rest, and see how you feel.* (detail: the two signs) → *Keep it a rest day.* → *An early night helps most.* → *Bed early. Rest helps.* Answers use the D6 words ("can be an early sign of illness"), say when to talk to a doctor, and name their sources.
- Coach's read now balances its line breaks (`text-wrap: balance`; Compose `LineBreak.Heading`): four reads had a one-word second line. Changes only how existing two-line reads break.

**Found:** with a card on the lip (illness, UH1's sync card), Today's Daily score tile runs under the tab bar (the open "last tile" item). At night the moon shows through the rain sheet's gaps (minor).

**Not done:** the storm (illness and readiness under 35, D2); Readiness, Calendar and Trends on these days; the haze-only day (skin and resting HR up a little, not ill).

**Board:** `wt1-board.html?bad=1`, `co2-board.html?bad=1`. STOP.

**Approved (2026-11-23):** VP1 as built (Rest under 50; the illness card on the lip with Why; Coach through both days; balanced read lines; white glass 41% at dusk under stratus).

## LT1 · Today's last tile with a card on the lip (2026-11-23)

**Asked:** continue (open since UH1 and VP1: with a card on the lip, Today's Daily score tile runs under the tab bar).

**Found:** a card on the lip (the morning check, E1; a failed sync, UH1; likely illness, VP1) moves the tiles down 76 px; Daily score then spans y 800–884 and the tab bar starts at 851. With the standard 12 px gap above the bar it needs 45 px back.

**Built** (`wt1-board.html?fit=1`; `tilesFor()` in the Today phone, `o.fit`; shots `Q=fit=1 TAG=lt1 node vp1.js wt1` → `out/lt1-wt1-*.png`, sheet `out/lt1-sheet.png`). Four situations (illness 15:10 and 23:10, the sync failed 07:15, morning check not done 07:15), three fits each, applied only while a card sits on the lip:
- **A · as built:** Today scrolls; the first screen loses Daily score under the bar.
- **B · compact:** Steps and HRV 76 px high (was 100) with their note on the label line, Sleep 164 (was 212); Daily score ends at 836. Nothing dropped.
- **C · grid:** two rows of two, Sleep · Steps / HRV · Daily score ("so far today"), all 100 high; ends at 788. Sleep drops "less than usual".

**Checked:** the as-built tiles came from the same new code byte-identical (VP1's ten Today shots). Every card stays inside the glass area VP1/WT1 measured (y 236–851) at the same text sizes, so those contrasts hold.

**Board:** `wt1-board.html?fit=1`. STOP for the pick (A / B / C).

**Picked (2026-11-23):** B · compact. Today now draws the compact tiles whenever a card sits on the lip (every board: MC2's E1 card, UH1's sync card, VP1's illness card); the LT1 board still shows A and C for comparison.

## ST1 · The storm day (2026-11-23)

**Asked:** continue (open since VP1: likely illness with readiness under 35, D2).

**The day:** HRV 36 ms, resting HR 64 (average 54), 4h 50m asleep (score 40), skin +1.1 °C, SpO2 93% → **34** by the shipped `readinessParts` (HRV 10.5, resting HR 8.3, sleep 10, vitals 5.4). Skin ≥ +0.5 and resting HR ≥ +5 with readiness under 35: BodySky gives the thunder sky (denser rain, the bolt).

**Found and fixed (`scene-gl.js`):**
- **The bolt never drew.** Its `uBolt` uniform call had slid into the end of a `//` comment (G3's snow-colour line), so `uBolt` stayed 0 on every thunder render since G3 (the SH1-era `out/thunder.png` has no bolt). Moved back onto its own line.
- **It struck through the number.** The bolt lived at x 0.30 of the width from the top edge, through the readiness number; at night it also sat behind the top-right button (white button 1.45:1). Now at x 0.74 from the cloud base (y 0.10) down to y ≈ 0.27, above the maple; shorter (12 + 7 steps); the branch stays on screen; the cull box leaves the glow under 1% at its edges (the first try cut a visible box at night).
- Live it still flashes every ~9 s with one flicker (two flashes, under WCAG's 3 a second); still frames and reduced motion show it steady.

**Rule:** on a storm day, sky text keeps left of x 262 by day as well, like at night (`SKY_RIGHT` also when `BOLT`): the bolt owns the right sky.

**Measured** (`st1/measure.json`, floor 3.0, no veil; glass y 236–851 + nav; sky text to x 380 by day, x 262 at night; bolt on):

| | 07:15 | 15:10 | 19:45 | 23:10 |
|---|---|---|---|---|
| Storm | white 26%, 3.19, sky 4.42; button `#082F5A` 4.15 | white 24%, 3.17, 4.86; 4.91 | white 31%, 3.19, 3.34; 3.23 | land `#1E2739` 22%, 3.72, 7.08; white 3.38 |

Load to converged 20.1 s (`loadT.js`; SH1 26.1 s): compile cost holds.

**Built** (`?bad=1`, third section on each board; shots `node vp1.js` → `out/vp1-wt1-10…14.png`, `out/vp1-co2-8…11.png`; sheets `out/st1-today.png`, `out/st1-coach.png`, skies `out/st1-skies.png`):
- **Today:** 34, *Rest*, "42 below your normal 76"; the card on the lip (*Your body may be fighting something* · *Skin +1.1 °C · resting HR 10 above usual* · Why), compact tiles under it (LT1); the sky note says *A storm: … This can mean your body is fighting something; rest if you can.*
- **Coach:** *Rest. Your body needs it.* (detail: the two signs) → *Rest is the plan today.* → *Wind down early tonight.* → *Sleep now if you can.* Answers say rest, and to talk to a doctor with a fever, if unwell, or if the signs stay up.

**Not done:** Readiness, Calendar and Trends on the bad days; the haze-only day. Other boards that show `thunder` now show the bolt, at its new place.

**Board:** `wt1-board.html?bad=1`, `co2-board.html?bad=1` (third section). STOP.

**Approved (2026-11-24):** ST1 as built (the bolt fixed and moved to the right sky; storm-day sky text left of x 262; Today and Coach through the storm day).

## BD1 · Readiness, Calendar and Trends on the bad days (2026-11-24)

**Asked:** Readiness, Calendar and Trends on the bad days (open since PD1).

**The days:** VP1's very poor day (46), likely illness (49) and ST1's storm day (34), at 15:10 under each day's own sky and glass (`vp1/`, `st1/` renders and measurements; every card sits inside the glass area measured there).

**Built** (`?bad=1`; shots `BOARDS=md3,tr2 TAG=bd1 node vp1.js` → `out/bd1-md3-*.png`, `out/bd1-tr2-*.png`, sheet `out/bd1-sheet.png`):
- **Readiness** (`md3-board.html?bad=1`): the MD3 page for a given day (`readinessPage(bd)`). The month chart's scale drops to 30 when a day is under 50 (grid 40 / 60 / 80), so today stays on it, filled coral below your normal. The parts follow the shipped `readinessParts`, vitals included: 12 / 14 / 11 / 9 (46), 17 / 13 / 14 / 5 (49), 11 / 8 / 10 / 5 (34), each summing to the score; on the illness days the vitals line reads "SpO₂ 93% (below 95%) · skin temp far from your average" (it was fixed to "near your average").
- **Calendar** (`md3-board.html?bad=1`, illness and storm days): each day's glyph is the sky it had, from the body, not readiness alone: rain (grey cloud with three drops) when skin temp and resting HR are both up, a bolt when readiness is also under 35. A second key line says so ("skin temp and resting HR up", "both up, readiness under 35"); the subtitle reads "Each day's sky, from your body". Shape carries it, not colour.
- **Trends** (`tr2-board.html?bad=1`): the TR2 list with today's values replaced. Very poor: 2 of 8 within your normal, "5 numbers need a look today". Illness days: 0 of 8, and the line names the pattern instead of a count: "Skin temp and resting HR are both up: your body may be fighting something" (as Today's card). Storm-day sky text keeps left of x 262 (ST1).

**Measured:** TR2's colours against each day's glass at its worst page band (floor 3.0): very poor (29%) bad 3.68, good 3.81; illness (22%) bad 3.35, good 3.47; storm (24%) bad 3.30, good 3.42.

**Found:** md3's coral colour constant is also called `BAD`, so the board tests `window.BAD` for the flag.

**Not done:** the haze-only day; past bad days opened from the Calendar (only today is a bad day in the data); night versions of these pages.

**Board:** `md3-board.html?bad=1`, `tr2-board.html?bad=1`. STOP.

**Approved (2026-11-24):** BD1 as built (Readiness with the shipped parts and a scale to 30; the Calendar glyph from the body, rain and storm; Trends naming the illness pattern).

## BD2 · The bad days at night, and the haze day (2026-11-24)

**Asked:** night versions of Readiness, the Calendar and Trends on the bad days, and the haze-only day.

**The haze day:** HRV 52 ms (just under normal), resting HR 57 (+3), 6h 50m (score 70), skin +0.4 °C, SpO2 96% → **67** by the shipped parts (24 / 16 / 18 / 9). Skin ≥ +0.3 and resting HR ≥ +3 but short of illness: BodySky gives partly cloudy with a humid haze (milky veil round the sun, high streaks). Renders `bd2/hz-day1510.png`, `bd2/hz-night.png`; `bd2/measure.json` (g2.js, floor 3.0, sky text to x 380 by day and x 262 at night): 15:10 white 22%, glass 4.07, sky 6.47; 23:10 land `#142257` 22%, glass 6.64, sky 4.08. No veil.

**Built** (`md3-board.html?bad=1`, `tr2-board.html?bad=1`, and the same with `&t=night` for 23:10 on each day's night glass; `BAD_K()` in `night.js` picks the hour; shots `BOARDS=md3,tr2 TAG=bd2d|bd2n Q=… node vp1.js`, sheets `out/bd2-day.png`, `out/bd2-night.png`):
- **Readiness:** the haze day joins the three (67, ↓ Below normal, the vitals line "skin temp near your average": +0.4 is not "far").
- **Calendar:** the haze day's glyph is a sun over two haze lines. The weather key is now three full rows under the readiness key ("Skin temp and resting HR a little up", "Both up: your body may be fighting something", "Both up, and readiness under 35"): on one line the three wrapped into fragments.
- **Trends:** the haze day counts 3 of 8, and its line names the pattern: "Skin temp and resting HR are a little up: your body is under some load". At 23:10 steps and strain are the full day's ("Below your usual").

**Found and fixed: colour on the sky.** The metric pages drew the hero's "↓ Below normal" in coral on the sky, but only the ink was ever measured there. Coral (`#B23A2E`) against the sky behind the word (x 22–202, y 140–166): fair 3.48, haze 3.30, very poor 3.58, **illness 2.34, storm 1.99**, PD1's dusk 2.09, VP1's dusk 1.31 (the ink: 3.24–14.1). Rule now: on the sky the arrow and word take the sky ink; coral and green stay on the glass (chart, parts, list), where they are measured. Applied to the hero helpers of `md3-board.html` and `md4-board.html` (every metric page); arrow and word carry the meaning, as before.

**Measured** (TR2 colours on each day's glass, worst page band, floor 3.0): 15:10 haze bad 3.52 / good 3.64, very poor 3.68 / 3.81, illness 3.35 / 3.47, storm 3.30 / 3.42; 23:10 (night colours) haze 7.13 / 8.90, very poor 7.28 / 9.08, illness 4.35 / 5.43, storm 4.38 / 5.47.

**Not done:** the haze day on Today and Coach; `md1-board.html` (history) still draws its HRV hero word in colour; past bad days opened from the Calendar.

**Board:** `md3-board.html?bad=1`, `tr2-board.html?bad=1`, each also with `&t=night`. STOP.

**Approved (2026-11-24):** BD2 as built (the haze day; the bad days at 23:10; the Calendar weather key in three rows; sky hero words in the sky ink, colour on the glass only).
