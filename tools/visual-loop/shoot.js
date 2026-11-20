// Visual self-critique loop: screenshot scene states, then pair each with its Samsung reference.
// Setup once:  cd tools/visual-loop && npm install
// Run (design-board server on :8765 must be up):
//   node shoot.js                 -> out/<state>.png for every state below + out/compare.png (ours | reference)
//   node shoot.js rain night      -> only those states
// Headless Chromium uses the real GPU through ANGLE (checked: RTX 4060, D3D11), so WebGL2 float targets work.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');

const REF = path.join(__dirname, '..', '..', 'reference');
const OUT = path.join(__dirname, 'out');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
// state: [scene.html query, reference image]. 3.15: snow and thunder are presets now (thunder is a review visual only, D2).
const STATES = {
  sunny:   ['sky=fair&h=13&anim=0',          'sunny.jpg'],
  partly:  ['sky=partly&h=13&anim=0',        'partly-cloudy.jpg'],
  cloudy:  ['sky=stratus&h=13&anim=0',       'cloudy.jpg'],
  rain:    ['sky=rain&h=13&anim=0',          'rain.png'],
  thunder: ['sky=thunder&h=16&anim=0',       'thunderstorm.png'],
  snow:    ['sky=snow&h=11&anim=0',          'snow.png'],
  fog:     ['sky=valleyFog&h=8&anim=0',      'fog.png'],
  dusk:    ['sky=partly&h=19&anim=0',        'dusk.jpg'],
  night:   ['sky=fair&h=23&anim=0',          'night.png'],
};
const WAIT = +(process.env.WAIT || 9000);   // the scene averages 24 jittered frames; give it time to converge

(async () => {
  const pick = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(STATES);
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  for (const name of pick) {
    const [q] = STATES[name];
    const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
    p.on('console', m => { if (m.type() === 'error') console.log(name, 'console error:', m.text().slice(0, 200)); });
    await p.goto(`${BASE}/scene.html?${q}`, { waitUntil: 'load', timeout: 90000 });
    await p.waitForTimeout(WAIT);
    await p.screenshot({ path: path.join(OUT, name + '.png') });
    await p.close();
    console.log('shot', name);
  }
  // Side-by-side sheet: ours | Samsung, same height, one row per state.
  const img = f => `data:image/${path.extname(f).slice(1).replace('jpg', 'jpeg')};base64,${fs.readFileSync(f).toString('base64')}`;
  const rows = pick.map(n => `<div class=r><b>${n}</b><img src="${img(path.join(OUT, n + '.png'))}"><img src="${img(path.join(REF, STATES[n][1]))}"></div>`).join('');
  const p = await b.newPage({ viewport: { width: 1000, height: 800 } });
  await p.setContent(`<style>body{margin:0;background:#1a1a1a;color:#ddd;font:14px sans-serif;display:flex;flex-wrap:wrap}.r{display:flex;align-items:flex-start;gap:4px;margin:4px}.r img{height:520px}b{writing-mode:vertical-rl}</style>${rows}`);
  await p.waitForTimeout(300);
  await p.screenshot({ path: path.join(OUT, 'compare.png'), fullPage: true });
  await b.close();
  console.log('wrote', path.join(OUT, 'compare.png'));
})();
