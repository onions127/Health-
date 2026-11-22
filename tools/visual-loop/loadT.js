// Where the scene's first load goes (headless D3D11 like shootT.js): noise volumes, scene shader compile, first draw per slice, converged.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 } });
  p.on('console', m => { if (m.text().startsWith('[load]') || m.type() === 'error') console.log(m.text().slice(0, 300)); });
  await p.addInitScript(() => { window.SCENE_T = true; });
  const t0 = Date.now();
  await p.goto('http://127.0.0.1:8765/scene.html?sky=fair&h=13&anim=0&seed=20411&terrain=lochCalm&tree=maple', { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 900000, polling: 1000 });
  console.log('converged', ((Date.now() - t0) / 1000).toFixed(1), 's after navigation');
  await b.close();
})();
