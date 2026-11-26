// SC1 cost: compose + post per frame, page at rest vs scrolled (the land's mip blur), and the mip rebuild.  node sc1-bench.js
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] }), p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2 });
  await p.goto('http://127.0.0.1:8765/sc1.html?h=7.7&anim=0&seed=20411&doy=328', { waitUntil: 'commit' });
  await p.waitForFunction(() => window.SC1 && SC1.S.ready, null, { timeout: 900000, polling: 1000 });
  const r = await p.evaluate(() => { const S = SC1.S, out = {};
    for (const y of [0, 150, 0, 150]) { SC1.setY(y); S.bench('tilt', 5); out['y' + y] = (out['y' + y] || []).concat(S.bench('tilt', 60).toFixed(3)); }
    return out; });
  console.log('compose+post ms/frame (824x1830):', JSON.stringify(r));
  await b.close();
})();
