// G1: glass contrast across the hours and night weathers for several glass materials, from ONE page (compile once; each state is a set()).
//   node glassSweep.js [states] [options JSON]
//   states: comma list of sky@hour (e.g. fair@19.5,rain@23); default = dawn and dusk sweeps plus night weathers
//   options: { name: { tint, ga, ink, sat } } -- ink = the weakest text colour on that glass (secondary). Also measures sky text (header, hero) in dark and light ink.
const { chromium } = require('playwright');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const DEF = [4.75, 5, 5.25, 5.5, 5.75, 6, 6.25, 6.5, 7, 7.5, 8, 16.5, 17, 17.5, 18, 18.25, 18.5, 18.75, 19, 19.25, 19.5, 19.75, 20, 20.5, 21, 22, 23].map(h => 'fair@' + h)
  .concat(['partly@19', 'partly@20', 'partly@23', 'stratus@19', 'stratus@23', 'rain@19', 'rain@23', 'snow@23', 'valleyFog@6', 'mist@23', 'thunder@20']);
const states = process.argv[2] ? process.argv[2].split(',') : DEF;
const OPT = JSON.parse(process.argv[3] || JSON.stringify({
  day: { tint: '#FFFFFF', ga: .22, ink: '#2E4A62', sat: 1.6 },
  A_ink: { tint: '#0A1E4A', ga: .45, ink: '#BFD0E6', sat: 1.6 },
  B_smoke: { tint: '#0B0F18', ga: .30, ink: '#C4CEDB', sat: 1.6 },
  C_frost: { tint: '#FFFFFF', ga: .12, ink: '#DCE6F2', sat: 1.6 },
}));
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=${process.env.SEED || 20411}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  const names = Object.keys(OPT);
  console.log('state'.padEnd(14), names.map(n => n.padStart(9)).join(' '), '  sky dark/light ink (hero needs 3, the rest 4.5)', '  page L* under cards');
  for (const st of states) {
    const [sky, h] = st.split('@');
    await p.evaluate(([sky, h]) => S.set({ sky: SceneGL.PRESETS[sky], hour: +h, readiness: 86 }), [sky, h]);
    await p.waitForTimeout(100); await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(200);
    const r = await p.evaluate(OPT => {
      const c = document.getElementById('c'), out = {};
      for (const [n, o] of Object.entries(OPT)) { const m = glassCheck(c, GLASS_RECTS.filter(x => x[0] !== 'nav'), o.ink, o.ga, 22, o.tint, o.sat); out[n] = Math.min(...m.map(x => x.worst)); }
      const sd = glassCheck(c, SKY_TEXT_RECTS, '#0E2A43', 0, 0), sl = glassCheck(c, SKY_TEXT_RECTS, '#F2F6FB', 0, 0);
      const rel = m => Math.min(...m.map(x => x.worst / (x.name === 'hero number' ? 3 : 4.5)));
      // page luminance under the cards (blurred backdrop mean, as L*), the cheap signal the app could switch on
      const gl = c.__gl, W = c.width, H = c.height, px = new Uint8Array(4 * W), lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; let sum = 0, n = 0;
      for (const yy of [.45, .55, .65, .75, .85]) { gl.readPixels(0, Math.round(H * (1 - yy)), W, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); for (let x = 0; x < W; x += 4) { const k = x * 4; sum += .2126 * lin(px[k]) + .7152 * lin(px[k + 1]) + .0722 * lin(px[k + 2]); n++; } }
      const Y = sum / n, Ls = Y > .008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y;
      return { out, sky: [rel(sd), rel(sl)], L: Ls };
    }, OPT);
    console.log(st.padEnd(14), names.map(n => (r.out[n].toFixed(2) + (r.out[n] < 4.5 ? '!' : ' ')).padStart(9)).join(' '), `   ${(r.sky[0] * 4.5).toFixed(2)}${r.sky[0] < 1 ? '!' : ' '} / ${(r.sky[1] * 4.5).toFixed(2)}${r.sky[1] < 1 ? '!' : ' '}`, `   L* ${r.L.toFixed(1)}`);
  }
  await b.close();
})();
