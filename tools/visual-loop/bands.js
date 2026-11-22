// Value/colour structure of a landscape image: median colour of N horizontal bands (centre 80% of the width), top -> bottom, with L*.
//   node bands.js [--n=16] img1 img2 ...      -> one line per image: y% hex L*  ...
// Used for the terrain reference specs (CLAUDE.md): far / mid / near ramps and value steps per depth layer.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const a = process.argv.slice(2), N = +((a.find(x => x.startsWith('--n=')) || '--n=16').slice(4));
const files = a.filter(x => !x.startsWith('--'));
(async () => {
  const b = await chromium.launch(), p = await b.newPage();
  for (const f of files) {
    const url = `data:image/${path.extname(f).slice(1).replace('jpg', 'jpeg')};base64,${fs.readFileSync(f).toString('base64')}`;
    const r = await p.evaluate(async ([url, N]) => {
      const im = new Image(); im.src = url; await im.decode();
      const W = 320, H = Math.round(320 * im.height / im.width), c = new OffscreenCanvas(W, H), g = c.getContext('2d'); g.drawImage(im, 0, 0, W, H);
      const d = g.getImageData(0, 0, W, H).data, lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4;
      const Ls = (r, gg, bb) => { const Y = .2126 * lin(r) + .7152 * lin(gg) + .0722 * lin(bb); return Y > .008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y; };
      const out = [];
      for (let i = 0; i < N; i++) {
        const y0 = Math.floor(i * H / N), y1 = Math.floor((i + 1) * H / N), R = [], G = [], B = [];
        for (let y = y0; y < y1; y++) for (let x = Math.floor(W * .1); x < W * .9; x++) { const k = (y * W + x) * 4; R.push(d[k]); G.push(d[k + 1]); B.push(d[k + 2]); }
        const med = v => v.sort((p, q) => p - q)[v.length >> 1], m = [med(R), med(G), med(B)];
        out.push(`${Math.round(100 * (i + .5) / N)}:#${m.map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase()}/${Math.round(Ls(...m))}`);
      }
      return out.join(' ');
    }, [url, N]);
    console.log(path.basename(f).padEnd(22), r);
  }
  await b.close();
})();
