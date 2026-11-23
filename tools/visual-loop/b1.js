// Round B1: render the sky lab's example days (design-prototype/b1-days.js) through BodySky into design-prototype/b1/<id>.png,
// with the notes and the sky-text contrast (no veil; best of light and dark ink) into b1/measure.json.   node b1.js
const { chromium } = require('playwright'); const path = require('path'), fs = require('fs');
const BASE = process.env.BASE || 'http://127.0.0.1:8765', OUT = path.join(__dirname, '..', '..', 'design-prototype', 'b1');
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] }), p = await b.newPage({ viewport: { width: 412, height: 915 } });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 600)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=20411`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  for (const f of ['body-sky.js', 'b1-days.js']) await p.addScriptTag({ url: `${BASE}/${f}?${Date.now()}` });
  const days = await p.evaluate(() => B1_DAYS.map(d => d.id)), out = {};
  for (const id of days) {
    const r = await p.evaluate(id => { const d = B1_DAYS.find(x => x.id === id), m = BodySky.bodySky(d.body, d.hour, SceneGL);
      S.set({ sky: m.sky, hour: d.hour, readiness: d.body.readiness == null ? undefined : d.body.readiness }); return { notes: m.notes, layers: m.layers, regime: m.regime }; }, id);
    await p.waitForTimeout(100); await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(250);
    await p.screenshot({ path: path.join(OUT, id + '.png') });
    r.sky = await p.evaluate(() => { const c = document.getElementById('c'), m = ink => Math.min(...glassCheck(c, SKY_TEXT_RECTS, ink, 0, 0).map(x => x.worst)); return { light: m('#F2F6FB'), dark: m('#0E2A43') }; });
    out[id] = r; console.log(id.padEnd(15), r.regime.padEnd(14), JSON.stringify(r.layers), 'sky text', Math.max(r.sky.light, r.sky.dark).toFixed(2));
  }
  fs.writeFileSync(path.join(OUT, 'measure.json'), JSON.stringify(out, null, 1)); await b.close();
})();
