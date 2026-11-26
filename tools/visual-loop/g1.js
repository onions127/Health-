// Round G1: shoot the dusk / night / dawn states (scene only, no UI) into design-prototype/g1/ and measure every night-glass option
// under the G1 rule (design-prototype/glass-rule.js): card text (primary, secondary) on the glass, the nav, the sky text with its scrim,
// and the Log weight button against the sky (non-text, 3:1). Writes g1/measure.json for r5a-board.html.   node g1.js
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const BASE = process.env.BASE || 'http://127.0.0.1:8765', OUT = path.join(__dirname, '..', '..', 'design-prototype', 'g1');
const STATES = [['day13', 'fair', 13, 'Day 13:00 (control)'], ['dusk1830', 'fair', 18.5, 'Dusk 18:30'], ['dusk1945', 'fair', 19.75, 'Dusk 19:45'], ['dusk2030', 'fair', 20.5, 'Twilight 20:30'],
  ['night', 'fair', 23, 'Night 23:00'], ['nightPartly', 'partly', 23, 'Night, partly cloudy'], ['nightRain', 'rain', 23, 'Night, rain'], ['nightSnow', 'snow', 23, 'Night, snow'], ['dawn0530', 'fair', 5.5, 'Dawn 05:30']];
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=${process.env.SEED || 20411}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  await p.addScriptTag({ url: `${BASE}/glass-rule.js?${Date.now()}` });
  const out = { states: STATES.map(s => ({ id: s[0], label: s[3] })), m: {} };
  for (const [id, sky, h, label] of STATES) {
    await p.evaluate(([sky, h]) => S.set({ sky: SceneGL.PRESETS[sky], hour: +h, readiness: 86 }), [sky, h]);
    await p.waitForTimeout(100); await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(300);
    await p.screenshot({ path: path.join(OUT, id + '.png') });
    const r = await p.evaluate(() => {
      const c = document.getElementById('c'), lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4, Lstar = Y => Y > .008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y;
      const gl = c.__gl, W = c.width, H = c.height, px = new Uint8Array(4 * W); let sum = 0, n = 0;
      for (const yy of [.45, .55, .65, .75, .85]) { gl.readPixels(0, Math.round(H * (1 - yy)), W, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); for (let x = 0; x < W; x += 4) { const k = x * 4; sum += .2126 * lin(px[k]) + .7152 * lin(px[k + 1]) + .0722 * lin(px[k + 2]); n++; } }
      const pageL = Lstar(sum / n), cards = GLASS_RECTS.filter(x => x[0] !== 'nav'), nav = GLASS_RECTS.filter(x => x[0] === 'nav');
      const worst = (rects, ink, a, blur, tint, rel) => Math.min(...glassCheck(c, rects, ink, a, blur, tint, blur ? 1.6 : 1).map(x => x.worst / (rel && x.name === 'hero number' ? 3 : 4.5) * 4.5));
      const need = (rects, ink, tint) => { let lo = 0, hi = .95; const ok = a => worst(rects, ink, a, 0, tint, true) >= 4.6; if (ok(0)) return 0; if (!ok(hi)) return 1; for (let i = 0; i < 7; i++) { const m = (lo + hi) / 2; if (ok(m)) hi = m; else lo = m; } return hi; };
      const res = { pageL };
      for (const k of Object.keys(GlassRule.OPTIONS)) {
        const g = GlassRule.cards(pageL, k, S.pal), tint = GlassRule.OPTIONS[k].tint(S.pal);
        const cardP = worst(cards, g.ink.primary, g.a, 22, g.tint), cardS = worst(cards, g.ink.secondary, g.a, 22, g.tint), navS = worst(nav, g.ink.secondary, g.nav.a, 22, g.tint);
        const nd = need(SKY_TEXT_RECTS, GlassRule.INK.day.sky, '#FFFFFF'), nl = need(SKY_TEXT_RECTS, GlassRule.INK.night.sky, tint);
        const skyMode = nl < nd ? 'night' : 'day', scrimA = Math.min(.9, (skyMode === 'night' ? nl : nd) + (Math.min(nl, nd) > 0 ? .03 : 0)), scrimT = skyMode === 'night' ? tint : '#FFFFFF';
        const sky = worst(SKY_TEXT_RECTS, GlassRule.INK[skyMode].sky, scrimA, 0, scrimT, true);
        // the button picks its own fill from the sky behind it (non-text 3:1): blue by day, a light pill or deep navy where the blue would melt into the sky
        const FILLS = [{ fill: '#0B5FA5', ink: '#FFFFFF' }, { fill: '#DCE9F7', ink: '#0B3A66' }, { fill: '#082F5A', ink: '#FFFFFF' }];
        const bc = FILLS.map(f => Math.min(...glassCheck(c, [['button', 250, 40, 392, 88]], f.fill, scrimA, 0, scrimT).map(x => x.worst)));   // the veil is a top band: the button sits on it too
        const bi = bc.findIndex(v => v >= 3.1), btn = FILLS[bi >= 0 ? bi : bc.indexOf(Math.max(...bc))], btnC = bc[FILLS.indexOf(btn)];
        res[k] = { mode: g.mode, tint: g.tint, a: +g.a.toFixed(2), navA: g.nav.a, cardP: +cardP.toFixed(2), cardS: +cardS.toFixed(2), navS: +navS.toFixed(2), skyMode, scrimT, scrimA: +scrimA.toFixed(2), sky: +sky.toFixed(2), needDark: +nd.toFixed(2), needLight: +nl.toFixed(2), btn, btnC: +btnC.toFixed(2) };
      }
      return res;
    });
    out.m[id] = r;
    console.log(id.padEnd(12), 'page L*', r.pageL.toFixed(1), Object.keys(r).filter(k => k.length === 1).map(k => { const o = r[k]; return `${k}: ${o.mode} ${o.tint} a${o.a} cards ${o.cardP}/${o.cardS}${o.cardS < 4.5 ? '!' : ''} nav ${o.navS}${o.navS < 4.5 ? '!' : ''} sky ${o.skyMode}+${o.scrimA} ${o.sky}${o.sky < 4.5 ? '!' : ''} btn ${o.btnC}${o.btnC < 3 ? '!' : ''}`; }).join(' | '));
  }
  fs.writeFileSync(path.join(OUT, 'measure.json'), JSON.stringify(out, null, 1));
  await b.close();
})();
