// What sits behind the sky text (header, hero number, hero word + detail): L* range per rect, no glass, no blur.
// Light ink #F2F6FB passes 3:1 where L* <= ~59; dark ink #0E2A43 where L* >= ~48. A rect spanning both ends needs a veil.   node skyProbe.js [sky@h,...]
const { chromium } = require('playwright');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const states = (process.argv[2] || 'fair@19.75,fair@20.5,fair@23,partly@23,rain@23,snow@23,fair@5.5').split(',');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=${process.env.SEED || 20411}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  for (const st of states) {
    const [sky, h] = st.split('@');
    await p.evaluate(([sky, h]) => S.set({ sky: SceneGL.PRESETS[sky], hour: +h, readiness: 86 }), [sky, h]);
    await p.waitForTimeout(100); await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(200);
    const r = await p.evaluate(() => {
      const c = document.getElementById('c'), gl = c.__gl, W = c.width, H = c.height, s = W / 412, px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
      const lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4, Ls = Y => Y > .008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y;
      return SKY_TEXT_RECTS.map(([n, x0, y0, x1, y1]) => { const v = []; let hi = null, hv = -1;
        for (let y = y0; y < y1; y += 2) for (let x = x0; x < x1; x += 2) { const o = ((H - 1 - Math.floor(y * s)) * W + Math.floor(x * s)) * 4, L = Ls(.2126 * lin(px[o]) + .7152 * lin(px[o + 1]) + .0722 * lin(px[o + 2])); v.push(L); if (L > hv) { hv = L; hi = [x, y, '#' + [px[o], px[o + 1], px[o + 2]].map(q => q.toString(16).padStart(2, '0')).join('')]; } }
        v.sort((a, b) => a - b); const q = f => v[Math.floor(f * (v.length - 1))].toFixed(0);
        return `${n}: L* ${q(0)}/${q(.5)}/${q(.95)}/${q(1)} max at ${hi}`; });
    });
    console.log(st.padEnd(12), r.join(' | '));
  }
  await b.close();
})();
