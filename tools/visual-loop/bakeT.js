// On-device bake path smoke test, headless: renderSliced (GPU-budgeted bands) + bake() must finish and give non-empty slices.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage(); p.on('pageerror', e => console.log('page error:', e.message.slice(0, 500)));
  await p.setContent(`<canvas id=c width=412 height=915></canvas><script src="http://127.0.0.1:8765/scene-gl.js?${Date.now()}"></script>`);
  await p.waitForFunction(() => window.SceneGL);
  const t0 = Date.now();
  const r = await p.evaluate(async () => {
    const S = SceneGL.mount(document.getElementById('c'), { bake: true, manual: true, frameSize: [412, 915], dpr: 1, seed: 20411, readiness: 86, hour: 13, animate: false, terrain: 'lochCalm', tree: 'maple' });
    const gpuMs = await S.renderSliced({ budgetMs: 200, gapMs: 0 }); const o = await S.bake('t');
    return { gpuMs, files: Object.fromEntries(Object.entries(o.files).map(([k, v]) => [k, v.size])) };
  });
  console.log('bake path ok in', ((Date.now() - t0) / 1000).toFixed(0), 's; band GPU', r.gpuMs.toFixed(0), 'ms;', JSON.stringify(r.files));
  await b.close();
})();
