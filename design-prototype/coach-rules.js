/* Round CR1: the rules that pick Coach's read (the headline over the sky, and its detail line).
   One pure function from the body and the hour, like BodySky, and on BodySky's own thresholds (haze, likely illness,
   storm, pace 1.15), so the sky and the read cannot disagree. First match wins, per part of the day:
     morning (to 12:00)   the plan, from this morning's body
     afternoon (to 18:00) progress against your usual day (steps against your usual for the hour)
     dusk (to 21:30)      how hard the day was (strain against your usual by this hour) and what recovery needs
     night                bedtime against your usual (earlier after a short night)
   The detail line always holds the numbers the read rests on. Ports to Kotlin as data + arithmetic.
   b: { readiness, hrv, hrvMin, hrvMax, rhr, rhrUsual, skinTemp, sleepMin, sleepUsual, steps, stepsUsual, strain,
        strainUsual, onsetUsual (minutes from noon) }; in the first week the usuals are missing (FW1): readiness null to day 3,
   normals and usuals null to day 7, onsetUsual null under 5 nights (nights = how many so far). */
(function (G) {
  const FW = { readiness: 4, normal: 8, bedtime: 5 };   // FW1: the app's minimums: readiness after 3 nights (day 4), a normal after 7 days (day 8), a usual bedtime after 5 nights
  const T = { rest: 50, easy: 65, ready: 80, ahead: 1.15, behind: 0.85, hard: 1.15, light: 0.85, short: 30, early: 30 };
  const BT = () => (G.BodySky && G.BodySky.T) || { hazeSkin: .3, hazeRhr: 3, illSkin: .5, illRhr: 5, stormReadiness: 35 };
  const n = x => Math.round(x).toLocaleString('en-US');
  const hm = m => `${Math.floor(m / 60)}h ${String(Math.round(m % 60)).padStart(2, '0')}m`;
  const clock = h => `${String(Math.floor(h)).padStart(2, '0')}:${String(Math.round((h % 1) * 60)).padStart(2, '0')}`;
  const at = mfn => { const t = ((Math.round(mfn / 5) * 5) + 720) % 1440; return `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`; };
  const dayFrac = h => Math.min(1, Math.max(.05, (h - 7) / 14));   // as BodySky and Trends: the usual day accrues 07:00 → 21:00

  function body(b) {   // the signs BodySky reads
    const t = BT();
    if (b.rhrUsual == null || b.skinTemp == null || b.rhr == null) return { dR: null, haze: false, ill: false, storm: false };   // FW1: no usual yet, no signs
    const dR = b.rhr - b.rhrUsual, ill = b.skinTemp >= t.illSkin && dR >= t.illRhr;
    return { dR, haze: b.skinTemp >= t.hazeSkin && dR >= t.hazeRhr, ill, storm: ill && b.readiness != null && b.readiness < t.stormReadiness };
  }
  function hrvWord(b) {
    if (b.hrvMin == null) return `your normal from day ${FW.normal}`;
    const half = Math.max(1, (b.hrvMax - b.hrvMin) / 2);
    return b.hrv < b.hrvMin - half ? 'well under your normal' : b.hrv < b.hrvMin ? 'below your normal' : b.hrv > b.hrvMax ? 'above your normal'
      : b.hrv === b.hrvMax ? 'at the top of your normal' : 'within your normal';
  }

  function read(b, hour) {
    const s = body(b), r = b.readiness;
    const signs = s.dR == null ? '' : `Skin +${b.skinTemp.toFixed(1)} °C · resting HR ${b.rhr}, ${s.dR} above usual`, rh = `Readiness ${r} · HRV ${b.hrv} ms, ${hrvWord(b)}`;
    const R = (rule, line, detail) => ({ rule, line, detail });
    const none = r == null, sl = b.sleepMin != null ? `Last night ${hm(b.sleepMin)}${b.sleepScore != null ? `, sleep score ${b.sleepScore}` : ''}` : '';
    if (hour < 12) {
      if (none) return R('am.first', 'Getting to know you.', `${b.sleepScore != null ? `Sleep score ${b.sleepScore}` : sl} · readiness from day ${FW.readiness}`);
      if (s.storm) return R('am.storm', 'Rest. Your body needs it.', signs);
      if (s.ill) return R('am.ill', 'Rest, and see how you feel.', signs);
      if (r < T.rest) return R('am.rest', 'Rest today.', rh);
      if (r < T.easy) return R('am.easy', 'Take it easy today.', rh);
      if (s.haze) return R('am.haze', 'Keep it moderate today.', signs);
      if (r < T.ready) return R('am.steady', 'Train, but not to the limit.', rh);
      return R('am.ready', 'A good day to train hard.', rh);
    }
    if (hour < 18) {
      const known = b.stepsUsual != null, p = known ? b.steps / Math.max(1, b.stepsUsual * dayFrac(hour)) : 1, pace = p >= T.ahead ? 'ahead' : p < T.behind ? 'behind' : 'on';
      const d = `${n(b.steps)} steps by ${clock(hour)} · strain ${b.strain}${known ? `, your usual ${b.strainUsual}` : ''}`;
      if (s.storm) return R('pm.storm', 'Rest is the plan today.', d);
      if (s.ill) return R('pm.ill', 'Keep it a rest day.', d);
      if (none || !known) {   // FW1: no pace without a usual day
        if (!none && r < T.rest) return R('pm.rest', 'Resting so far. Good.', d);
        if (!none && r < T.easy) return R('pm.easy', 'Easy so far. Keep it that way.', d);
        return R('pm.first', 'Learning your usual day.', d);
      }
      if (r < T.easy && pace === 'ahead') return R('pm.over', 'More than planned. Ease off now.', d);
      if (r < T.rest) return R('pm.rest', 'Resting so far. Good.', d);
      if (r < T.easy) return R('pm.easy', 'Easy so far. Keep it that way.', d);
      if (s.haze) return R('pm.haze.' + pace, { ahead: 'Ahead of pace. Keep it moderate.', on: 'On pace. Keep it moderate.', behind: 'A quiet day. Keep it moderate.' }[pace], d);
      return R('pm.' + pace, { ahead: 'Ahead of your usual day. Keep it going.', on: 'On pace for your usual day.', behind: 'Behind your usual day. A walk would help.' }[pace], d);
    }
    if (hour < 21.5) {
      if (none || b.strainUsual == null) {   // FW1
        const d0 = `Strain ${b.strain}${none ? '' : ` · readiness was ${r}`}`;
        if (!none && r < T.rest) return R('dusk.rest', 'A quiet day. Sleep early.', d0);
        if (!none && r < T.easy) return R('dusk.easy', 'An easy day. Sleep early.', d0);
        return R('dusk.first', 'A day done. Sleep on time.', d0);
      }
      const q = b.strain / Math.max(1, b.strainUsual * dayFrac(hour)), load = q >= T.hard ? 'hard' : q < T.light ? 'light' : 'full';
      const d = `Strain ${b.strain}, ${load === 'hard' ? 'above' : load === 'light' ? 'under' : 'near'} your usual ${b.strainUsual} · readiness was ${r}`;
      if (s.storm) return R('dusk.storm', 'Wind down early tonight.', d);
      if (s.ill) return R('dusk.ill', 'An early night helps most.', d);
      if (r < T.rest) return R('dusk.rest', 'A quiet day. Sleep early.', d);
      if (r < T.easy) return R('dusk.easy', 'An easy day. Sleep early.', d);
      if (s.haze) return R('dusk.haze', 'A fair day. Sleep on time.', d);
      return R('dusk.' + load, { hard: 'A hard day. Ease off now.', full: 'A full day. Sleep on time.', light: 'A light day. Sleep on time.' }[load], d);
    }
    if (b.onsetUsual == null) {   // FW1: fewer than 5 nights: no usual bedtime yet
      if (!none && r < T.rest) return R('night.rest', 'Bed now, if you can.', `Last night ${hm(b.sleepMin)}`);
      return R('night.first', 'Aim for seven and a half hours.', `Your usual bedtime after ${FW.bedtime} nights · ${b.nights || 0} so far`);
    }
    const usual = `you usually fall asleep at ${at(b.onsetUsual)}`, last = `Last night ${hm(b.sleepMin)} · ${usual}`, short = b.sleepUsual - b.sleepMin >= T.short;
    if (s.storm) return R('night.storm', 'Sleep now if you can.', last);
    if (s.ill) return R('night.ill', 'Bed early. Rest helps.', last);
    if (r < T.rest) return R('night.rest', 'Bed now, if you can.', last);
    if (short) return R('night.short', `Bed by ${at(b.onsetUsual - T.early)} tonight.`, `Half an hour early: last night was short, ${hm(b.sleepMin)}`);
    return R('night.usual', `Bed by ${at(b.onsetUsual)} tonight.`, `Keeps your sleep on track · ${usual}`);
  }

  G.CoachRules = { read, body, T, FW };
  if (typeof module !== 'undefined') module.exports = G.CoachRules;
})(typeof window !== 'undefined' ? window : globalThis);
