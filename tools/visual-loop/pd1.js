// Round PD1: a poor day (?poor=1) on Today, Coach and Readiness, by day and at night, into out/pd1-<t>-<i>.png.   node pd1.js
const { chromium } = require('playwright'); const path = require('path');
const BOARDS = ['wt1-board.html', 'co2-board.html', 'md3-board.html'];
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  for (const t of ['day', 'night']) { let n = 0;
    for (const board of BOARDS) {
      await p.goto(`http://127.0.0.1:8765/${board}?poor=1${t === 'night' ? '&t=night' : ''}`, { waitUntil: 'networkidle' }); await p.waitForSelector('figure.uh .phone'); await p.waitForTimeout(800);
      for (const ph of await p.$$('figure.uh .phone')) await ph.screenshot({ path: path.join(__dirname, 'out', `pd1-${t}-${n++}.png`) });
    }
    console.log(t, n, 'phones');
  }
  await b.close();
})();
