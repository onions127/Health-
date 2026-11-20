// Screenshot one element of a board: node board.js <page> <selector> <out.png> [waitMs]
const { chromium } = require('playwright');
const [page, sel, out, wait = 9000] = process.argv.slice(2);
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 1 });
  p.on('console', m => { if (m.type() === 'error') console.log('console error:', m.text().slice(0, 300)); });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 300)));
  await p.route(u => /fonts\.(googleapis|gstatic)\.com/.test(u.href), r => r.fulfill({ status: 200, contentType: 'text/css', body: '' }));   // offline: the board's web fonts would block the parser; Roboto is local
  await p.goto('http://127.0.0.1:8765/' + page, { waitUntil: 'commit' }); await p.waitForTimeout(+wait);
  await p.locator(sel).first().screenshot({ path: out, timeout: 240000 }); await b.close();
})();
