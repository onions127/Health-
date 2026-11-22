// Round L1: the sun's and moon's spikes in three styles (?rays= 0 none, 1 soft glare, 2 the 3.15 starburst), shot over the sky at
// day, dusk, twilight and night into design-prototype/l1/<hour>-r<style>.png (full frame, 412x915).   node l1.js
const { chromium } = require('playwright'); const path = require('path');
const BASE = process.env.BASE || 'http://127.0.0.1:8765', OUT = path.join(__dirname, '..', '..', 'design-prototype', 'l1');
const HOURS = (process.env.HOURS || '9,13,19,19.75,20.5,23').split(',').map(Number);
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  for (const r of (process.env.STYLES || '2,1,0').split(',').map(Number)) {
    const p = await b.newPage({ viewport: { width: 412, height: 915 } });
    p.on('pageerror', e => console.log('page error:', e.message.slice(0, 500)));
    await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=20411&rays=${r}`, { waitUntil: 'commit' });
    await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
    for (const h of HOURS) {
      await p.evaluate(h => S.set({ sky: SceneGL.PRESETS.fair, hour: h, readiness: 86 }), h); await p.waitForTimeout(100);
      await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(250);
      await p.screenshot({ path: path.join(OUT, `${String(h).replace('.', '_')}-r${r}.png`) });
    }
    console.log('rays', r, 'done'); await p.close();
  }
  await b.close();
})();
