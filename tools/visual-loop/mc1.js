// Round MC1: the Morning check phones on mc1-board.html into out/mc1-<i>.png.   node mc1.js
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  await p.goto('http://127.0.0.1:8765/mc1-board.html', { waitUntil: 'networkidle' }); await p.waitForSelector('.phone'); await p.waitForTimeout(800);
  const ph = await p.$$('.phone'); for (let i = 0; i < ph.length; i++) await ph[i].screenshot({ path: path.join(__dirname, 'out', `mc1-${i}.png`) });
  console.log(ph.length, 'phones'); await b.close();
})();
