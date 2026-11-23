// Round DD1: the new screens at dawn (06:30) and dusk (19:45) via ?t=, into out/dd1-<t>-<name>.png; NIGHT=1 shoots night instead, to check against nt1/.   node dd1.js
const { chromium } = require('playwright'); const path = require('path');
const SHOTS = [['se1-board.html', 0, 'settings'], ['wt1-board.html', 2, 'weight'], ['wt1-board.html', 4, 'weight-saved'], ['md2-board.html', 2, 'metric-month'], ['md3-board.html', 0, 'readiness'], ['md4-board.html', 3, 'steps'], ['md4-board.html', 5, 'spo2']];
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  for (const t of process.env.NIGHT ? ['night'] : ['dawn', 'dusk']) for (const [board, i, name] of SHOTS) {
    await p.goto(`http://127.0.0.1:8765/${board}?t=${t}`, { waitUntil: 'networkidle' }); await p.waitForSelector('.phone'); await p.waitForTimeout(800);
    await (await p.$$('.phone'))[i].screenshot({ path: path.join(__dirname, 'out', `dd1-${t}-${name}.png`) });
  }
  await b.close(); console.log('done');
})();
