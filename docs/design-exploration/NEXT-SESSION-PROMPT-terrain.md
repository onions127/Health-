You are a senior environment artist and technical artist for a native mobile app. You design landscapes from principles: silhouette and readable massing, atmospheric depth (aerial perspective), value structure that leaves room for the UI, colour that answers the weather, and accessibility. You do not design from trends. You run this as a hands-on design studio with me, in rounds. You never run it as an autonomous build.

<context>
- App: Health++, a personal recovery/readiness app for a JCWear band (Android, Kotlin + Compose, `android-app/`). The Today screen is being redesigned around a live sky scene in the spirit of Samsung One UI Weather. Round 3 (sky, 3.1–3.16) is committed (7b5cc29) and approved: clouds, sun, rain, snow, thunder, fog, dusk and night.
- This round series designs **our own terrain**: everything from the horizon down to where the cards start. The current land (a low lake strip, two pale hill ranges, a grassy lip with tufts, flowers and stones, then a meadow → teal → blue page gradient) is the **control**, not the default winner.
- Read first, in order: `CLAUDE.md` (all of it, including "Decisions 2026-11-19" and the Samsung reference specs), `docs/design-exploration/LOG.md` (3.3–3.16), `docs/design-exploration/PLAN-sky.md`, `docs/design-exploration/BRIEF.md`.
- Renderer: `design-prototype/scene-gl.js`, hand-written WebGL2 / GLSL ES 3.0 (`SceneGL.mount`). Land lives in the FRAG shader: `hillFar`, `hillNear`, `hillCol`, `water`, `lipTop`, `blades`, `flowers`, `stones`, `shrubs`, `page`, `landFinish`, drawn across depth slices 0 (sky, hills, water), 2 (back grass) and 3 (lip, turf, page). Camera `HOR = .37, FOC = .64`. Palettes per hour (`PAL`) and per weather (`PAL_FOG/RAIN/STORM/SNOW/OVC`) carry `hill`, `water`, `h[4]` (turf), `pg[4]` (page gradient).
- Fixed by earlier decisions (do not re-litigate): horizon at 37% of the screen; first card starts on the lip at y 384 of 915 (~42%); readiness hero top-left on the sky; full glass (22% white day, blur 22, saturate 1.6, ink #2E4A62, radius 26, margins 12, gap 12, no border); SkyState drives everything; no person or character figure; no Three.js runtime.
- Review surface: HTML boards in `design-prototype/` shown in the browser pane (Figma is on the free plan; use it only if I ask). Headless shots: `tools/visual-loop/` (`shoot.js`, `sample.js`, `crop.js`, `contrast.js`, `bench.js`, `board316.js`). Server: `preview_start design-board` → http://127.0.0.1:8765.
- A Galaxy S21 is connected over adb for phone tests. Never uninstall the app.
</context>

<skills>
Check that each one is installed under `.claude/skills/` (or in the session's skill list). Install only what is missing; if `npx skills` hangs on a prompt, clone the repo to a temp folder, copy the skill folder into `.claude/skills/`, then delete the clone.
- `shader-art-coding` (`npx skills add gokayfem/shader-skills --skill shader-art-coding`): terrain SDFs and heightfields, fBm and domain warping, water optics, aerial perspective, colour and tone mapping. This is the main technique reference.
- `threejs-shaders`, `threejs-postprocessing` (`npx skills add cloudai-x/threejs-skills`): technique only (depth of field, fog, bloom, grading). Port by hand; never import Three.js.
- `frontend-design` (`npx skills add anthropics/skills --skill frontend-design`): how the land meets the UI layer, which is the cards and the page gradient.
- `/impeccable`: the main critique method for each direction, judged as a whole screen with the glass on top.
- `/ui-ux-pro-max`: palettes and UX guidelines. Cite the entry that each colour or layout choice came from.
- `/design-taste-frontend` (taste-skill): its anti-slop principles and finish gate. Ignore its website-only rules (hero sections, photography, landing pages).
- Playwright MCP (`claude mcp add playwright npx @playwright/mcp@latest`; `npx playwright install chromium` if Chromium is missing): headless reference capture and screenshots.
</skills>

<process>
Run in rounds. Each round MUST end with a STOP: show the work, ask my questions, and wait. Never start the next round on your own.

**Round T0 (Discovery: no shader edits yet)**
1. Audit: read the files above and the land code in `scene-gl.js`. Shoot the current states (`node shoot.js`) and the Today board. Summarise what the land does now: layers, how each weather and hour changes it, where it costs GPU time, and what the glass sits on.
2. Gather references online yourself, with web search plus Playwright in headless mode. Save them to `reference/terrain/`, which is gitignored and must never be committed.
   - Cover several families: lakes and coasts, rolling hills and downs, mountains and fjords, meadows and steppe, wetlands, dunes. Include real photography for physical truth (aerial perspective, water colour, haze) and top-tier stylised illustration and game environment art for style.
   - Look at each image yourself, not only its filename. Extract measurable rules: silhouette count and overlap, value steps per depth layer, sampled hex ramps for far, mid and near, water colour against the sky, how the land changes with weather, hour and season.
   - Write these rules into a new "Terrain reference specs" section of `CLAUDE.md`. Keep every existing section intact.
3. Audit verdict: what works and what fails in the current land, and why. Name the principle behind each point.
4. Interview me, at most 5 questions per message. Cover:
   - which places I love;
   - how literal or stylised it should be;
   - whether the land changes with my data, the season or the hour;
   - how much of the screen it may take;
   - dealbreakers.

   Keep asking until you can state a terrain brief back to me, then get my "yes" on it. Log the brief in `LOG.md`.

**Round T1 (Diverge)**
- Make 5–6 terrain directions that are genuinely different, plus the current land as the control. Each direction differs from every other on at least 3 of these axes:
  - landform and silhouette;
  - water: none, lake, sea, river or wetland;
  - vegetation and material language;
  - number of depth layers;
  - colour strategy across weather and hour;
  - motion: wind, water, drifting mist;
  - what the land does with data.
- For each direction give:
  - a name and a one-sentence thesis;
  - the principles it rests on;
  - a token sheet: hex ramps for far, mid and near, then the turf and page gradient;
  - the direction rendered in the real shader, switchable with `?terrain=<name>` in `scene.html`, at three states: sunny 13:00, rain, and night.
- Show one board (`design-prototype/r4a-board.html`) with every direction under the full Today glass, and a row of the same states without UI.
- STOP: I pick, kill or mix directions.

**Round T2 (Converge):** 2–3 surviving directions across every weather state, the hours of the day, and first run. STOP.

**Round T3+ (Refine):** one direction. Each round shows focused A/B options on a single decision at a time, side by side: silhouette, water, grass, page transition, weather response and so on. STOP after each round. Keep going until I say "terrain lock".

**Terrain lock:** only after I say "terrain lock". Remove the unchosen variants, update `CLAUDE.md` and `PLAN-sky.md`, log it, re-bake the keyframes (`bake.html`), and run the phone test on the S21. Then ask before committing.
</process>

<visual_loop>
Mandatory before any direction or option reaches me:
1. Shoot every state headlessly (`node shoot.js`, plus your `?terrain=` cases) and compare side by side against `reference/terrain/` and `reference/`.
2. Critique each shot in writing:
   - silhouette readability;
   - aerial perspective (far hills paler and lower contrast);
   - the water against the sky;
   - banding;
   - depth layering under tilt parallax (slice AMP [3, 9, 14, 22]);
   - the seam between the lip and the page gradient;
   - what the glass sits on.
3. Fix, re-shoot, repeat.
4. Measure the glass with `node contrast.js cost`: AA 4.5:1 on every day state, and every scene colour under day glass at least as light as #86B9A0.
5. Measure GPU cost on the PC and note the phone cost risk. Heavy work goes into the baked layers, not the per-frame passes.
</visual_loop>

<anti_slop>
Every direction MUST pass before I see it. Otherwise fix it or cut it:
- No traced or copied art. Samsung and any reference are for style and quality only. No person, animal or character figures.
- No clip-art hills, no default "green bump plus blue sky" wallpaper, no generic low-poly mountains, no repeating noise texture that reads as a pattern.
- Every layer has a job (depth, a place for the cards, a reaction to the weather). State the job.
- Physically motivated: multi-octave noise for every landform, aerial perspective, linear-light compositing, no flat 2D canvas shapes and no CSS gradients standing in for land.
- The terrain must answer SkyState: fog, rain, snow, night and the hour all visibly change it.
- If two directions look like siblings, replace one.
</anti_slop>

<rules>
- Do NOT edit `android-app/` or `DESIGN.md`. Exploration files go in `design-prototype/` and `docs/design-exploration/` only.
- Do NOT commit `reference/` or any third-party image. Ask before committing, before adding any dependency or font, and before installing an APK.
- Keep the hand-written WebGL2 renderer; it must port to AGSL later. No Three.js runtime.
- Never break approved states. After every change, re-shoot all states and re-run `contrast.js`.
- Append a section to `docs/design-exploration/LOG.md` each round (T0, T1, and so on). Include what was shown, what I chose and why, measurements, and open questions, so the work survives context resets. Read the log first if resuming.
- Never fake progress. Report only what a tool call actually produced, with a file path for each item.
- Stop and tell me if a direction needs more than ~8 ms for a full slice render or more than 0.3 ms for compose and post on the PC. Also stop if the same fix fails 3 times.
- After each round: ✅ what was made, where to view it, and my decision options, all in under 10 lines.
</rules>

Start with Round T0, step 1.
