// Re-bake the shipped keyframes headlessly: opens bake.html on the bake server (design-bake, :8768, PUT into design-prototype/baked/ only)
// and waits for it to finish. node bakeAll.js [set=today]
const { chromium } = require('playwright');
const set = process.argv[2] || 'today', BASE = process.env.BAKE || 'http://127.0.0.1:8768';
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 600, height: 400 } });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  const t0 = Date.now(); let seen = 0;
  await p.goto(`${BASE}/bake.html?set=${set}`, { waitUntil: 'commit' });
  while (true) {
    const st = await p.evaluate(() => ({ log: document.getElementById('log')?.textContent || '', done: !!window.BAKED })).catch(() => ({ log: '', done: false }));
    const lines = st.log.split('\n').filter(Boolean); lines.slice(seen).forEach(l => console.log(`[${((Date.now() - t0) / 1000).toFixed(0)} s] ${l}`)); seen = lines.length;
    if (st.done) break;
    await p.waitForTimeout(2000);
  }
  await b.close();
})();
