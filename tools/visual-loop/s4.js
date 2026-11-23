// Round S4: Sleep trends as lines; bedtime and wake as two lines (A) vs bars (B). Shoots both scrolled phones into out/s4-a.png, s4-b.png,
// A after tapping 4 Nov into out/s4-a-nov4.png, and prints the measured mark contrast.   node s4.js
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  await p.goto('http://127.0.0.1:8765/s4-board.html', { waitUntil: 'networkidle' }); await p.waitForFunction(() => window.S3M, null, { timeout: 20000 }); await p.waitForTimeout(500);
  console.log(JSON.stringify(await p.evaluate(() => S3M)));
  const shot = async (k, n) => { await p.waitForTimeout(300); await (await p.$$('.phone'))[k].screenshot({ path: path.join(__dirname, 'out', `s4-${n}.png`) }); };
  await shot(0, 'a'); await shot(1, 'b');
  await p.click('#live rect[data-i="81"]'); await shot(0, 'a-nov4');
  await b.close();
})();
