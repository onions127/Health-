// Round S3: the Sleep trends below the fold (s3-board.html). Shoots the scrolled phone for last night and for 4 Nov (tapped in a chart),
// plus the whole scrolling page as one tall image, into out/s3-*.png, and prints the measured mark contrast.   node s3.js
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  await p.goto('http://127.0.0.1:8765/s3-board.html', { waitUntil: 'networkidle' }); await p.waitForFunction(() => window.S3M, null, { timeout: 20000 }); await p.waitForTimeout(500);
  console.log(JSON.stringify(await p.evaluate(() => S3M)));
  const shot = async n => { await p.waitForTimeout(300); await (await p.$$('.phone'))[1].screenshot({ path: path.join(__dirname, 'out', `s3-${n}.png`) }); };
  await shot('trends');
  await p.click('#live2 rect[data-i="81"]'); await shot('trends-nov4');
  await p.evaluate(() => { const c = document.querySelector('#live .phone'), s = c.querySelector('.scroll'); c.style.height = '1730px'; s.style.overflow = 'visible'; S2.go(89); });
  await p.evaluate(() => { const c = document.querySelector('#live .phone'); c.style.height = '1730px'; c.querySelector('.scroll').style.overflow = 'visible'; c.querySelector('.nav').style.display = 'none'; });
  await p.waitForTimeout(300); await (await p.$('#live .phone')).screenshot({ path: path.join(__dirname, 'out', 's3-full.png') });
  await b.close();
})();
