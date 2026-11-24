// BD5: the scores the app computes, ported from android-app/…/domain (ScoreBreakdown.kt, SleepAndReadinessEngine.kt),
// plus S2, a proposed stricter sleep score (prototype only; the app is untouched until lock).
// A night: { min (asleep, minutes), share (deep + REM of staged time), eff (%), off (minutes the midpoint is from your
// usual), onset (minutes from noon: 23:15 = 675, 02:00 = 840, 03:00 = 900) }.
(function (root) {
  const clamp = (x, a, b) => Math.min(b, Math.max(a, x));

  // The app's sleep score (sleepScoreParts, stages available).
  function sleepApp(n) {
    const p = [['Duration', 0.40, clamp(n.min / 450 * 100, 0, 100)], ['Deep + REM share', 0.20, clamp(n.share / 0.38 * 100, 0, 100)],
      ['Efficiency', 0.10, n.eff], ['Regular timing', 0.15, clamp((180 - n.off) / 150 * 100, 0, 100)],
      ['Asleep by 02:00', 0.15, clamp((960 - n.onset) / 120 * 100, 0, 100)]];
    return finish(p, n.onset >= 900 ? 70 : 100);
  }

  // S2: the same five parts and weights; three of them stop giving credit for a poor night.
  //  Duration: 100 at 7h30, 0 at 4h30 (the app: 0 at no sleep, so 4h40 still earned 62).
  //  Deep + REM: 100 at 38%, 0 at 20% (the app: 0 at 0%).
  //  Efficiency: 100 at 95%, 0 at 75% (the app: the raw %, so a broken 78% night earned 78).
  function sleepS2(n) {
    const p = [['Duration', 0.40, clamp((n.min - 270) / 180 * 100, 0, 100)], ['Deep + REM share', 0.20, clamp((n.share - 0.20) / 0.18 * 100, 0, 100)],
      ['Efficiency', 0.10, clamp((n.eff - 75) / 20 * 100, 0, 100)], ['Regular timing', 0.15, clamp((180 - n.off) / 150 * 100, 0, 100)],
      ['Asleep by 02:00', 0.15, clamp((960 - n.onset) / 120 * 100, 0, 100)]];
    return finish(p, n.onset >= 900 ? 70 : 100);
  }

  function finish(p, cap) {
    return { score: Math.min(cap, Math.round(p.reduce((s, x) => s + x[1] * x[2], 0))), parts: p.map((x) => [x[0], x[1] * x[2]]) };
  }

  // The app's readiness (readinessParts): b = { hrv, rhr, sleepScore, spo2, tempDelta }, base = { hrv, rhr } (30 days before).
  // pts = whole points per part that add up to the score (as the Readiness page draws them).
  function readiness(b, base) {
    const far = Math.abs(b.tempDelta) > 0.5;
    const ex = [0.40 * clamp(75 + (b.hrv / Math.max(1, base.hrv) - 1) * 125, 20, 100), 0.25 * clamp(78 + (base.rhr - b.rhr) * 4.5, 20, 100),
      0.25 * b.sleepScore, 0.10 * clamp(95 - (far ? 25 : 0) - (b.spo2 < 95 ? (95 - b.spo2) * 8 : 0), 20, 100)];
    const score = Math.round(ex.reduce((s, x) => s + x, 0)), pts = ex.map(Math.floor);
    let left = score - pts.reduce((s, x) => s + x, 0);
    ex.map((e, k) => [e - pts[k], k]).sort((a, c) => c[0] - a[0]).forEach(([, k]) => { if (left > 0) { pts[k]++; left--; } });
    return { score, pts, far };
  }

  const recovery = (hrv, rhr) => clamp(Math.round(hrv * 0.92 + (90 - rhr) * 0.72), 0, 100);
  const daily = (ready, sleep, strain) => Math.round(ready * 0.42 + sleep * 0.36 + strain * 0.22);

  // "Your normal" (MetricInsights.baseline): mean of the 30 days before, ± max(SD, the metric's floor; 3 for scores).
  function normal(values, floor) {
    const m = values.reduce((s, x) => s + x, 0) / values.length, sd = Math.sqrt(values.reduce((s, x) => s + (x - m) ** 2, 0) / values.length);
    const hw = Math.max(sd, floor);
    return { mean: m, avg: Math.round(m), lo: Math.round(m - hw), hi: Math.round(m + hw) };
  }

  // The designed bad days' nights (VP1, ST1, BD2): S2 gives each the sleep score its readiness was built on
  // (haze 70, illness 56, very poor 42, storm 40); the app gives 82–88 and 72–73. PD1's poor day is 4 Nov in data.js.
  const BAD_NIGHTS = {
    hz: { min: 410, share: 0.27, eff: 80, off: 40, onset: 710 },    // 6h 50m, asleep 23:50, light
    ill: { min: 365, share: 0.23, eff: 79, off: 30, onset: 710 },   // 6h 05m, restless, little deep or REM
    vp: { min: 280, share: 0.32, eff: 81, off: 100, onset: 800 },   // 4h 40m, asleep 01:20
    st: { min: 290, share: 0.27, eff: 82, off: 90, onset: 780 }     // 4h 50m, asleep 01:00
  };

  // Which sleep score the prototype runs: S2 unless ?sleep=app.
  const q = typeof location !== 'undefined' ? new URLSearchParams(location.search).get('sleep') : null;
  const SLEEP = q === 'app' ? 'app' : 's2';
  const api = { BAD_NIGHTS, sleepApp, sleepS2, sleep: SLEEP === 'app' ? sleepApp : sleepS2, SLEEP, readiness, recovery, daily, normal };
  root.SCORES = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
