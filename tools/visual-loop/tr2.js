// Round TR2: the Trends list with good/bad colour, today (day) and 4 Nov (night), into out/tr2-0.png, tr2-1.png; prints the colour contrast.   node tr2.js
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  await p.goto('http://127.0.0.1:8765/tr2-board.html', { waitUntil: 'networkidle' }); await p.waitForFunction(() => window.TR2M, null, { timeout: 20000 }); await p.waitForTimeout(600);
  console.log(JSON.stringify(await p.evaluate(() => TR2M)));
  const ph = await p.$$('.phone'); for (let i = 0; i < ph.length; i++) await ph[i].screenshot({ path: path.join(__dirname, 'out', `tr2-${i}.png`) });
  await b.close();
})();
