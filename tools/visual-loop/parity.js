// LOCK phase 1: parity fixtures. The prototype's own functions (scores.js, body-sky.js, coach-rules.js, glass-rule.js) run
// over the sample data and write what they give into android-app/app/src/test/resources/parity/*.tsv; the Kotlin ports must
// give the same.   node parity.js   (needs the design-board server on :8765)
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const OUT = path.join(__dirname, '..', '..', 'android-app', 'app', 'src', 'test', 'resources', 'parity');
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch(), p = await b.newPage();
  p.on('pageerror', e => console.log('page error', e.message));
  await p.goto('http://127.0.0.1:8765/co2-board.html', { waitUntil: 'networkidle' });
  await p.addScriptTag({ url: 'http://127.0.0.1:8765/scene-gl.js' });
  await p.addScriptTag({ url: 'http://127.0.0.1:8765/glass-rule.js' });
  const files = await p.evaluate(() => {
    const SC = SCORES, D = HEALTH_DATA.days, T = (rows) => rows.map((r) => r.join('\t')).join('\n') + '\n';
    const out = {};

    // 1 · Sleep: every night as data.js scores it (the same history and median), S2 and the app's old formula.
    const nightMid = (d) => d.night.onset + d.sleepMin / 2, sleep = [['min', 'share', 'eff', 'off', 'onset', 's2', 'app', 's2parts']];
    D.forEach((d, i) => {
      const hist = (n) => (i >= 3 ? D.slice(Math.max(0, i - n), i) : D.slice(0, 7).filter((x) => x !== d));
      const mids = hist(14).map(nightMid).sort((a, c) => a - c), n = mids.length;
      const med = n % 2 ? mids[n >> 1] : (mids[n / 2 - 1] + mids[n / 2]) / 2;
      const nt = { min: d.sleepMin, share: d.night.share, eff: d.night.eff, off: Math.abs(nightMid(d) - med), onset: d.night.onset };
      const s2 = SC.sleepS2(nt);
      sleep.push([nt.min, nt.share, nt.eff, nt.off, nt.onset, s2.score, SC.sleepApp(nt).score, s2.parts.map((x) => x[1].toFixed(6)).join(',')]);
    });
    Object.values(SC.BAD_NIGHTS).forEach((nt) => { const s2 = SC.sleepS2(nt); sleep.push([nt.min, nt.share, nt.eff, nt.off, nt.onset, s2.score, SC.sleepApp(nt).score, s2.parts.map((x) => x[1].toFixed(6)).join(',')]); });
    out['sleep_s2.tsv'] = T(sleep);

    // 2 · Bodies for BodySky and CoachRules: every day with 30 days before it (as CR1 builds them), at nine hours with steps and
    // strain accruing through the day; the six drawn days (COACH_DAYS) at their hours; the first week (FIRST_WEEK); an empty body.
    const S = SCORES, mean = (a) => a.reduce((s, x) => s + x, 0) / a.length, dayFrac = (h) => Math.min(1, Math.max(.05, (h - 7) / 14));
    const HOURS = [5.33, 7.25, 10, 13, 15.17, 17.5, 19.75, 20.5, 23.17], bodies = [];
    for (let i = 30; i < D.length; i++) {
      const d = D[i], p = D.slice(i - 30, i), p14 = D.slice(i - 14, i).map((x) => x.night.onset).sort((a, c) => a - c), hn = S.normal(p.map((x) => x.hrv), 3);
      const b = { readiness: d.readiness, readinessUsual: Math.round(mean(p.map((x) => x.readiness))), sleepScore: d.sleepScore, hrv: d.hrv, hrvMin: hn.lo, hrvMax: hn.hi,
        rhr: d.rhr, rhrUsual: Math.round(mean(p.map((x) => x.rhr))), skinTemp: Math.round(d.readyBase.tempDelta * 10) / 10, sleepMin: d.sleepMin,
        sleepUsual: Math.round(mean(p.map((x) => x.sleepMin))), stepsUsual: Math.round(mean(p.map((x) => x.steps))), strainUsual: Math.round(mean(p.map((x) => x.strain))),
        onsetUsual: (p14[6] + p14[7]) / 2, nights: 14 };
      HOURS.forEach((h) => bodies.push([{ ...b, steps: Math.round(d.steps * dayFrac(h)), strain: Math.round(d.strain * dayFrac(h)) }, h]));
    }
    const C = COACH_DAYS;
    ['today', 'pd', 'hz', 'vp', 'ill', 'st'].forEach((k) => [[0, 5.33], [0, 7.25], [1, 15.17], [2, 19.75], [2, 20.5], [3, 23.17]].forEach(([i, h]) => bodies.push([C.at(C[k], i), h])));
    FIRST_WEEK.forEach((f) => [5.33, 7.25, 15.17, 19.75, 23.17].forEach((h) => bodies.push([{ ...f, steps: Math.round((f.steps || 0) * dayFrac(h)), strain: Math.round((f.strain || 0) * dayFrac(h)) }, h])));
    bodies.push([{}, 7.25], [{}, 15.17], [{}, 23.17]);
    // the rarest branches: a low day ahead of pace (pm.over), haze ahead of and behind pace
    bodies.push([{ ...C.at(C.pd, 1), steps: 9000, strain: 60 }, 15.17], [{ ...C.at(C.hz, 1), steps: 9500, strain: 70 }, 15.17], [{ ...C.at(C.hz, 1), steps: 900, strain: 8 }, 15.17]);

    const IN = ['readiness', 'readinessUsual', 'sleepScore', 'sleepMin', 'sleepUsual', 'hrv', 'hrvMin', 'hrvMax', 'steps', 'stepsUsual', 'strain', 'strainUsual',
      'skinTemp', 'rhr', 'rhrUsual', 'onsetUsual', 'nights'];
    const cell = (v) => (v == null || Number.isNaN(v) ? '' : String(v));
    const SKYK = ['style', 'paintCov', 'cirrus', 'ciHook', 'ciVeil', 'ciAng', 'cover', 'tall', 'grey', 'haze', 'sheet', 'sType', 'sb', 'st', 'fog', 'fogH', 'rain', 'wind', 'snow', 'bolt', 'palK'];
    const P = SceneGL.PRESETS, palName = (pal) => (pal == null ? 'none' : pal === P.valleyFog.pal ? 'fog' : pal === P.rain.pal ? 'rain' : pal === P.thunder.pal ? 'storm' : pal === P.stratus.pal ? 'overcast' : 'other');
    const sky = [['hour', ...IN, 'regime', ...SKYK, 'pal', 'lFog', 'lHrv', 'lActivity', 'lLoad', 'lIll', 'lStorm', 'talkback']];
    const coach = [['hour', ...IN, 'rule', 'line', 'detail']];
    bodies.forEach(([b, h]) => {
      const ins = IN.map((k) => cell(b[k])), r = BodySky.bodySky(b, h, SceneGL), L = r.layers;
      sky.push([h, ...ins, r.regime, ...SKYK.map((k) => cell(r.sky[k] == null ? 0 : r.sky[k])), palName(r.sky.pal), ...['fog', 'hrv', 'activity', 'load', 'ill', 'storm'].map((k) => cell(L[k] == null ? '' : L[k])), r.talkback]);
      const c = CoachRules.read(b, h);
      coach.push([h, ...ins, c.rule, c.line, c.detail]);
    });
    out['body_sky.tsv'] = T(sky);
    out['coach_rules.tsv'] = T(coach);
    return out;
  });
  // 3 · Glass: g2.js's measurements of the saved renders (PS1 at 05:20, TW1 at 20:30; Coach's layout: chat glass y 236–851,
  // nav, sky text x <= 262). The renders stay in design-prototype/; the test reads them from there.
  const glass = [['png', 'mode', 'a', 'needLight', 'needDark', 'navA', 'cardP', 'cardS', 'navS', 'skyMode', 'scrimA', 'sky', 'btnFill', 'btnC']];
  for (const dir of ['ps1', 'tw1']) {
    const m = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'design-prototype', dir, 'measure.json'), 'utf8')).m;
    for (const [id, f] of Object.entries(m)) { const g = f['3'];
      glass.push([`${dir}/${id}.png`, g.mode, g.a, g.needLight, g.needDark, g.navA, g.cardP, g.cardS, g.navS, g.skyMode, g.scrimA, g.sky, g.btn.fill, g.btnC]); }
  }
  files['glass_g2.tsv'] = glass.map((r) => r.join('\t')).join('\n') + '\n';
  for (const [f, s] of Object.entries(files)) { fs.writeFileSync(path.join(OUT, f), s); console.log(f, s.split('\n').length - 2, 'rows'); }
  await b.close();
})();
