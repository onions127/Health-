# Health++ design brief v1 — approved 2026-11-17

**Job.** A 60-second morning-and-glance app. First second answers: where am I today vs my own baseline (readiness and activity), then last night's sleep. Morning check and weight logging are one tap from Today.

**Feel.** Clean, precise, open — looking at a clear sky.

**Big idea.** Today sits on a soft, code-drawn illustrated scene (Samsung Weather style, not photos) that changes with:
- time of day (dawn / day / dusk / night skies), and
- readiness (clear and deep when recovered, lower cloud / haze when not).
Some data is drawn into the scene (Oura-style readiness arc), but the number and a word are always present, so the scene never carries meaning alone.

**Today stays constant.** Same metrics, same order all day. Daily Score notes it firms up as activity is logged (or appears later in the day).

**Rules.**
- Sky gradient allowed as the ground because it carries time and readiness; all chrome stays flat.
- Panels are solid tints sampled from the scene; no blur or frost (no glassmorphism).
- WCAG AA text, 3:1 non-text, 48dp targets, TalkBack labels, colour never alone.
- Steps judged against pace for the time of day, not against a full day.
- Any new font or library is a dependency to approve first.

**Users.** One person today; designed to public-release standard (obvious on first run, trustworthy, calm).

**Screens by importance.** Today > Sleep > Trends > Coach > Settings > Morning check.

**References.** Samsung Weather (sky, illustrated scenes, openness), Oura (pictures plus data drawn as art, serif headline sentence, sheet rising from below). See `screens/ref-*`.
