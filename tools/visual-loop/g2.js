// Round G2: scene glass at lower contrast floors. Same states and scene renders as G1 (design-prototype/g1/*.png).
// The glass takes the colour of the land under it (pale by day, dark at night) and is only as strong as the floor needs:
// per state and floor, the smallest alpha that keeps the weakest card text (secondary) at the floor, for light and for dark ink;
// the state takes whichever needs less. Sky text the same, with a veil in the same tint. Writes design-prototype/g2/measure.json.   node g2.js   (MODE=light|dark forces the glass; S1: STATES='[[id, preset, hour, label]]' RECTS='[[cards...], [sky text...]]')
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const BASE = process.env.BASE || 'http://127.0.0.1:8765', OUT = path.join(__dirname, '..', '..', 'design-prototype', process.env.OUT || 'g2');   // G3: OUT=g3 SHOOT=1 also saves the scene renders
const FLOORS = process.env.FLOORS ? process.env.FLOORS.split(',').map(Number) : [4.5, 4.0, 3.5, 3.0], SATD = +(process.env.SATD || 1.0);   // the night glass's saturate(): 1.6 lifted the blue land above the page around the cards
const STATES = process.env.STATES ? JSON.parse(process.env.STATES) : [['day13', 'fair', 13, 'Day 13:00 (control)'], ['dusk1830', 'fair', 18.5, 'Dusk 18:30'], ['dusk1945', 'fair', 19.75, 'Dusk 19:45'], ['dusk2030', 'fair', 20.5, 'Twilight 20:30'],
  ['night', 'fair', 23, 'Night 23:00'], ['nightPartly', 'partly', 23, 'Night, partly cloudy'], ['nightRain', 'rain', 23, 'Night, rain'], ['nightSnow', 'snow', 23, 'Night, snow'], ['dawn0530', 'fair', 5.5, 'Dawn 05:30']];
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=${process.env.SEED || 20411}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  if (process.env.RECTS) await p.evaluate(r => { [window.GLASS_RECTS, window.SKY_TEXT_RECTS] = r; }, JSON.parse(process.env.RECTS));   // S1: another screen's layout
  const out = { floors: FLOORS, states: STATES.map(s => ({ id: s[0], label: s[3] })), m: {} };
  for (const [id, sky, h] of STATES) {
    await p.evaluate(([sky, h]) => S.set({ sky: SceneGL.PRESETS[sky], hour: +h, readiness: 86 }), [sky, h]);
    await p.waitForTimeout(100); await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(300);
    if (process.env.SHOOT) await p.screenshot({ path: path.join(OUT, id + '.png') });
    const r = await p.evaluate(([FLOORS, SATD, MODE]) => {
      const c = document.getElementById('c'), lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4, gam = v => Math.round(255 * (v <= .0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - .055));
      const Lstar = Y => Y > .008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y, toHex = a => '#' + a.map(v => Math.max(0, Math.min(255, v)).toString(16).padStart(2, '0')).join('').toUpperCase();
      // the page under the cards: mean linear colour (same bands as G1)
      const gl = c.__gl, W = c.width, H = c.height, px = new Uint8Array(4 * W), acc = [0, 0, 0]; let n = 0;
      for (const yy of [.45, .55, .65, .75, .85]) { gl.readPixels(0, Math.round(H * (1 - yy)), W, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); for (let x = 0; x < W; x += 4) { for (let k = 0; k < 3; k++) acc[k] += lin(px[x * 4 + k]); n++; } }
      const pg = acc.map(v => v / n), Yp = .2126 * pg[0] + .7152 * pg[1] + .0722 * pg[2], pageL = Lstar(Yp);
      // scene tint: the page's own hue, deep (Y .02) for the dark glass; chroma halved toward grey at the same Y
      const atY = (Y, keep) => toHex(pg.map(v => { const u = v / Math.max(Yp, 1e-5); return gam(Math.min(1, Y * (keep * u + (1 - keep)))); }));
      const lightT = '#FFFFFF', darkT = atY(.02, .8);   // the day glass stays as decided (white); the night glass takes the land's hue
      const INK = { light: { primary: '#0E2A43', secondary: '#2E4A62', sky: '#0E2A43' }, dark: { primary: '#FFFFFF', secondary: '#C9D7EA', sky: '#F2F6FB' } };
      const cards = GLASS_RECTS.filter(x => x[0] !== 'nav'), nav = GLASS_RECTS.filter(x => x[0] === 'nav');
      const worst = (rects, ink, a, blur, tint, F) => Math.min(...glassCheck(c, rects, ink, a, blur, tint, blur ? (tint === '#FFFFFF' ? 1.6 : SATD) : 1).map(x => x.worst * (x.name === 'hero number' ? F / Math.min(3, F) : 1)));
      const need = (rects, ink, tint, blur, F) => { let lo = 0, hi = .95; const ok = a => worst(rects, ink, a, blur, tint, F) >= F + .05; if (ok(0)) return 0; if (!ok(hi)) return 1; for (let i = 0; i < 7; i++) { const m = (lo + hi) / 2; if (ok(m)) hi = m; else lo = m; } return hi; };
      const res = { pageL, lightT, darkT, satD: SATD };
      for (const F of FLOORS) {
        const nl = need(cards, INK.light.secondary, lightT, 22, F), nd = need(cards, INK.dark.secondary, darkT, 22, F);
        const mode = MODE || (nl === nd ? (pageL >= 56 ? 'light' : 'dark') : (nl < nd ? 'light' : 'dark')), tint = mode === 'light' ? lightT : darkT;
        const a = Math.max(mode === 'light' ? .22 : .22, (mode === 'light' ? nl : nd) + .02);
        const navA = Math.max(.30, need(nav, INK[mode].secondary, tint, 22, F) + .02);
        const sl = need(SKY_TEXT_RECTS, INK.light.sky, lightT, 0, F), sd = need(SKY_TEXT_RECTS, INK.dark.sky, darkT, 0, F);
        const skyMode = sd <= sl ? 'dark' : 'light', scrimT = skyMode === 'light' ? lightT : darkT, s0 = Math.min(sl, sd), scrimA = s0 > 0 ? Math.min(.9, s0 + .03) : 0;
        const FILLS = [{ fill: '#0B5FA5', ink: '#FFFFFF' }, { fill: '#FFFFFF', ink: '#0B3A66' }, { fill: '#082F5A', ink: '#FFFFFF' }];
        const bc = FILLS.map(f => Math.min(...glassCheck(c, [['button', 250, 40, 392, 88]], f.fill, scrimA, 0, scrimT).map(x => x.worst)));
        const bi = bc.findIndex(v => v >= 3.1), btn = FILLS[bi >= 0 ? bi : bc.indexOf(Math.max(...bc))];
        res[F] = { mode, tint, a: +a.toFixed(2), needLight: +nl.toFixed(2), needDark: +nd.toFixed(2), navA: +navA.toFixed(2), ink: INK[mode], skyInk: INK[skyMode],
          cardP: +worst(cards, INK[mode].primary, a, 22, tint, F).toFixed(2), cardS: +worst(cards, INK[mode].secondary, a, 22, tint, F).toFixed(2), navS: +worst(nav, INK[mode].secondary, navA, 22, tint, F).toFixed(2),
          skyMode, scrimT, scrimA: +scrimA.toFixed(2), sky: +Math.min(...glassCheck(c, SKY_TEXT_RECTS, INK[skyMode].sky, scrimA, 0, scrimT).filter(x => x.name !== 'hero number').map(x => x.worst)).toFixed(2),
          hero: +Math.min(...glassCheck(c, SKY_TEXT_RECTS, INK[skyMode].sky, scrimA, 0, scrimT).filter(x => x.name === 'hero number').map(x => x.worst)).toFixed(2), btn, btnC: +bc[FILLS.indexOf(btn)].toFixed(2) };
      }
      return res;
    }, [FLOORS, SATD, process.env.MODE || null]);
    out.m[id] = r;
    console.log(id.padEnd(12), 'L*', r.pageL.toFixed(0), r.lightT, r.darkT, FLOORS.map(F => { const o = r[F]; return `${F}: ${o.mode[0]}${Math.round(o.a * 100)} ${o.cardS} sky ${o.skyMode[0]}+${Math.round(o.scrimA * 100)} ${o.sky}/${o.hero}`; }).join(' | '));
  }
  fs.writeFileSync(path.join(OUT, 'measure.json'), JSON.stringify(out, null, 1));
  await b.close();
})();
