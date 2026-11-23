// Round X1: shoots each transition case x style at 0/25/50/75/100% into design-prototype/x1/<case>-<style>-<pct>.png.   node x1.js
const { chromium } = require('playwright'); const path = require('path'), fs = require('fs');
const BASE = process.env.BASE || 'http://127.0.0.1:8765', OUT = path.join(__dirname, '..', '..', 'design-prototype', 'x1');
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] }), p = await b.newPage({ viewport: { width: 412, height: 915 } });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 600)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=20411`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  for (const f of ['body-sky.js', 'b1-days.js', 'x1-cases.js']) await p.addScriptTag({ url: `${BASE}/${f}` });
  const cases = await p.evaluate(() => X1_CASES.map(c => c.id)), ready = () => p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 100 });
  for (const id of cases) for (const style of ['fade', 'front', 'patches']) {
    await p.evaluate(id => S.set(X1_state(X1_CASES.find(c => c.id === id), 'from').set), id); await p.waitForTimeout(100); await ready(); await p.waitForTimeout(250);
    const ok = await p.evaluate(([id, style]) => { S.set(Object.assign({ transition: style }, X1_state(X1_CASES.find(c => c.id === id), 'to').set)); S.holdTransition(0); return S.transitioning; }, [id, style]);
    if (!ok) { console.log(id, style, 'no transition started'); continue; }
    await p.waitForFunction(() => S.ready && S.transitionReady, null, { timeout: 120000, polling: 100 });
    for (const pct of [0, 25, 50, 75, 100]) {
      await p.evaluate(v => S.holdTransition(v), pct / 100); await p.waitForTimeout(200);
      await p.screenshot({ path: path.join(OUT, `${id}-${style}-${pct}.png`) });
    }
    await p.evaluate(() => S.holdTransition(null)); await p.waitForFunction(() => !S.transitioning, null, { timeout: 20000, polling: 100 });
    console.log(id, style, 'ok');
  }
  await b.close();
})();
