// Round TR1: the three Trends layouts on tr1-board.html into out/tr1-<i>.png, and the measured mark contrast.   node tr1.js
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  await p.goto('http://127.0.0.1:8765/tr1-board.html', { waitUntil: 'networkidle' }); await p.waitForFunction(() => window.TR1M, null, { timeout: 20000 }); await p.waitForTimeout(600);
  console.log(JSON.stringify(await p.evaluate(() => TR1M)));
  const ph = await p.$$('.phone'); for (let i = 0; i < ph.length; i++) await ph[i].screenshot({ path: path.join(__dirname, 'out', `tr1-${i}.png`) });
  await b.close();
})();
