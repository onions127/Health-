// Round M1: the far mountain range. Shoots fair at 09:00, 13:00, 18:30 and 23:00 (full frame) into design-prototype/m1/<tag>-<hour>.png.
//   TAG=a node m1.js [query]   (query appended to scene.html, e.g. '&seed=20411')
const { chromium } = require('playwright'); const path = require('path'), fs = require('fs');
const BASE = process.env.BASE || 'http://127.0.0.1:8765', OUT = path.join(__dirname, '..', '..', 'design-prototype', 'm1'), TAG = process.env.TAG || 'a';
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] }), p = await b.newPage({ viewport: { width: 412, height: 915 } });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 600)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=20411${process.argv[2] || ''}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  for (const h of (process.env.HOURS || '9,13,18.5,23').split(',').map(Number)) {
    await p.evaluate(h => S.set({ sky: SceneGL.PRESETS.fair, hour: h, readiness: 86 }), h); await p.waitForTimeout(100);
    await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(250);
    await p.screenshot({ path: path.join(OUT, `${TAG}-${String(h).replace('.', '_')}.png`) });
  }
  await b.close(); console.log('done', TAG);
})();
