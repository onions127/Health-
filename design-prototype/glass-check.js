/* Measures real text contrast on full glass over a rendered scene canvas.
   Blurs the canvas like backdrop-filter: blur(22px) (Gaussian, sigma 22 CSS px), composites white at --ga over it,
   and returns the worst contrast for a text colour inside each card rectangle (CSS px, 412x915 frame). */
window.glassCheck = function (canvas, rects, ink = '#2E4A62', ga = 0.22, blurPx = 22) {   // blurPx 0, ga 0: text sitting straight on the scene (hero, header; 3.15)
  const W = canvas.width, H = canvas.height, s = W / 412, step = 4;                 // sample every 4 CSS px
  const gl = canvas.__gl || canvas.getContext('webgl2') || canvas.getContext('webgl'), px = new Uint8Array(W * H * 4);
  gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
  const gw = Math.floor(412 / step), gh = Math.floor(915 / step), lin = c => { c /= 255; return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4; };
  let ch = [0, 1, 2].map(() => new Float32Array(gw * gh));
  for (let j = 0; j < gh; j++) for (let i = 0; i < gw; i++) { const X = Math.floor((i * step + step / 2) * s), Y = H - 1 - Math.floor((j * step + step / 2) * s), o = (Y * W + X) * 4; for (let k = 0; k < 3; k++) ch[k][j * gw + i] = px[o + k]; }
  const sig = Math.max(blurPx, .01) / step, R = blurPx > 0 ? Math.ceil(sig * 3) : 0, ker = []; let sum = 0; for (let t = -R; t <= R; t++) { const v = Math.exp(-t * t / (2 * sig * sig)); ker.push(v); sum += v; } ker.forEach((v, i) => ker[i] = v / sum);
  const blur = (a, horiz) => { const b = new Float32Array(a.length); for (let j = 0; j < gh; j++) for (let i = 0; i < gw; i++) { let acc = 0; for (let t = -R; t <= R; t++) { const ii = horiz ? Math.min(gw - 1, Math.max(0, i + t)) : i, jj = horiz ? j : Math.min(gh - 1, Math.max(0, j + t)); acc += a[jj * gw + ii] * ker[t + R]; } b[j * gw + i] = acc; } return b; };
  ch = ch.map(a => blur(blur(a, true), false));
  const inkL = (() => { const c = [1, 3, 5].map(i => lin(parseInt(ink.slice(i, i + 2), 16))); return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]; })();
  return rects.map(([name, x0, y0, x1, y1]) => { let worst = 99, at = null; for (let y = y0; y < y1; y += step) for (let x = x0; x < x1; x += step) { const i = Math.floor(x / step), j = Math.floor(y / step), c = [0, 1, 2].map(k => ga * 255 + (1 - ga) * ch[k][j * gw + i]); const L = .2126 * lin(c[0]) + .7152 * lin(c[1]) + .0722 * lin(c[2]); const cr = (L + .05) / (inkL + .05); if (cr < worst) { worst = cr; at = [x, y, c.map(v => Math.round(v).toString(16).padStart(2, '0')).join('')]; } } return { name, worst: +worst.toFixed(2), at }; });
};
// 3.15: horizon at 37%, cards start on the grassy lip at 384 (42%); the last card runs on under the nav (the page scrolls), so it is measured above the nav
window.GLASS_RECTS = [['week strip', 12, 384, 400, 488], ['sleep tile', 12, 500, 200, 712], ['steps tile', 212, 500, 400, 600], ['hrv tile', 212, 612, 400, 712], ['daily tile', 12, 724, 400, 808], ['rhr tile', 12, 820, 400, 851], ['nav', 0, 851, 412, 915]];   // 3.16: Samsung proportions (margin 12, gap 12)
// text straight on the sky, ink #0E2A43, no glass: header line and the hero's word and detail line (the 89 px number needs only 3:1)
window.SKY_TEXT_RECTS = [['header', 20, 40, 300, 92], ['hero number', 18, 112, 230, 205], ['hero word + detail', 22, 205, 330, 262]];
