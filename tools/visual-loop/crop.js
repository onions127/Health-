// Close-up of a shot: node crop.js <in.png> <out.png> <x0> <y0> <x1> <y1> [scale]   (fractions of the image; nearest-neighbour upscale)
const { chromium } = require('playwright'), fs = require('fs'), path = require('path');
const [inp, outp, x0, y0, x1, y1, sc = 3] = process.argv.slice(2);
(async () => {
  const b = await chromium.launch(), p = await b.newPage();
  const data = await p.evaluate(async ([src, r, s]) => {
    const im = new Image(); im.src = src; await im.decode();
    const sx = r[0] * im.width, sy = r[1] * im.height, w = (r[2] - r[0]) * im.width, h = (r[3] - r[1]) * im.height;
    const c = document.createElement('canvas'); c.width = Math.round(w * s); c.height = Math.round(h * s);
    const x = c.getContext('2d'); x.imageSmoothingEnabled = false; x.drawImage(im, sx, sy, w, h, 0, 0, c.width, c.height);
    return c.toDataURL('image/png').split(',')[1];
  }, [`data:image/${path.extname(inp).slice(1).replace('jpg', 'jpeg')};base64,${fs.readFileSync(inp).toString('base64')}`, [+x0, +y0, +x1, +y1], +sc]);
  fs.writeFileSync(outp, Buffer.from(data, 'base64')); await b.close();
})();
