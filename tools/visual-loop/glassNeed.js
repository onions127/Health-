// G1: how strong each glass must be. Per state, the smallest glass alpha that gives AA (4.6, a little margin) for the weakest text on it,
// for the day glass (white, dark ink) and each night material (light ink); and the scrim behind the sky text (header, hero) for dark or light ink.
//   node glassNeed.js [states]
const { chromium } = require('playwright');
const BASE = process.env.BASE || 'http://127.0.0.1:8765', T = +(process.env.T || 4.6);
const DEF = [5, 5.25, 5.5, 5.75, 6, 6.25, 17, 17.5, 18, 18.25, 18.5, 18.75, 19, 19.25, 19.5, 19.75, 20, 20.25, 20.5, 21, 23].map(h => 'fair@' + h)
  .concat(['partly@19', 'partly@20', 'partly@23', 'stratus@19', 'stratus@23', 'rain@19', 'rain@23', 'snow@19', 'snow@23', 'valleyFog@6', 'mist@23', 'thunder@20']);
const states = process.argv[2] ? process.argv[2].split(',') : DEF;
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=${process.env.SEED || 20411}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  console.log('state'.padEnd(13), 'L*'.padStart(5), '| glass alpha for AA:', 'day'.padStart(5), 'navy'.padStart(5), 'smoke'.padStart(5), 'sky'.padStart(5), '| sky text: dark ink + white scrim, light ink + navy scrim', '| sky tint');
  for (const st of states) {
    const [sky, h] = st.split('@');
    await p.evaluate(([sky, h]) => S.set({ sky: SceneGL.PRESETS[sky], hour: +h, readiness: 86 }), [sky, h]);
    await p.waitForTimeout(100); await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(200);
    const r = await p.evaluate(T => {
      const c = document.getElementById('c'), lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4, gam = v => Math.round(255 * (v <= .0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - .055));
      const hx = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16)), toHex = a => '#' + a.map(v => Math.max(0, Math.min(255, v)).toString(16).padStart(2, '0')).join('').toUpperCase();
      // C: the sky overhead's hue at the navy's darkness (Y .013)
      const z = hx(S.pal.zen).map(lin), Y = .2126 * z[0] + .7152 * z[1] + .0722 * z[2], skyT = toHex(z.map(v => gam(v * .013 / Math.max(Y, 1e-4))));
      const cards = GLASS_RECTS.filter(x => x[0] !== 'nav');
      const need = (rects, ink, tint, blur, rel) => { let lo = 0, hi = .95; const ok = a => Math.min(...glassCheck(c, rects, ink, a, blur, tint, blur ? 1.6 : 1).map(x => x.worst / (rel && x.name === 'hero number' ? 3 : 4.5) * 4.5)) >= T;
        if (ok(0)) return 0; if (!ok(hi)) return 1; for (let i = 0; i < 7; i++) { const m = (lo + hi) / 2; if (ok(m)) hi = m; else lo = m; } return hi; };
      const gl = c.__gl, W = c.width, H = c.height, px = new Uint8Array(4 * W); let sum = 0, n = 0;
      for (const yy of [.45, .55, .65, .75, .85]) { gl.readPixels(0, Math.round(H * (1 - yy)), W, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); for (let x = 0; x < W; x += 4) { const k = x * 4; sum += .2126 * lin(px[k]) + .7152 * lin(px[k + 1]) + .0722 * lin(px[k + 2]); n++; } }
      const Yp = sum / n, Ls = Yp > .008856 ? 116 * Math.cbrt(Yp) - 16 : 903.3 * Yp;
      return { L: Ls, day: need(cards, '#2E4A62', '#FFFFFF', 22), navy: need(cards, '#C9D7EA', '#0A1E4A', 22), smoke: need(cards, '#C9D7EA', '#0E1116', 22), sky: need(cards, '#C9D7EA', skyT, 22),
        sd: need(SKY_TEXT_RECTS, '#0E2A43', '#FFFFFF', 0, true), sl: need(SKY_TEXT_RECTS, '#F2F6FB', '#0A1E4A', 0, true), skyT };
    }, T);
    const f = v => (v >= 1 ? '  >.95' : v.toFixed(2)).padStart(6);
    console.log(st.padEnd(13), r.L.toFixed(1).padStart(5), '|', ' '.repeat(19), f(r.day), f(r.navy), f(r.smoke), f(r.sky), '|', ' '.repeat(19), f(r.sd), ' '.repeat(18), f(r.sl), ' |', r.skyT);
  }
  await b.close();
})();
