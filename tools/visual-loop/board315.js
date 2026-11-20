// Round 3.15 review board: Today + first run (live board r3c-board.html), then every weather state, ours | Samsung reference.
// Writes out/board-3.15.html and out/board-3.15.png. out/ is gitignored, so the embedded reference screenshots never reach a commit.
// Needs: design-board on :8765 and a fresh `node shoot.js` (out/<state>.png).   Run: node board315.js [--no-today]
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const OUT = path.join(__dirname, 'out'), REF = path.join(__dirname, '..', '..', 'reference');
const STATES = [['sunny', 'sunny.jpg', 'fair · 13:00'], ['partly', 'partly-cloudy.jpg', 'partly · 13:00'], ['cloudy', 'cloudy.jpg', 'stratus · 13:00'], ['rain', 'rain.png', 'rain · 13:00'],
  ['thunder', 'thunderstorm.png', 'thunder · 16:00 (review only, D2)'], ['snow', 'snow.png', 'snow · 11:00'], ['fog', 'fog.png', 'valley fog · 08:00 (ref is evening fog)'], ['dusk', 'dusk.jpg', 'partly · 19:00'], ['night', 'night.png', 'fair · 23:00']];
const img = f => `data:image/${path.extname(f).slice(1).replace('jpg', 'jpeg')};base64,${fs.readFileSync(f).toString('base64')}`;
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  if (!process.argv.includes('--no-today')) {
    const p = await b.newPage({ viewport: { width: 1800, height: 1100 }, deviceScaleFactor: 1 });
    await p.route(u => /fonts\.(googleapis|gstatic)\.com/.test(u.href), r => r.fulfill({ status: 200, contentType: 'text/css', body: '' }));   // offline
    await p.goto('http://127.0.0.1:8765/r3c-board.html', { waitUntil: 'commit' });
    await p.waitForSelector('#ui .phone', { timeout: 240000 });
    await p.addStyleTag({ content: '.phone, .phone * { font-family: Roboto, "Segoe UI", system-ui, sans-serif !important; }' });   // stand-in when the web font is offline
    await p.waitForTimeout(25000);
    await p.locator('#ui .phone').screenshot({ path: path.join(OUT, 'today.png'), timeout: 240000 });
    await p.locator('#first .phone').screenshot({ path: path.join(OUT, 'first.png'), timeout: 240000 });
    await p.close();
  }
  const pair = ([n, r, cap]) => `<figure><div class=pr><img src="${img(path.join(OUT, n + '.png'))}"><img src="${img(path.join(REF, r))}"></div><figcaption><b>${n}</b> · ${cap}</figcaption></figure>`;
  const html = `<!doctype html><meta charset=utf-8><title>Health++ · Round 3.15 board</title><style>
    body{margin:0;padding:28px;background:#E9EBEE;font:14px/1.45 "Segoe UI",system-ui,sans-serif;color:#14171A}
    h1{font-size:26px;margin:0 0 6px} p{max-width:110ch;margin:0 0 12px;color:#3A424B} h2{font-size:18px;margin:26px 0 12px}
    .row{display:flex;gap:28px;flex-wrap:wrap;align-items:flex-start} figure{margin:0} figcaption{font-size:13px;margin-top:6px;color:#30363D}
    .phone{width:330px;border-radius:26px;overflow:hidden;box-shadow:0 12px 32px rgba(20,23,26,.16)} .phone img{width:330px;display:block}
    .pr{display:flex;gap:4px} .pr img{height:480px;border-radius:14px;display:block}
  </style>
  <h1>Round 3.15 · Samsung-reference pass</h1>
  <p>Left of each pair: ours (scene.html, 412×915, still frame). Right: the Samsung One UI Weather reference (style reference only, local, never shipped or committed).
  Horizon at 37% with lake, far hills and a grassy lip; the readiness number as the hero; sky ramps toward the sampled reference; soft high-key cumulus masses; sun starburst, ghosts and silver rim as post; snow, thunder and new rain streaks.</p>
  <h2>Today and first run (glass unchanged: 22% white, blur 22, ink #2E4A62)</h2>
  <div class=row><figure><div class=phone><img src="${img(path.join(OUT, 'today.png'))}"></div><figcaption>Today · readiness 86, 15:10 (sky at 13:00)</figcaption></figure>
  <figure><div class=phone><img src="${img(path.join(OUT, 'first.png'))}"></div><figcaption>First run · no score yet</figcaption></figure>
  <figure><div class=phone><img src="${img(path.join(REF, 'sunny.jpg'))}"></div><figcaption>Reference · sunny</figcaption></figure></div>
  <h2>Every state: ours | reference</h2><div class=row>${STATES.map(pair).join('')}</div>`;
  fs.writeFileSync(path.join(OUT, 'board-3.15.html'), html);
  const p = await b.newPage({ viewport: { width: 1500, height: 900 } });
  await p.setContent(html); await p.waitForTimeout(500);
  await p.screenshot({ path: path.join(OUT, 'board-3.15.png'), fullPage: true });
  await b.close(); console.log('wrote out/board-3.15.html and out/board-3.15.png');
})();
