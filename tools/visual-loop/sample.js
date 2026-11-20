// Sample sky ramps (hex) from our shots and the Samsung references, same spots, so ramps are measured, not eyeballed.
//   node sample.js [state ...]      (reads out/<state>.png and reference/<ref>; needs no server)
// Spots: right edge of the sky (x .90), top / middle / just above the horizon, and the water or ground just below it.
// Ours: horizon from HOR (env, default .37). References: horizon ~.37 (CLAUDE.md). Each spot is a 9x9 px mean.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const REF = path.join(__dirname, '..', '..', 'reference'), OUT = path.join(__dirname, 'out');
const HOR = +(process.env.HOR || .37), DIR = process.env.DIR || OUT;
const REFS = { sunny: 'sunny.jpg', partly: 'partly-cloudy.jpg', cloudy: 'cloudy.jpg', rain: 'rain.png', thunder: 'thunderstorm.png', snow: 'snow.png', fog: 'video/patches-of-fog.png', dusk: 'dusk.jpg', night: 'night.png' };
const spots = h => [['top', .06], ['mid', h * .5], ['nearHor', h - .025], ['below', h + .012]];
const url = f => `data:image/${path.extname(f).slice(1).replace('jpg', 'jpeg')};base64,${fs.readFileSync(f).toString('base64')}`;
const lab = hx => { const c = [1, 3, 5].map(i => parseInt(hx.slice(i, i + 2), 16) / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);
  const Y = .2126 * c[0] + .7152 * c[1] + .0722 * c[2]; return Y > .008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y; };   // L* only
(async () => {
  const pick = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(REFS);
  const b = await chromium.launch(), p = await b.newPage();
  const sample = (src, pts) => p.evaluate(async ([src, pts]) => {
    const im = new Image(); im.src = src; await im.decode();
    const c = document.createElement('canvas'); c.width = im.width; c.height = im.height; const x = c.getContext('2d'); x.drawImage(im, 0, 0);
    return pts.map(([n, fy]) => { const d = x.getImageData(Math.round(.90 * im.width) - 4, Math.round(fy * im.height) - 4, 9, 9).data, s = [0, 0, 0];
      for (let i = 0; i < d.length; i += 4) for (let k = 0; k < 3; k++) s[k] += d[i + k];
      return [n, '#' + s.map(v => Math.round(v / 81).toString(16).padStart(2, '0')).join('').toUpperCase()]; });
  }, [src, pts]);
  const rows = [];
  for (const n of pick) {
    const ours = await sample(url(path.join(DIR, n + '.png')), spots(HOR)), ref = await sample(url(path.join(REF, REFS[n])), spots(.37));
    rows.push({ state: n, ...Object.fromEntries(ours.map(([k, v], i) => [k, `${v} | ${ref[i][1]}  dL*${(lab(v) - lab(ref[i][1])).toFixed(0).padStart(4)}`])) });
  }
  console.log('ours | reference   (dL* = ours - reference)');
  for (const r of rows) console.log(r.state.padEnd(8), ['top', 'mid', 'nearHor', 'below'].map(k => k + ' ' + r[k]).join('   '));
  await b.close();
})();
