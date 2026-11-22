// Contact sheet of images, optionally cropped to a vertical band (fractions of height).
//   node montage.js <out.png> <h px> [--crop=.30,.60] [--cols=N] img1 img2 ...   (labels = file names)
// Used for terrain references (reference/terrain/, never committed) and to look at the land band of our shots.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const a = process.argv.slice(2), out = a[0], H = +a[1];
const crop = (a.find(x => x.startsWith('--crop=')) || '--crop=0,1').slice(7).split(',').map(Number);
const cols = +((a.find(x => x.startsWith('--cols=')) || '--cols=99').slice(7));
const files = a.slice(2).filter(x => !x.startsWith('--'));
const img = f => `data:image/${path.extname(f).slice(1).replace('jpg', 'jpeg')};base64,${fs.readFileSync(f).toString('base64')}`;
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1600, height: 900 } });
  const cell = f => `<figure><div class=c style="height:${H}px"><img src="${img(f)}" style="height:${H / (crop[1] - crop[0])}px;margin-top:${-H * crop[0] / (crop[1] - crop[0])}px"></div><figcaption>${path.basename(f)}</figcaption></figure>`;
  await p.setContent(`<style>body{margin:0;padding:6px;background:#202020;color:#ddd;font:12px sans-serif;display:grid;grid-template-columns:repeat(${Math.min(cols, files.length)},max-content);gap:6px}
    figure{margin:0}.c{overflow:hidden}.c img{display:block}</style>${files.map(cell).join('')}`);
  await p.waitForTimeout(400);
  await p.screenshot({ path: out, fullPage: true });
  await b.close();
})();
