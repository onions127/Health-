// Pixel difference between pairs of PNGs (same names in two folders): node pixdiff.js dirA dirB [filter]
// Prints max channel difference (0-255), mean, and the share of pixels that differ by more than 2 and 8 levels.
// BANDS=1: also the pixels > 8 per tenth of the height. DIFFDIR=<dir>: writes a x12 difference image per pair.
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const [A, B, f = ''] = process.argv.slice(2);
(async () => {
  const b = await chromium.launch(), p = await b.newPage();
  for (const n of fs.readdirSync(A).filter(n => n.endsWith('.png') && n.includes(f) && fs.existsSync(path.join(B, n)))) {
    const u = x => `data:image/png;base64,${fs.readFileSync(x).toString('base64')}`;
    const r = await p.evaluate(async ([a, bb]) => {
      const load = async s => { const im = new Image(); im.src = s; await im.decode(); const c = new OffscreenCanvas(im.width, im.height), g = c.getContext('2d'); g.drawImage(im, 0, 0); return g.getImageData(0, 0, im.width, im.height).data; };
      const x = await load(a), y = await load(bb); let mx = 0, sum = 0, n2 = 0, n8 = 0; const W = 412, rows = new Array(10).fill(0), dimg = new Uint8ClampedArray(x.length);
      for (let i = 0; i < x.length; i += 4) { const d = Math.max(Math.abs(x[i] - y[i]), Math.abs(x[i + 1] - y[i + 1]), Math.abs(x[i + 2] - y[i + 2])); mx = Math.max(mx, d); sum += d; if (d > 2) n2++; if (d > 8) { n8++; rows[Math.floor(i / 4 / W * 10 / (x.length / 4 / W))]++; } dimg[i] = dimg[i + 1] = dimg[i + 2] = Math.min(255, d * 12); dimg[i + 3] = 255; }
      const N = x.length / 4, H = N / W, c = new OffscreenCanvas(W, H); c.getContext('2d').putImageData(new ImageData(dimg, W, H), 0, 0);
      const png = new Uint8Array(await (await c.convertToBlob()).arrayBuffer()); let bin = ''; for (const v of png) bin += String.fromCharCode(v);
      return { mx, mean: sum / N, p2: 100 * n2 / N, p8: 100 * n8 / N, rows, png: btoa(bin) };
    }, [u(path.join(A, n)), u(path.join(B, n))]);
    console.log(n.padEnd(44), `max ${r.mx}  mean ${r.mean.toFixed(3)}  >2: ${r.p2.toFixed(3)}%  >8: ${r.p8.toFixed(3)}%` + (process.env.BANDS ? `  >8 by tenth: ${r.rows.join(' ')}` : ''));
    if (process.env.DIFFDIR) { fs.mkdirSync(process.env.DIFFDIR, { recursive: true }); fs.writeFileSync(path.join(process.env.DIFFDIR, 'diff-' + n), Buffer.from(r.png, 'base64')); }
  }
  await b.close();
})();
