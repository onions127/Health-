// Round T1: shoot terrain directions x states from ONE page (the scene shader compiles once, ~2 min on D3D11; then each state is a set()).
//   node shootT.js [dirs=control,loch,chalk,downs,steppe,bay] [states=sunny,rain,night] [extra opts JSON]
// Out: out/t1/<dir>-<state>.png. Needs design-board on :8765.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const OUT = path.join(__dirname, 'out', process.env.OUTD || 't1');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const ST = { sunny: ['fair', 13], partly: ['partly', 13], rain: ['rain', 13], night: ['fair', 23], dusk: ['partly', 19], fog: ['valleyFog', 8], snow: ['snow', 11], cloudy: ['stratus', 13], dawn: ['fair', 6.5], thunder: ['thunder', 16],
  first: ['fair', 13, { readiness: null, sky: undefined }], winter: ['fair', 13, { dry: .05 }], today: ['fair', 13, { dry: dryToday() }], summer: ['fair', 13, { dry: .95 }],
  // T3: the year by day of year (season drives grass dryness, trees and mountain snow together)
  feb: ['fair', 13, { doy: 45 }], apr: ['fair', 13, { doy: 95 }], apr20: ['fair', 13, { doy: 109 }], may: ['fair', 13, { doy: 135 }], jul: ['fair', 13, { doy: 196 }], oct: ['fair', 13, { doy: 300 }], nov: ['fair', 13, { doy: 325 }], nov29: ['fair', 13, { doy: 332 }], dec3: ['fair', 13, { doy: 337 }], apr15: ['fair', 13, { doy: 104 }], jan: ['fair', 13, { doy: 15 }] };   // seasons: Mediterranean year
function dryToday() { return null; }
const dirs = (process.argv[2] || 'control,loch,chalk,downs,steppe,bay').split(','), states = (process.argv[3] || 'sunny,rain,night').split(','), extra = JSON.parse(process.argv[4] || '{}');
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('console', m => { if (m.type() === 'error') console.log('console error:', m.text().slice(0, 1500)); });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 1500)));
  const t0 = Date.now();
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=20411`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  console.log('compiled and converged in', ((Date.now() - t0) / 1000).toFixed(0), 's');
  for (const d of dirs) for (const s of states) {
    await p.evaluate(([d, sky, h, ex, sx]) => { const [tr, sp, lv, gr] = d.split(':'); const o = Object.assign({ terrain: tr, tree: sp || null, leaf: lv || null, ground: gr || null, doy: null, dry: null, sky: SceneGL.PRESETS[sky], hour: h, readiness: 86 }, ex, sx || {});  if (sx && 'sky' in sx) o.sky = undefined; S.set(o); }, [d, ST[s][0], ST[s][1], extra, ST[s][2]]);
    await p.waitForTimeout(100);
    await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 });
    await p.waitForTimeout(300);
    await p.screenshot({ path: path.join(OUT, `${d.replace(/:/g, '_')}-${s}.png`) });
    console.log('shot', d, s);
  }
  await b.close();
})();
