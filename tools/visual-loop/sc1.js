// Round SC1: the Sleep page over the live sky at scroll steps, day and night. Shots -> design-prototype/sc1/<time>-y<scroll>.png,
// glass and sky-text contrast at each step -> design-prototype/sc1/measure.json.   node sc1.js   (needs the design-board server on :8765)
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const OUT = path.join(__dirname, '..', '..', 'design-prototype', 'sc1'), YS = [0, 30, 60, 100, 150, 200, 400, 900];
const TIMES = Object.fromEntries(Object.entries({ day: 7.7, dusk: 19.75, night: 23.17 }).filter(([k]) => !process.argv[2] || process.argv.slice(2).includes(k)));
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] }), M = {};
  for (const [name, h] of Object.entries(TIMES)) {
    const p = await b.newPage({ viewport: { width: 412, height: 915 } });
    p.on('pageerror', e => console.log('page error', e.message)); p.on('console', m => m.type() === 'error' && console.log(m.text().slice(0, 300)));
    await p.goto(`http://127.0.0.1:8765/sc1.html?h=${h}&anim=0&seed=20411&doy=328`, { waitUntil: 'commit' });
    await p.waitForFunction(() => window.SC1 && SC1.S.ready, null, { timeout: 900000, polling: 1000 });
    M[name] = {};
    for (const y of YS) {
      await p.evaluate(y => SC1.setY(y), y); await p.waitForTimeout(400);
      await p.screenshot({ path: path.join(OUT, `${name}-y${y}.png`) });
      M[name][y] = await p.evaluate(() => SC1.measure());
      const g = M[name][y].glass, worst = g.length ? Math.min(...g.map(r => r.worst)) : null;
      console.log(name, 'y', y, 'glass worst', worst, 'hero', (M[name][y].sky[0] || {}).worst);
    }
    await p.close();
  }
  const F = path.join(OUT, 'measure.json'), old = fs.existsSync(F) ? JSON.parse(fs.readFileSync(F)) : {}; fs.writeFileSync(F, JSON.stringify({ ...old, ...M }, null, 1));
  await b.close();
})();
