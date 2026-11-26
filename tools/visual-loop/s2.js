// Round S2: the Sleep phone on s2-board.html at last night, then by its back button (one tap, and to the 4 Nov poor night), into out/s2-<n>.png.   node s2.js
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1200, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  await p.goto('http://127.0.0.1:8765/s2-board.html', { waitUntil: 'networkidle' }); await p.waitForTimeout(800);
  const shot = async n => { await p.waitForTimeout(300); await (await p.$('.phone')).screenshot({ path: path.join(__dirname, 'out', `s2-${n}.png`) }); };
  await shot('last');
  await p.click('.step[data-dir="-1"]'); await shot('back1');
  const fwdOff = await p.$eval('.step[data-dir="1"]', e => e.getAttribute('aria-disabled')); console.log('forward disabled after one step back:', fwdOff);
  await p.evaluate(() => S2.go(81)); await shot('nov4');
  await b.close();
})();
