// Repeated GPU cost of a full render (all four slices + compose + post) and of compose + post, from one page: node benchT.js [terrain:tree] [sky=rain]
const { chromium } = require('playwright');
(async () => {
  const [tr, sp] = (process.argv[2] || 'lochCalm:maple').split(':'), sky = process.argv[3] || 'rain';
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 } });
  await p.goto(`http://127.0.0.1:8765/scene.html?sky=${sky}&h=13&anim=0&seed=20411&terrain=${tr}&tree=${sp}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 900000, polling: 1000 });
  for (let i = 0; i < 5; i++) { const t = await p.evaluate(() => ({ full: S.bench('full', 8), tilt: S.bench('tilt', 40) })); console.log(`run ${i + 1}: full ${t.full.toFixed(2)} ms, compose+post ${t.tilt.toFixed(3)} ms`); }
  await b.close();
})();
