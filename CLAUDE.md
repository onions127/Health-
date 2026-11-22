## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. Route only to skills in the session's available-skills list; answer directly for quick questions or small scoped edits.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec
- Sky / weather scene shader work → read the project skills `shader-art-coding`, `threejs-shaders`, `threejs-postprocessing` (in `.claude/skills/`) for technique; `frontend-design` for the UI layer over the scene

---

# Health++ — project context

Health++ is a personal recovery/readiness companion app for a JCWear band (Android, Kotlin + Compose, `android-app/`; minSdk 26). The Today screen is being redesigned around a live sky scene in the spirit of Samsung One UI Weather. Exploration history lives in `docs/design-exploration/LOG.md` (read it before any scene work; every round is logged there).

## Where things are

| Path | What |
|---|---|
| `DESIGN.md` | **Instrument**: the shipped app design (R6.2). Control, not the target. Do not edit before "lock". |
| `docs/design-exploration/BRIEF.md` | Approved brief v1 (job, feel, rules). |
| `docs/design-exploration/LOG.md` | Round-by-round decisions R0 → 3.14. Append a section per round. |
| `docs/design-exploration/PLAN-sky.md` | Sky grammar, SkyState, body → sky mapping, decisions D1–D6. |
| `design-prototype/scene-gl.js` | **The scene.** One WebGL2 renderer (`SceneGL.mount(canvas, opts)`), GLSL ES 3.0. |
| `design-prototype/scene.html` | Single scene: `?sky=<preset>&h=<hour>&r=<readiness>&seed=&anim=0&post=0`. |
| `design-prototype/r3*-board.html` | Review boards per round (r3c = Today with glass + tilt, r3h = hour/readiness sliders). |
| `design-prototype/phone.html` | Full-screen device test page (`?baked=`, `?device=1`, `?src=`). |
| `design-prototype/glass-check.js` | Measures real text contrast over the rendered scene under the glass. |
| `design-prototype/bake.html`, `bake-server.py`, `sky-baker.js`, `baked/` | Keyframe baking (PC and on-device worker). |
| `design-prototype/scene-gl-34.js`, `scene-kit.js` | Old versions (3.4 volumetric, 3.2 SVG). Comparison only. |
| `reference/` | Samsung Weather references (see below). |
| `design-prototype/r4a-board.html` … `r4h-board.html`, `t1/` … `t8/` | Terrain rounds T1–T8 boards and their saved renders (history only: the live variants were removed at terrain lock; the boards' images still open). |
| `reference/terrain/` | Terrain photo and stylised references (local only, gitignored). See "Terrain reference specs". |
| `tools/visual-loop/` | Headless screenshot + side-by-side compare script. |

Servers (`.claude/launch.json`): `design-board` → http://127.0.0.1:8765 (serves `design-prototype/`), `design-board-2` → :8767, `design-bake` → :8768 (bake-server, PUT into `baked/` only).

## Decisions already made (do not re-litigate)

- **Exploration rule:** no edits to `android-app/` or `DESIGN.md` until the user says "lock". Exploration files live in `design-prototype/` and `docs/design-exploration/`.
- **Rounds end with a STOP:** show the board, wait for the user's pick. Log each round in LOG.md with what was measured.
- **Feel:** clean, precise, open, like looking at a clear sky. The illustration is the centerpiece and is judged first (3.1).
- **Direction:** A+D Glass (Open Sky scene + Forecast's unequal tiles), **full glass** 22% white day (saturate 1.6, ink `#2E4A62`), blur 22 px, light edge, soft offset shadow. **Night glass (G2, 2026-11-22):** the land's own hue under the cards at Y .02, 22%, saturate 1, faint light edge, light ink; only as strong as the text needs (`GlassRule.G2` in `glass-rule.js`). **G3 (2026-11-22), the sky behind the text after sunset:** cloud sky light goes from white to the dark sky with night (`AMBN`), the key fades once the sun is down (`TWI`), stars are low intensity, night snow is lit like the moonlit cloud; no veil behind the sky text at night.
- **Contrast is measured, never estimated:** text over the *rendered* scene (glass-check.js) must reach **3.0:1** (G2, 2026-11-22: the user chose the feel over AA 4.5; small text below 4.5 at dusk and dawn is accepted), 3:1 non-text, 48dp targets, colour never carries meaning alone. Any scene colour under day glass must be at least as light as `#86B9A0` (3.1). Last measured worst case: 3.18:1 (G2, dusk 19:45).
- **Originality:** match Samsung's *style and quality*, never trace or copy its artwork, figures or characters. No person/character figure in our scene.
- **Scene technique (3.3 → 3.14):** physically based, data-driven, code-drawn (no painted assets):
  - Volumetric cumulus ray-marched through 1.5–8.5 km (Schneider/Nubis style): 128³ Perlin-Worley shape + 64³ Worley detail (16-bit float 3D textures), 512² weather map; Beer-Lambert + two-lobe HG phase + multi-scatter octaves + powder; one directional key light (sun at infinity, 3.14).
  - Cirrus is the main cloud (3.7): plane at 9.5 km, one upper-wind direction per day, hooked fibres, cirrostratus veil with 22° halo.
  - Sheet layer for stratocumulus/stratus, valley fog, light rain (3.6). A second "painted" style exists (`style: 1`, 3.8).
  - Camera: horizon 58% down (3.7). **Superseded 2026-11-19:** horizon moves to **37%** down, to fit the app like the reference (see "Decisions 2026-11-19").
  - Four depth slices (sky / near clouds / mid land / near land) with tilt parallax, AMP `[3, 9, 14, 22]` CSS px (3.10).
  - Float pipeline: 24 jittered frames accumulated in RGBA16F; post = bright pass, two-level bloom (¼, ¹⁄₁₆), quarter-res light shafts, per-hour colour grade, highlight shoulder, sRGB, dither (3.9, 3.12).
  - Live layer: wind gusts in grass and crowns (MRT material), birds, sun/moon disc, halo, twinkling stars (3.11–3.12).
  - Baked keyframes per weather (`KEYFRAMES`, RGBM PNG in sqrt space); phone plays bakes, PC/phone worker bakes; Cache Storage `sky-v2` (3.12–3.14).
- **SkyState** (one struct drives everything, later Android too): `cover, tall, grey, haze, sheet, sType, sb, st, fog, fogH, rain, wind, cirrus, style, paintCov, seed`. Presets: `fair, partly, stratocumulus, stratus, valleyFog, mist, rain, painted, paintedClear, paintedCloudy`.
- **Body → sky (PLAN-sky D1–D6):** each signal owns one layer. Readiness → low-cloud regime (≥80 fair, 65–79 partly, 50–64 stratocumulus, <50 stratus); sleep → morning valley fog; HRV/stress → cirrus form; strain/steps → cumulus growth through the day; skin temp + RHR both up → haze, and light rain only for likely illness (D6). No storms (D2). New formation per day seeded by date (D3). Tap the sky to explain; TalkBack text always (D4).
- **Android path:** AGSL needs API 33 and has no 3D textures → 2D atlas at reduced res, or baked frames (API 26–32 play bakes with GLES 3.0 passes). Decide at lock.
- **Dependencies:** any new font or library must be approved first (brief). Three.js is **not** a runtime dependency; the renderer is hand-written WebGL2 so it ports to AGSL. Use the Three.js skills as technique references only, unless the user approves adopting it.
- **Decisions 2026-11-19 (after the Samsung reference pass):**
  - Horizon at **37%** of screen height (lake/sea strip + distant hills, grassy lip just below, first card right on the lip). Replaces the 58% camera.
  - Hero = the **readiness number, large, on the left of the scene**, like the temperature in the reference (number, then a word under it, then a small detail line). **No bar, no gauge, no arc** under or around it. The sun-on-the-arc hero (3.2) is retired; the sun stays in the sky as weather only.
  - Glass stays **as designed** by day (22% white, dark ink). At night (G2) the glass goes dark with light ink, tinted by the land, not Samsung's constant navy.
  - Renderer stays **hand-written WebGL2 / GLSL**, no Three.js runtime: it already holds the whole pipeline (float slices, bakes, tilt) and must port to AGSL. Three.js skills are technique references (bloom, DOF, lens flare, particles), ported by hand.
  - `reference/` is local only (gitignored): third-party screenshots are never committed.
- **Terrain lock (2026-11-22, rounds T0–T9):** the land is the **open calm loch** (T3 `lochCalm`): wide still water, a mountain range far off on the left (snow follows the season), two long low ranges, a shore with coves of sand, **two maple trees** on the lip (grown branching, fine leaf clumps, thinning without holes; colour, fullness and bloom follow the day of the year), and a **flat page** under the cards (no swell or fields under the glass, T8). One land only: `LAND`, `TK` (tokens) and `LEAF` in `scene-gl.js`; the `terrain`, `tree`, `leaf` and `ground` options are gone. Season: Mediterranean year (`DRY`, `MSNOW`, the maple's `year`). `?dry=` and `?doy=` still preview a season.
- Phone tests: Galaxy S21 via adb; never uninstall the app. Figma Starter plan runs out of MCP calls; fall back to HTML boards.

## Samsung One UI Weather reference specs

Sources in `reference/` (top level = one canonical image per state; subfolders hold the rest):
- `reference/user/` — user-supplied device screenshots (sunny with flare, partly cloudy, overcast, dusk/cloudy purple, mostly cloudy).
- `reference/video/` — frames from the YouTube video "Samsung Galaxy … One UI 8 | All Weather Animations & Varieties" (XJ-d7Zo67EU): sunny, clear morning, twilight, night, partly cloudy, cloudy dusk, mostly cloudy night, rain, thunderstorm, fog, snow, smoke, dust.
- `reference/online/` — One UI 7 vs 8 comparisons from Samsung Community and 9to5Google.

These are reference stills for *style*, not assets: never ship them or trace them.

### Composition
- Horizon sits **~35–38% down** the screen: a lake/sea strip with low distant blue hills, then a grassy foreground lip (tufts, small flowers, stones) at ~40%. The first card starts right on that lip (~42%). Adopted: ours goes to 37% (2026-11-19).
- The big number (temperature) sits top-left at ~y 0.14–0.24, ~100 sp light weight, condition word below it, a small detail line lower; nothing graphic under it.
- Below the land the page background is a long vertical gradient the cards float on: meadow green → teal → sky blue → saturated blue at the nav bar. The scene and this gradient read as one continuous picture.
- Clouds live in the top 0–30%, often cropped by the top edge. The sun sits high, right of centre (~y 0.13–0.17).

### Sky colour ramps (sampled, right edge of sky, top → just above horizon)
| State | Top | Middle | Near horizon | Water / ground |
|---|---|---|---|---|
| Sunny (user) | `#67ACF1` | `#88C2F5` | `#A6D5F7` | sea `#5DBDE4` |
| Sunny (video) | `#70B2EF` | `#80BCF2` | `#97CBF6` | sea `#3E86AF` |
| Partly cloudy | `#4594DE` | `#579FE3` | `#97C9EE` | sea `#6FCCF0` |
| Clear, low morning sun | `#94B6C4` | `#9EB5BE` | `#C3BCBB` (warm) | sea `#72CBEE` |
| Mostly cloudy (device) | `#CAE8FC` | `#9BD3F8` | `#8DCBF5` | sea `#6DCCF3` |
| Overcast | `#FEFEFE` (cloud) | `#BBD0DB` | `#8CAFC7` | land `#406E7F` |
| Cloudy dusk | `#C7DCEA` | `#9AB8CD` | `#789EB8` | `#3E6B79` |
| Dusk, cloudy purple | `#A19ED9` | `#988ECD` | `#8376BA` | sea `#4F78A7` |
| Clear twilight | `#9D86EE` | `#A789EE` | `#C29AEE` (pink-lilac) | sea `#282F70` |
| Clear night | `#16317E` | `#12317F` | `#1C55B4` (brighter) | sea `#1B2072` |
| Mostly cloudy night | `#5678BF` (lit cloud) | `#385CA8` | `#1C56B6` | sea `#1B2072` |
| Rain | `#9194B9` | `#8C8FB6` | `#AC93CC` (lilac glow) | ground `#39585C` |
| Thunderstorm | `#99A8C0` | `#9AA4B3` | `#637494` | land `#394B62` |
| Light rain + thunder | `#808796` | `#8D929E` | `#5D6A8A` | land `#3A4D62` |
| Fog (evening) | `#304164` | `#7D8EAD` | `#8192AF` | `#4A4F8B` |
| Patches of fog | `#406386` | `#7795AC` | `#91ABBF` | `#618693` |
| Light snow | `#798E99` | `#607B8E` | `#8E90AB` | `#3F437F` |
| Snow | `#A6CBD5` | `#7DABC3` | `#A1C5DD` | `#A2CAE1` (snowy) |
| Smoke | `#7E95A9` | `#CBC8C7` | `#C7C5C3` (warm) | `#406B79` |
| Blowing dust | `#CBB278` | `#C0A364` | `#BA975E` | `#6E3C1D` |

Rules read off these:
- Clear day: saturated azure at the zenith, about +25–30 L* paler at the horizon haze; the water below is *more* saturated than the haze just above it.
- Night is not grey: deep ultramarine, **brighter toward the horizon**, sea darker than the sky.
- Bad weather is cool and violet-leaning (rain) or blue-grey (storm), never neutral grey. Rain keeps a lilac glow at the horizon.
- Twilight and dusk are lavender/violet with a white sun, not orange.

### Light
- **Sun (clear):** small hot white disc (~6–8% of screen width), wide soft bloom (~25% width), 8–12 thin diffraction spikes (starburst), and 2–3 faint round lens ghosts on the line from the sun through screen centre. Shafts are subtle; the starburst does the work.
- **Sun behind cloud (partly):** the cloud edge glows white with a silver rim; no disc.
- **Dusk sun:** white starburst disc just above the horizon with a lilac halo; light reflects as a soft path on the water.
- **Night:** sparse 1–2 px stars, low intensity; cloud undersides lit cool blue; moonlit glints on water.
- **Clouds:** soft, airbrushed, high-key white (`#FEFEFE`) with pale blue-grey shade (~`#C7DCEA`); low self-shadow contrast; misty edges, no hard silhouette; cumulus masses at the top edge, not streaks. Drift is slow (barely visible between frames seconds apart; not measured — estimate ≤ 2% screen width per 10 s).
- **Fog:** horizontal bands veiling the far water and land, desaturated, sky darkening toward the top.

### Precipitation and depth
- **Rain:** thin streaks ~1 px × 20–50 px, 15–35% opacity, slanted ~8–12°, 2–3 depth layers (near = longer and brighter). No large bokeh drops in the shipped app.
- **Thunderstorm:** denser streaks plus a thin branching white lightning bolt in the upper sky.
- **Snow:** round soft flakes, 2–3 depths; far flakes 1–2 px sharp, near flakes 4–8 px and blurred (depth of field); slow drift with the wind; snow-dusted ground.
- **Atmospheric depth:** distant hills are pale blue and low contrast; foreground grass is sharp and saturated.

### Glass cards (Samsung)
- Cards are a darkening tint over a heavily blurred backdrop (land detail smeared to colour bands, ~40–60 px blur). Measured: card ≈ 6–10% darker than the uncovered background beside it (gap `#57A9D0` vs card `#529AB3`); no white frost.
- Day cards follow the page gradient: `#75A784` (top, over meadow) → `#589EA8` → `#428ABE`; nav bar `#368AE4`. Night cards `#041F54`–`#0A286B`; rain `#426371`→`#25656A`; storm `#2E4454`→`#264065`; snow `#6F8FA1`→`#769AC7`.
- No visible border hairline (≤ ~8% white if any), no shadow. Corner radius ≈ 26–28 dp, side margin ≈ 11–12 dp, gap ≈ 12 dp.
- Text is **white** (primary 100%, secondary ~75–80%). Note: white on the green card top measures ≈ 3.5:1, **below AA**. Our AA rule wins: copy the materials, not the contrast. Decided 2026-11-19: we keep our light glass with dark ink; take only proportions (radius, margins, no border) from Samsung.

## Terrain reference specs (Round T0, 2026-11-20)

Sources in `reference/terrain/` (local only, gitignored, never committed or traced): `photo/` = Wikimedia Commons photography (lakes, Lake District, Loch Lomond, fjords, Seven Sisters coast, downs, Palouse, Tuscany, Blue Ridge/Smokies, alpine meadow, steppe, wetland, Namib and coastal dunes, fog, rain, snow, dusk, night); `style/` = store screenshots of games known for environment art (Firewatch, Old Man's Journey, Sable, Alba, A Short Hike, Journey, Season, Ghost of Tsushima, Lost Ember, The Pathless, Sky, GRIS, Dordogne). Contact sheets: `reference/terrain/sheet-*.png`. Ramps measured with `tools/visual-loop/bands.js` (median colour of horizontal bands, centre 80% width; value = CIE L*).

### Silhouette and massing
- Depth reads from **3–5 overlapping silhouettes** (Smokies `ridges-1`: 5; Lake District `lake-district-2`: 3 + valley floor; `fog-valley-1`: 3). Stylised work uses **3–4 planes** (Old Man's Journey, Firewatch). More than 5 turns into texture.
- Each nearer silhouette **overlaps the one behind across ≥ 30% of the width** and has a different rhythm (one long slow swell against shorter peaks). No two crests run parallel; no crest is symmetric about the screen centre.
- Crests carry the detail; bases dissolve into haze or the next layer. The eye reads the **top edge** of every layer, so the top edge must be clean (one line, a little fine noise), not lumpy all over.

### Value steps per depth layer (aerial perspective)
| Reference | Horizon sky | Far | Mid | Near | Foreground |
|---|---|---|---|---|---|
| Smokies (photo, clear) | `#E2EEF6` L93 → `#BED9EC` 85 | `#87B0CF` 70 | `#4E7A9E` 50 | `#4B6B89` 44 | `#4B5128`–`#6B5729` 33–38 |
| Lake District (photo) | `#ACC5DA` 78 | `#52647A` 42 (blue) | `#647D3E` 49 | `#5A5C31` 38 | `#56441C` 30 |
| Fog valley (photo) | `#D2EAF5` 91 | fog `#CCD5DB` 85 | `#6D7570` 48 | `#5B5342` 36 | 27–35 |
| Steppe (photo) | `#ADBAC2` 75 | horizon land `#AD9561` 63 | `#896E3A` 48 | `#77672D` 44 | — |
| Old Man's Journey (style) | `#F0E9D7` 92 | haze 85 | `#C79E77` 68 | `#C78565`→`#9F6B5C` 62–50 | `#4E4B51` 32 |
| Alba (style, high-key) | `#8BE8FE` 87 | `#95E39A` 84 | `#A5CC7D` 78 | `#C6B972` 75 | `#90A56A` 65 |
| Sable (style, low-contrast) | `#D3FAB7` 94 | `#82B08C` 68 | `#7BAB92` 66 | `#88B29A` 69 | `#A5BCAA` 74 |
- Rule: the **far layer sits 8–20 L\* under the horizon sky**; steps between far layers are small (5–10 L\*), between near layers large (10–20 L\*). Order is fixed: far is never darker than mid in clear air.
- Hue: far = the sky's horizon hue at low chroma (blue-grey); mid = blend; near = local colour at full chroma (green, ochre). Haze is additive toward the horizon colour, not a grey fog.
- Stylised work keeps the same order but **compresses the range high-key** (Alba 87→65, Sable 94→66). This is the only route that fits our glass rule: **anything under the cards must stay ≥ L\* 71** (`#86B9A0` = L\* 71). So deep near-values (L\* 25–45) can only live **above the first card (y < .42)**, and the page under the cards stays a high-key gradient.

### Water against the sky
- Open lake under open sky: water reads **5–15 L\* darker than the sky just above the horizon** (Derwentwater: sky L82, water L70; Kilchurn dusk: a reflection ~8 L\* under its object). Ripples break the mirror nearest the viewer; the far water is a clean mirror strip.
- Enclosed or steep water (fjord) mirrors dark slopes: **L\* 14–19** (`#1B313B`–`#122632`) under an L80 sky.
- Sea at the coast (Samsung, Seven Sisters): more saturated than the haze just above it; a pale shore band separates it from land.
- Night: water darker than the sky at the horizon (night lake: sky L24 at the horizon, water L9–11), the moon path is the only bright mark.

### Weather, hour and season on the land
- **Overcast** (`lake-haze-1`): the whole frame compresses to L\* ~35–75, chroma drops, no sun-facing slope light; low cloud sits *between* ridges, and the lake mirrors the grey sky.
- **Rain** (`rain-hills-1`): far layers vanish into the veil; mid ridges lift toward the sky value; rain shafts lighten the hill behind them; foreground grass saturates (wet).
- **Fog**: banks lie in the hollows at about L85 (brighter than every land layer), ridge tops break through; the far range drops out first.
- **Snow** (`snow-hills-1`): lit snow L85–95, shaded snow blue-grey L53–60 (`#797E82`): the form is drawn by shading, not by hue. Dark marks (walls, shrubs, stones) carry the composition.
- **Dusk / low sun** (`dusk-lake-2`): lit slopes take the light colour and shadowed faces go to the sky colour; contrast between lit and shaded faces peaks; the lake carries a near-perfect mirror. (Our dusk is lavender, so lit faces go lilac-white, not orange.)
- **Night** (`night-lake-1`): land is near-black silhouette (L4–11) below a sky that is brighter toward the horizon (L14 → 24); form only at the crest line against the sky.
- **Season**: steppe and downs swing green (spring) → straw `#AD9561` (late summer); Palouse shows the same hills in bands of green and wheat. Snow is the winter extreme.

### Stylised quality (what the best illustrated work does)
- **Few flat planes + one detailed layer**: Firewatch and Old Man's Journey keep far/mid layers as near-flat colour with a soft vertical gradient and put texture (grass, rocks, trees) only in the nearest plane.
- **Crest light**: a thin lighter band along the top of a sunlit hill (Old Man's Journey) separates overlapping planes without outlines.
- **Grain, not noise**: a fine print grain over flat colour (Old Man's Journey, Sable) reads as material; large-scale repeating noise reads as a pattern (banned).
- **Limited palette per scene**: 3–5 hues per frame (Sable: mint/teal only; Alba: cyan, sand, sage), each layer one hue at one value.

## Shader and rendering standards

- The scene is GPU-rendered GLSL (WebGL2 now, AGSL later). **Banned for the scene:** flat 2D `<canvas>` shapes (circle suns, ellipse clouds), CSS gradients as the sky, SVG primitives, stock photos or traced art.
- Required qualities: multi-octave noise (Perlin-Worley / FBM) for every cloud form; physically motivated light (extinction, phase function, multiple-scatter approximation); atmospheric scattering and aerial perspective; linear-light compositing in float targets; post (bloom, grade, highlight shoulder, dither against OLED banding). Depth of field is the standard for precipitation particles (near blurred, far sharp).
- Everything is driven by SkyState + hour + seed. No weather is hard-coded into a shader branch that SkyState cannot reach.
- Keep the phone in budget: measure GPU ms per frame (PC and Galaxy S21) for every change; heavy work goes into the baked layers, not the per-frame passes.
- **Compile time (T9):** every call site of a function is a full inlined copy for the compiler (HLSL on ANGLE/D3D, and the GPU driver after it). Keep ONE call site for the cloud march (`clouds()`) and one cloud-density sample per pixel per program (`cDen`); add heavy work by widening an existing site, not by calling again. The scene is two programs from one source (`SKY_ONLY` slices 0–1, `LAND_ONLY` slices 2–3), compiled in parallel. Check any shader change with `tools/visual-loop/loadT.js` (page load to converged, ~25 s headless since terrain lock; was 410 s before T9) and `pixdiff.js` against the previous shots.
- Use the skills: `shader-art-coding` (noise, atmosphere, colour), `threejs-shaders` / `threejs-postprocessing` (techniques for bloom, DOF, lens effects — port, don't import).

## Mandatory visual self-critique loop

Before calling any visual pass done:
1. Start the server: `preview_start design-board` (or check http://127.0.0.1:8765 is up).
2. Shoot every state headlessly and build the comparison sheet:
   `cd tools/visual-loop && npm install` (once), then `node shoot.js` (or `node shoot.js rain night`). Output: `tools/visual-loop/out/<state>.png` and `out/compare.png` (ours | Samsung, per state). Headless Chromium uses the real GPU through ANGLE. The Playwright MCP (`playwright`, local scope) can do the same interactively in a fresh session.
3. Look at `compare.png` and each pair at full size. Critique, in writing, per state: sky ramp vs the table above (sample hex), horizon/haze falloff, cloud softness and rim light, sun/glare or moon/stars, precipitation depth, banding, depth layering, and what the glass would sit on.
4. Fix, re-shoot, repeat until each state matches the reference's polish. Then run `glass-check.js` (AA ≥ 4.5 on every state) and measure frame cost.
5. Log the round in LOG.md (what changed, measurements, what still differs) and STOP for the user.
