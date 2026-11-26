// Rounds PS1, TW1: the drawn days' bodies at one hour, as g2.js STATES (5th element = a BodySky body).   node ps1-states.js > out/ps1-states.json   (TW1: H=20.5 I=2 TAG=pm2030)
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(), p = await b.newPage();
  await p.goto('http://127.0.0.1:8765/co2-board.html', { waitUntil: 'networkidle' });
  const H = +(process.env.H || 5.33), I = +(process.env.I || 0), TAG = process.env.TAG || 'am0520';
  const s = await p.evaluate(([H, I, TAG]) => {
    const HD = window.HEALTH_DATA, C = COACH_DAYS, SS = { today: HD.today.sleepScore, pd: HD.days[81].sleepScore, hz: 70, vp: 42, ill: 56, st: 40 };
    return Object.keys(SS).map(k => [k + '-' + TAG, 'fair', H, k + ' ' + TAG, { ...C.at(C[k], I), ...(I ? {} : { steps: 0, strain: 0 }), sleepScore: SS[k], readinessUsual: HD.METRICS.readiness.avg30 }]);
  }, [H, I, TAG]);
  console.log(JSON.stringify(s)); await b.close();
})();
