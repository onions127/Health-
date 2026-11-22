// Probe: how long until the scene compiles, renders and converges; then full-render and compose cost.  node probe.js "<query>"
const { chromium } = require('playwright');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('console', m => { if (m.type() === 'error') console.log('console error:', m.text().slice(0, 2000)); });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 2000)));
  const t0 = Date.now();
  await p.goto(`${BASE}/scene.html?${process.argv[2] || 'sky=fair&h=13'}&anim=0`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S, null, { timeout: 600000 });
  console.log('mounted (compile done) after', ((Date.now() - t0) / 1000).toFixed(1), 's');
  await p.waitForFunction(() => S.ready, null, { timeout: 600000, polling: 1000 });
  console.log('converged after', ((Date.now() - t0) / 1000).toFixed(1), 's');
  console.log(await p.evaluate(() => ({ full: S.bench('full', 6).toFixed(2), tilt: S.bench('tilt', 30).toFixed(3) })));
  await b.close();
})();
