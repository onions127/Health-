/* Round B1: the weather of the body (PLAN-sky.md section 4, D1-D6). One pure function from today's body data and the hour to a
   SkyState for SceneGL, plus the note shown when the sky is tapped (D4) and the TalkBack text. Every signal owns one layer, so the sky
   never says something the numbers do not:
     readiness          -> the low-cloud regime (>= 80 fair, 65-79 partly, 50-64 stratocumulus, < 50 stratus)      SceneGL.weather(r)
     sleep              -> morning valley fog, from first light, thinning by late morning                         fog, fogH
     HRV vs your normal -> the high cloud: a clean high sky, then streaks, then a milky veil with a halo          cirrus, ciHook, ciVeil
     activity pace      -> fair-weather cumulus building through the afternoon                                    tall, cover
     skin temp + RHR    -> haze when both are up; light rain only for likely illness (D6);
                           a thunderstorm only for likely illness and readiness under 35 (D2)
     missing data       -> a neutral fair sky, no judgement
   All thresholds are relative to the person's own normal (data.js METRICS normalMin / normalMax / avg30). Values are B1 proposals.
   Ports to Kotlin as data + arithmetic: no randomness, no state. */
(function (G) {
  const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v)), sstep = (a, b, x) => { const t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t); };
  const fmtMin = m => `${Math.floor(m / 60)} h ${String(Math.round(m % 60)).padStart(2, '0')} m`;

  // Thresholds (B1). Illness: both skin temperature and resting HR above the person's own baseline.
  const T = {
    fogSleepScore: [78, 50],        // sleep score: no fog at 78+, full valley fog at 50 or under
    fogDeficitMin: [20, 100],       // minutes under the usual: no fog at 20, full fog at 100
    fogHours: [5, 7, 10.5, 12],     // fog appears from first light (5-7) and is gone by noon, thinning from 10:30
    hrvLow: [0, -1.6],              // HRV against the normal band, in half-band units (0 = at the bottom of normal): streaks from 0, veil by -1.6
    activity: [1.0, 1.6],           // activity pace (steps or strain against the usual for this time of day): building from 1.0, full at 1.6
    buildHours: [10, 15, 19, 21],   // cumulus build from late morning, held through the afternoon, settling after sunset
    hazeSkin: .3, hazeRhr: 3,       // haze: skin temp >= +0.3 C and resting HR >= 3 bpm over the usual
    illSkin: .5, illRhr: 5,         // likely illness (D6): skin temp >= +0.5 C and resting HR >= 5 bpm over the usual
    stormReadiness: 35,             // D2: a thunderstorm only for likely illness and readiness under 35
  };

  // b: { readiness, sleepScore, sleepMin, sleepUsual, hrv, hrvMin, hrvMax, steps, stepsUsual, strain, strainUsual, skinTemp, rhr, rhrUsual }
  // (any may be missing). hour: 0-24. Returns { sky (full SkyState), layers (which signals act, 0..1), notes (sentences), talkback }.
  function bodySky(b, hour, SceneGL) {
    const P = SceneGL.PRESETS, has = k => b && b[k] != null && !Number.isNaN(b[k]);
    const sky = SceneGL.weather(has('readiness') ? b.readiness : null), L = {}, notes = [];
    const regime = SceneGL.regimeOf(has('readiness') ? b.readiness : null);

    // readiness: the regime
    if (has('readiness')) {
      const word = { fair: 'Mostly clear', partly: 'Partly cloudy', stratocumulus: 'Broken cloud', stratus: 'Low grey cloud' }[regime];
      const d = has('readinessUsual') ? Math.round(b.readiness - b.readinessUsual) : null;
      notes.push(`${word}: readiness ${b.readiness}${d != null ? `, ${Math.abs(d)} ${d >= 0 ? 'above' : 'below'} your normal` : ''}.`);
    } else notes.push('A plain fair sky: there is no readiness score yet today.');

    // sleep: morning valley fog
    let fog = 0;
    if (has('sleepScore')) fog = Math.max(fog, sstep(T.fogSleepScore[0], T.fogSleepScore[1], b.sleepScore));
    if (has('sleepMin') && has('sleepUsual')) fog = Math.max(fog, sstep(T.fogDeficitMin[0], T.fogDeficitMin[1], b.sleepUsual - b.sleepMin));
    const morning = sstep(T.fogHours[0], T.fogHours[1], hour) * (1 - sstep(T.fogHours[2], T.fogHours[3], hour));
    L.fog = fog * morning;
    if (L.fog > .02) {
      Object.assign(sky, { fog: Math.max(sky.fog, .90 * L.fog), fogH: .25, haze: Math.max(sky.haze, .10 * L.fog), wind: Math.min(sky.wind, .15 + .5 * (1 - L.fog)) });
      sky.pal = P.valleyFog.pal; sky.palK = .85 * L.fog;
      const less = has('sleepMin') && has('sleepUsual') ? b.sleepUsual - b.sleepMin : null;
      notes.push(`Morning mist in the valley: you slept ${has('sleepMin') ? fmtMin(b.sleepMin) : 'less well'}${less != null && less > 5 ? `, ${Math.round(less)} min less than usual` : ''}. It clears by late morning.`);
    }

    // HRV: the high cloud (the base sky is a clean high sky with a little cirrus)
    if (has('hrv') && has('hrvMin') && has('hrvMax')) {
      const half = Math.max(1, (b.hrvMax - b.hrvMin) / 2), z = (b.hrv - b.hrvMin) / half;   // 0 at the bottom of normal, 2 at the top
      const low = sstep(T.hrvLow[0], T.hrvLow[1], z);
      L.hrv = low;
      const veil = sstep(.6, 1, low);
      Object.assign(sky, { cirrus: .30 + .62 * low, ciHook: low < .6 ? .9 * sstep(0, .5, low) : .9 * (1 - veil), ciVeil: veil, haze: Math.max(sky.haze, .05 * veil) });
      if (low < .05) sky.ciHook = 0;
      if (low > .05) notes.push({ hrv: 1, t: `${veil > .5 ? 'A milky veil of high cloud' : 'Streaks of high cloud'}: HRV ${b.hrv} ms, under your normal ${b.hrvMin}-${b.hrvMax}.` });
    }

    // activity: cumulus building through the afternoon (only where there is cumulus to build)
    let pace = null;
    if (has('steps') && has('stepsUsual')) { const dayFrac = clamp((hour - 7) / 14, .05, 1); pace = b.steps / Math.max(1, b.stepsUsual * dayFrac); }
    if (has('strain') && has('strainUsual')) pace = Math.max(pace || 0, b.strain / Math.max(1, b.strainUsual));
    if (pace != null) {
      const act = sstep(T.activity[0], T.activity[1], pace), when = sstep(T.buildHours[0], T.buildHours[1], hour) * (1 - sstep(T.buildHours[2], T.buildHours[3], hour));
      L.activity = act * when * (regime === 'fair' || regime === 'partly' ? 1 : .4);
      if (L.activity > .02) {
        Object.assign(sky, { tall: Math.min(1, sky.tall + .30 * L.activity), cover: Math.min(.75, sky.cover + .18 * L.activity) });
        notes.push(`Clouds building through the afternoon: ${has('steps') ? `${b.steps.toLocaleString('en-US')} steps` : `strain ${b.strain}`}, ahead of your usual pace.`);
      }
    }

    // skin temperature + resting HR: haze, then light rain (likely illness), then a storm (likely illness and readiness under 35)
    if (has('skinTemp') && has('rhr') && has('rhrUsual')) {
      const dR = b.rhr - b.rhrUsual, hazy = b.skinTemp >= T.hazeSkin && dR >= T.hazeRhr, ill = b.skinTemp >= T.illSkin && dR >= T.illRhr;
      const storm = ill && has('readiness') && b.readiness < T.stormReadiness;
      L.load = hazy ? 1 : 0; L.ill = ill ? 1 : 0; L.storm = storm ? 1 : 0;
      const signs = `skin temperature ${b.skinTemp >= 0 ? '+' : ''}${b.skinTemp.toFixed(1)} °C and resting HR ${b.rhr} bpm (usual ${b.rhrUsual}), both up`;
      if (ill) for (let i = notes.length - 1; i >= 0; i--) if (notes[i].hrv) notes.splice(i, 1);   // under rain the high cloud is hidden: say only what the sky shows
      if (storm) { Object.assign(sky, P.thunder, { cirrus: 0 }); notes.push(`A storm: ${signs}, and readiness ${b.readiness}. This can mean your body is fighting something; rest if you can.`); }
      else if (ill) { Object.assign(sky, P.rain, { cirrus: 0, fog: Math.max(P.rain.fog, sky.fog * .6) }); notes.push(`Light rain: ${signs}. This can be an early sign of illness.`); }
      else if (hazy) { Object.assign(sky, { haze: Math.max(sky.haze, .30), grey: Math.max(sky.grey, .16), cirrus: Math.max(sky.cirrus, .55), ciVeil: Math.max(sky.ciVeil || 0, .45) }); notes.push(`A humid haze: ${signs}. Your body is under some load.`); }
    }

    for (let i = 0; i < notes.length; i++) if (typeof notes[i] !== 'string') notes[i] = notes[i].t;
    const talkback = 'Sky: ' + notes.join(' ');
    return { sky, layers: L, notes, talkback, regime };
  }

  // today's inputs from the prototype data (data.js): the person's own normals come from METRICS
  function fromHealthData(HD, day) {
    const t = day || HD.today, M = HD.METRICS, prev = HD.days.slice(-31, -1), mean = k => prev.reduce((a, d) => a + d[k], 0) / prev.length;
    return { readiness: t.readiness, readinessUsual: M.readiness.avg30, sleepScore: t.sleepScore, sleepMin: t.sleepMin, sleepUsual: Math.round(mean('sleepMin')),
      hrv: t.hrv, hrvMin: M.hrv.normalMin, hrvMax: M.hrv.normalMax, steps: t.steps, stepsUsual: M.steps.avg30, strain: t.strain, strainUsual: M.strain.avg30,
      skinTemp: t.skinTemp, rhr: t.rhr, rhrUsual: Math.round(mean('rhr')) };
  }

  G.BodySky = { bodySky, fromHealthData, T };
})(typeof window !== 'undefined' ? window : globalThis);
