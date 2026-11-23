// Round VP1: a very poor day and likely illness (?bad=1) on Today and Coach, into out/vp1-<board>-<i>.png.   node vp1.js [wt1|co2]
const { chromium } = require('playwright'); const path = require('path');
const ONLY = process.argv[2], Q = process.env.Q || 'bad=1', TAG = process.env.TAG || 'vp1', BOARDS = (process.env.BOARDS ? process.env.BOARDS.split(',') : ['wt1', 'co2']).filter(b => !ONLY || b === ONLY);   // LT1: Q=fit=1 TAG=lt1
(async () => {
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1.6 / .72 });
  p.on('pageerror', e => console.log('err', e.message)); p.on('console', m => m.type() === 'error' && console.log('console', m.text()));
  for (const board of BOARDS) { let n = 0;
    await p.goto(`http://127.0.0.1:8765/${board}-board.html?${Q}`, { waitUntil: 'networkidle' }); await p.waitForSelector('figure.uh .phone'); await p.waitForTimeout(800);
    for (const ph of await p.$$('figure.uh .phone')) await ph.screenshot({ path: path.join(__dirname, 'out', `${TAG}-${board}-${n++}.png`) });
    console.log(board, n, 'phones');
  }
  await b.close();
})();
