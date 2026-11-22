// The 3.1 floor rule: every scene colour under the day glass must be at least as light as #86B9A0 (L* 71).
//   node floor.js img1 img2 ...   -> darkest L* under the card area (y >= 384/915, 12 px side margins), and where
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
(async () => {
  const b = await chromium.launch(), p = await b.newPage();
  for (const f of process.argv.slice(2)) {
    const url = `data:image/png;base64,${fs.readFileSync(f).toString('base64')}`;
    const r = await p.evaluate(async url => {
      const im = new Image(); im.src = url; await im.decode();
      const c = new OffscreenCanvas(im.width, im.height), g = c.getContext('2d'); g.drawImage(im, 0, 0);
      const d = g.getImageData(0, 0, im.width, im.height).data, lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4;
      let min = 999, at = null; const y0 = Math.round(im.height * 384 / 915), y1 = im.height - Math.round(im.height * 64 / 915), m = Math.round(im.width * 12 / 412);
      for (let y = y0; y < y1; y++) for (let x = m; x < im.width - m; x += 2) { const k = (y * im.width + x) * 4, Y = .2126 * lin(d[k]) + .7152 * lin(d[k + 1]) + .0722 * lin(d[k + 2]), L = Y > .008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y; if (L < min) { min = L; at = [x, y]; } }
      return { min: min.toFixed(1), at };
    }, url);
    console.log(path.basename(f).padEnd(20), 'darkest under cards L*', r.min, r.min < 71 ? 'BELOW #86B9A0' : 'ok', 'at', r.at.join(','));
  }
  await b.close();
})();
