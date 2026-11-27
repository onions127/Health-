// LOCK phase 3: parity fixtures for the renderer's inputs. The prototype's scene (scene-gl.js) renders a list of skies, and every
// uniform it sets for the look (sky, land, compose, rays, final) is recorded and written to
// android-app/app/src/test/resources/parity/scene_*.tsv, with the trees' branch texture. The Kotlin SceneParams must give the same.
// Uniforms the renderer owns (texture units, sizes, jitter, time, tilt offsets) are left out.
//   node sky-parity.js            (needs the design-board server on :8765)
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const OUT = path.join(__dirname, '..', '..', 'android-app', 'app', 'src', 'test', 'resources', 'parity');
const SKYK = ['style', 'paintCov', 'cirrus', 'ciHook', 'ciVeil', 'ciAng', 'cover', 'tall', 'grey', 'haze', 'sheet', 'sType', 'sb', 'st', 'fog', 'fogH', 'rain', 'wind', 'snow', 'bolt'];
const SKY0 = { style: 0, paintCov: .5, cirrus: .5, ciHook: .6, ciVeil: 0, ciAng: .95, cover: .3, tall: .62, grey: 0, haze: 0, sheet: 0, sType: 0, sb: 1, st: 1.7, fog: 0, fogH: 0, rain: 0, wind: .45, snow: 0, bolt: 0 };
const SKIP = {
  SKY: ['uShape', 'uDetail', 'uWeather', 'uTreeTex', 'uRes', 'uT', 'uMargin', 'uSplit', 'uOff', 'uFrame', 'uSlice'],
  COMPOSE: ['uS0', 'uS1', 'uS2', 'uS3', 'uM2', 'uM3', 'uO0', 'uO1', 'uO2', 'uO3', 'uRes', 'uMargin', 'uSliceRes', 'uSliceRes1', 'uXf', 'uT', 'uWindDbg'],
  FINAL: ['uScene', 'uB1', 'uB2', 'uR', 'uRes', 'uT'], RAYS: ['uSrc', 'uDst', 'uRes'],
};
SKIP.LAND = SKIP.SKY;
const HOURS = [0.5, 5.25, 5.5, 5.75, 6.5, 7.5, 9, 11.2, 13, 16.5, 17.75, 19, 19.75, 20.25, 20.5, 21, 23.17];
const PRESETS = ['fair', 'partly', 'stratocumulus', 'stratus', 'valleyFog', 'mist', 'rain', 'thunder', 'painted'];
const PALS = { valleyFog: 'fog', rain: 'rain', thunder: 'storm', stratus: 'overcast' };   // the palette objects, by the name SkyPalette uses
(async () => {
  // the realistic skies: a sample of the BodySky fixture's outputs, at their hours
  const bs = fs.readFileSync(path.join(OUT, 'body_sky.tsv'), 'utf8').trim().split('\n').map(l => l.split('\t')), hd = bs.shift();
  const col = (r, k) => r[hd.indexOf(k)];
  const cases = [];
  PRESETS.forEach((n, i) => HOURS.forEach((h, j) => cases.push({ preset: n, hour: h, seed: [20411, 7, 133][(i + j) % 3], doy: [40, 328, 200, 110][(i + j) % 4], scroll: [0, 0, 45, 120, 260][(i + j) % 5] })));
  bs.forEach((r, i) => { if (i % 6) return;
    const sky = Object.fromEntries(SKYK.map(k => [k, +col(r, k)])), pal = col(r, 'pal');
    cases.push({ sky, pal: pal === 'none' ? null : pal, palK: +col(r, 'palK'), hour: +col(r, 'hour'), seed: 20000 + i, doy: (90 + i) % 366, scroll: 0 });
  });

  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 } });
  p.on('pageerror', e => console.log('page error', e.message));
  await p.addInitScript(() => {
    const P = WebGL2RenderingContext.prototype, rec = new Map(); window.__rec = rec; window.__tree = null;
    const o = { ss: P.shaderSource, as: P.attachShader, gul: P.getUniformLocation, ts: P.texSubImage2D };
    P.shaderSource = function (sh, src) { sh.__src = src; return o.ss.call(this, sh, src); };
    P.attachShader = function (pr, sh) { if (!/gl_Position/.test(sh.__src || '')) pr.__fs = sh.__src; return o.as.call(this, pr, sh); };
    P.getUniformLocation = function (pr, n) { const l = o.gul.call(this, pr, n); if (l) { l.__n = n; l.__p = pr; } return l; };
    P.texSubImage2D = function (...a) { const d = a[a.length - 1]; if (d instanceof Float32Array && d.length === 6400) window.__tree = Array.from(d); return o.ts.apply(this, a); };
    for (const m of Object.getOwnPropertyNames(P).filter(m => /^uniform(1|2|3|4)(f|i)v?$/.test(m))) {
      const f = P[m];
      P[m] = function (l, ...v) {
        if (l && l.__p) { if (!rec.has(l.__p)) rec.set(l.__p, {}); rec.get(l.__p)[l.__n] = v.length === 1 && v[0] && v[0].length != null ? Array.from(v[0]) : v; }
        return f.call(this, l, ...v);
      };
    }
  });
  await p.goto('http://127.0.0.1:8765/scene.html?anim=0&seed=20411&doy=328', { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.frames > 1, null, { timeout: 900000, polling: 500 });
  const dpr = await p.evaluate(() => Math.min(2, devicePixelRatio || 1));
  const rows = [['case', 'prog', 'name', 'values']], ins = [['case', 'hour', 'seed', 'doy', 'scroll', 'dpr', 'pal', 'palK', ...SKYK]];
  for (let i = 0; i < cases.length; i++) {
    const c = cases[i];
    const got = await p.evaluate(async ({ c, SKY0, PALS }) => {
      const P = SceneGL.PRESETS, sky = c.sky ? { ...SKY0, ...c.sky } : { ...SKY0, ...P[c.preset] }, palName = c.sky ? c.pal : PALS[c.preset] || null;
      const palObj = { fog: P.valleyFog.pal, rain: P.rain.pal, storm: P.thunder.pal, overcast: P.stratus.pal }[palName];
      if (palObj) { sky.pal = palObj; if (c.sky) sky.palK = c.palK; } else { delete sky.pal; sky.palK = 0; }
      window.__rec.clear(); S.set({ sky, readiness: null, hour: c.hour, seed: c.seed, doy: c.doy, scroll: c.scroll });
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
      const src = SceneGL._src, names = { COMPOSE: src.COMPOSE, FINAL: src.FINAL, RAYS: src.RAYS }, out = {};
      for (const [pr, u] of window.__rec) {
        const fs = pr.__fs || '', n = fs.includes('#define SKY_ONLY') ? 'SKY' : fs.includes('#define LAND_ONLY') ? 'LAND' : Object.keys(names).find(k => names[k] === fs);
        if (n) out[n] = u;
      }
      return { u: out, sky: Object.fromEntries(Object.keys(SKY0).map(k => [k, sky[k]])), pal: palName || 'none', palK: sky.palK || 0 };
    }, { c, SKY0, PALS });
    ins.push([i, c.hour, c.seed, c.doy, c.scroll, dpr, got.pal, got.palK, ...SKYK.map(k => got.sky[k])]);
    for (const prog of ['SKY', 'LAND', 'COMPOSE', 'RAYS', 'FINAL']) {
      const u = got.u[prog]; if (!u) { if (prog !== 'RAYS') console.log('case', i, 'no', prog); continue; }
      for (const [n, v] of Object.entries(u)) if (!SKIP[prog].includes(n)) rows.push([i, prog, n, v.join(',')]);
    }
    if (i % 40 === 0) console.log('case', i, '/', cases.length);
  }
  const tree = await p.evaluate(() => window.__tree);
  // values the same in every case go to one 'static' row each (the land's shapes and tokens)
  const by = new Map(); rows.slice(1).forEach(r => { const k = r[1] + ' ' + r[2]; if (!by.has(k)) by.set(k, new Set()); by.get(k).add(r[3]); });
  const counts = new Map(); rows.slice(1).forEach(r => { const k = r[1] + ' ' + r[2]; counts.set(k, (counts.get(k) || 0) + 1); });
  const stat = [...by].filter(([k, s]) => s.size === 1 && counts.get(k) === cases.length).map(([k]) => k);
  const outRows = [rows[0], ...stat.map(k => { const [pg, n] = k.split(' '); return ['static', pg, n, [...by.get(k)][0]]; }), ...rows.slice(1).filter(r => !stat.includes(r[1] + ' ' + r[2]))];
  const T = rs => rs.map(r => r.join('\t')).join('\n') + '\n';
  fs.writeFileSync(path.join(OUT, 'scene_cases.tsv'), T(ins));
  fs.writeFileSync(path.join(OUT, 'scene_uniforms.tsv'), T(outRows));
  fs.writeFileSync(path.join(OUT, 'scene_tree.tsv'), (tree || []).join(',') + '\n');
  console.log(cases.length, 'cases,', outRows.length - 1, 'uniform rows,', stat.length, 'static; tree', tree ? tree.length : 0, '; dpr', dpr);
  await b.close();
})();
