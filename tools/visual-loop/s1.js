// Round S1: screenshots each phone on design-prototype/s1-board.html into out/s1-<i>.png (the board itself needs s1/ and s1c/ measured by g2.js).   node s1.js
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu'] }), p = await b.newPage({ viewport: { width: 1500, height: 1000 }, deviceScaleFactor: 1.6 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  await p.goto('http://127.0.0.1:8765/s1-board.html', { waitUntil: 'networkidle' }); await p.waitForTimeout(1500);
  const ph = await p.$$('.phone'); for (let i = 0; i < ph.length; i++) await ph[i].screenshot({ path: path.join(__dirname, 'out', `s1-${i}.png`) });
  console.log(ph.length, 'phones'); await b.close();
})();
