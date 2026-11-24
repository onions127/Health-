/* Round CR1: the rules that pick Coach's read (the headline over the sky, and its detail line).
   One pure function from the body and the hour, like BodySky, and on BodySky's own thresholds (haze, likely illness,
   storm, pace 1.15), so the sky and the read cannot disagree. First match wins, per part of the day:
     morning (to 12:00)   the plan, from this morning's body
     afternoon (to 18:00) progress against your usual day (steps against your usual for the hour)
     dusk (to 21:30)      how hard the day was (strain against your usual by this hour) and what recovery needs
     night                bedtime against your usual (earlier after a short night)
   The detail line always holds the numbers the read rests on. Ports to Kotlin as data + arithmetic.
   b: { readiness, hrv, hrvMin, hrvMax, rhr, rhrUsual, skinTemp, sleepMin, sleepUsual, steps, stepsUsual, strain,
        strainUsual, onsetUsual (minutes from noon) } */
(function (G) {
  const T = { rest: 50, easy: 65, ready: 80, ahead: 1.15, behind: 0.85, hard: 1.15, light: 0.85, short: 30, early: 30 };
  const BT = () => (G.BodySky && G.BodySky.T) || { hazeSkin: .3, hazeRhr: 3, illSkin: .5, illRhr: 5, stormReadiness: 35 };
  const n = x => Math.round(x).toLocaleString('en-US');
  const hm = m => `${Math.floor(m / 60)}h ${String(Math.round(m % 60)).padStart(2, '0')}m`;
  const clock = h => `${String(Math.floor(h)).padStart(2, '0')}:${String(Math.round((h % 1) * 60)).padStart(2, '0')}`;
  const at = mfn => { const t = ((Math.round(mfn / 5) * 5) + 720) % 1440; return `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`; };
  const dayFrac = h => Math.min(1, Math.max(.05, (h - 7) / 14));   // as BodySky and Trends: the usual day accrues 07:00 → 21:00

  function body(b) {   // the signs BodySky reads
    const t = BT(), dR = b.rhr - b.rhrUsual, ill = b.skinTemp >= t.illSkin && dR >= t.illRhr;
    return { dR, haze: b.skinTemp >= t.hazeSkin && dR >= t.hazeRhr, ill, storm: ill && b.readiness < t.stormReadiness };
  }
  function hrvWord(b) {
    const half = Math.max(1, (b.hrvMax - b.hrvMin) / 2);
    return b.hrv < b.hrvMin - half ? 'well under your normal' : b.hrv < b.hrvMin ? 'below your normal' : b.hrv > b.hrvMax ? 'above your normal'
      : b.hrv === b.hrvMax ? 'at the top of your normal' : 'within your normal';
  }

  function read(b, hour) {
    const s = body(b), r = b.readiness;
    const signs = `Skin +${b.skinTemp.toFixed(1)} °C · resting HR ${b.rhr}, ${s.dR} above usual`, rh = `Readiness ${r} · HRV ${b.hrv} ms, ${hrvWord(b)}`;
    const R = (rule, line, detail) => ({ rule, line, detail });
    if (hour < 12) {
      if (s.storm) return R('am.storm', 'Rest. Your body needs it.', signs);
      if (s.ill) return R('am.ill', 'Rest, and see how you feel.', signs);
      if (r < T.rest) return R('am.rest', 'Rest today.', rh);
      if (r < T.easy) return R('am.easy', 'An easy day will pay off tomorrow.', rh);
      if (s.haze) return R('am.haze', 'A steady day. Keep it moderate.', signs);
      if (r < T.ready) return R('am.steady', 'A steady day: train, but not to the limit.', rh);
      return R('am.ready', 'A good day to train hard.', rh);
    }
    if (hour < 18) {
      const p = b.steps / Math.max(1, b.stepsUsual * dayFrac(hour)), pace = p >= T.ahead ? 'ahead' : p < T.behind ? 'behind' : 'on';
      const d = `${n(b.steps)} steps by ${clock(hour)} · strain ${b.strain}, your usual ${b.strainUsual}`;
      if (s.storm) return R('pm.storm', 'Rest is the plan today.', d);
      if (s.ill) return R('pm.ill', 'Keep it a rest day.', d);
      if (r < T.easy && pace === 'ahead') return R('pm.over', 'More than planned. Ease off now.', d);
      if (r < T.rest) return R('pm.rest', 'Resting so far. Good.', d);
      if (r < T.easy) return R('pm.easy', 'Easy so far. Keep it that way.', d);
      if (s.haze) return R('pm.haze.' + pace, { ahead: 'Ahead of pace. Keep it moderate.', on: 'On pace. Keep it moderate.', behind: 'A quiet day. Keep it moderate.' }[pace], d);
      return R('pm.' + pace, { ahead: 'Ahead of your usual day. Keep it going.', on: 'On pace for your usual day.', behind: 'Behind your usual day. A walk would help.' }[pace], d);
    }
    if (hour < 21.5) {
      const q = b.strain / Math.max(1, b.strainUsual * dayFrac(hour)), load = q >= T.hard ? 'hard' : q < T.light ? 'light' : 'full';
      const d = `Strain ${b.strain}, ${load === 'hard' ? 'above' : load === 'light' ? 'under' : 'near'} your usual ${b.strainUsual} · readiness was ${r}`;
      if (s.storm) return R('dusk.storm', 'Wind down early tonight.', d);
      if (s.ill) return R('dusk.ill', 'An early night helps most.', d);
      if (r < T.rest) return R('dusk.rest', 'A quiet day done. Sleep early.', d);
      if (r < T.easy) return R('dusk.easy', 'An easy day done. An early night next.', d);
      if (s.haze) return R('dusk.haze', 'A fair day done. Sleep on time.', d);
      return R('dusk.' + load, { hard: 'A hard day done. Ease off from here.', full: 'A full day done. Sleep on time.', light: 'A light day done. Sleep on time.' }[load], d);
    }
    const usual = `you usually fall asleep at ${at(b.onsetUsual)}`, last = `Last night ${hm(b.sleepMin)} · ${usual}`, short = b.sleepUsual - b.sleepMin >= T.short;
    if (s.storm) return R('night.storm', 'Sleep now if you can.', last);
    if (s.ill) return R('night.ill', 'Bed early. Rest helps.', last);
    if (r < T.rest) return R('night.rest', 'Bed now, if you can.', last);
    if (short) return R('night.short', `Bed by ${at(b.onsetUsual - T.early)} tonight.`, `Half an hour early: last night was short, ${hm(b.sleepMin)}`);
    return R('night.usual', `Bed by ${at(b.onsetUsual)} tonight.`, `Keeps your sleep on track · ${usual}`);
  }

  G.CoachRules = { read, body, T };
  if (typeof module !== 'undefined') module.exports = G.CoachRules;
})(typeof window !== 'undefined' ? window : globalThis);
