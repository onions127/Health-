// LOCK phase 2 spike: the prototype's shaders and one state's uniforms, for the S21 renderer spike (android-app/app/src/debug).
// Records every uniform the prototype sets (per program, last value) and the trees' branch texture, while it renders a state.
//   node spike-export.js            (needs the design-board server on :8765)
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const OUT = path.join(__dirname, '..', '..', 'android-app', 'app', 'src', 'debug', 'assets', 'skyspike');
const STATES = { fair13: 'sky=fair&h=13', stratus13: 'sky=stratus&h=13', fair2010: 'sky=fair&h=20.17' };
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  for (const [name, q] of Object.entries(STATES)) {
    const p = await b.newPage({ viewport: { width: 412, height: 915 } });
    p.on('pageerror', e => console.log('page error', e.message));
    await p.addInitScript(() => {
      const P = WebGL2RenderingContext.prototype, rec = new Map(); window.__rec = rec; window.__tree = null;
      const o = { ss: P.shaderSource, as: P.attachShader, gul: P.getUniformLocation, ts: P.texSubImage2D };
      P.shaderSource = function (sh, src) { sh.__src = src; return o.ss.call(this, sh, src); };
      P.attachShader = function (pr, sh) { if (/out vec4|layout\(location/.test(sh.__src || '') && !/gl_Position/.test(sh.__src)) pr.__fs = sh.__src; return o.as.call(this, pr, sh); };
      P.getUniformLocation = function (pr, n) { const l = o.gul.call(this, pr, n); if (l) { l.__n = n; l.__p = pr; } return l; };
      P.texSubImage2D = function (...a) { const d = a[a.length - 1]; if (d instanceof Float32Array && d.length === 6400) window.__tree = Array.from(d); return o.ts.apply(this, a); };
      for (const m of Object.getOwnPropertyNames(P).filter(m => /^uniform(1|2|3|4)(f|i)v?$/.test(m))) {
        const f = P[m];
        P[m] = function (l, ...v) {
          if (l && l.__p) { if (!rec.has(l.__p)) rec.set(l.__p, {}); const val = v.length === 1 && v[0] && v[0].length != null ? Array.from(v[0]) : v; rec.get(l.__p)[l.__n] = { t: m.replace('uniform', ''), v: val }; }
          return f.call(this, l, ...v);
        };
      }
    });
    await p.goto(`http://127.0.0.1:8765/scene.html?${q}&anim=0&seed=20411&doy=328`, { waitUntil: 'commit' });
    await p.waitForFunction(() => window.S && S.ready, null, { timeout: 900000, polling: 1000 });
    const data = await p.evaluate(() => {
      const src = SceneGL._src, names = { COMPOSE: src.COMPOSE, FINAL: src.FINAL, BRIGHT: src.BRIGHT, BLUR: src.BLUR, RAYS: src.RAYS, GEN: src.GEN };
      const out = { uniforms: {}, tree: window.__tree, shaders: {} };
      for (const [pr, u] of window.__rec) {
        const fs = pr.__fs || '';
        const n = fs.includes('#define SKY_ONLY') ? 'SKY' : fs.includes('#define LAND_ONLY') ? 'LAND' : Object.keys(names).find(k => names[k] === fs);
        if (n) out.uniforms[n] = u;
      }
      const part = d => src.FRAG.replace('#version 300 es\n', '#version 300 es\n#define ' + d + '\n');
      Object.assign(out.shaders, { VERT: src.VERT, GEN: src.GEN, SKY: part('SKY_ONLY'), LAND: part('LAND_ONLY'), COMPOSE: src.COMPOSE, FINAL: src.FINAL, BRIGHT: src.BRIGHT, BLUR: src.BLUR, RAYS: src.RAYS });
      return out;
    });
    for (const [k, s] of Object.entries(data.shaders)) fs.writeFileSync(path.join(OUT, k + '.glsl'), s);
    fs.writeFileSync(path.join(OUT, `state-${name}.json`), JSON.stringify({ uniforms: data.uniforms, tree: data.tree }));
    await p.screenshot({ path: path.join(OUT, '..', '..', '..', '..', '..', '..', 'tools', 'visual-loop', 'out', `spike-ref-${name}.png`) });
    console.log(name, Object.keys(data.uniforms).join(','), 'tree', data.tree ? data.tree.length : 0);
    await p.close();
  }
  await b.close();
})();
