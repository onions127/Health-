// Round NT1: the picked screens at night (?night=1) into design-prototype/nt1/<name>.png and out/nt1-<name>.png.   node nt1.js
const { chromium } = require('playwright'); const path = require('path'), fs = require('fs');
const SHOTS = [['co1-board.html', 0, 'coach'], ['se1-board.html', 0, 'settings'], ['wt1-board.html', 2, 'weight'], ['wt1-board.html', 4, 'weight-saved'], ['md2-board.html', 0, 'metric-today'], ['md2-board.html', 2, 'metric-month']];
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  for (const [board, i, name] of SHOTS) {
    await p.goto(`http://127.0.0.1:8765/${board}?night=1`, { waitUntil: 'networkidle' }); await p.waitForSelector('.phone'); await p.waitForTimeout(800);
    const ph = (await p.$$('.phone'))[i], buf = await ph.screenshot();
    fs.writeFileSync(path.join(__dirname, 'out', `nt1-${name}.png`), buf); fs.writeFileSync(path.join(__dirname, '..', '..', 'design-prototype', 'nt1', `${name}.png`), buf);
  }
  console.log(SHOTS.length, 'shots'); await b.close();
})();
