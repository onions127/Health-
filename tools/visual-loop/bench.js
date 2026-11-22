// GPU cost per build: node bench.js [src ...]   (default: the 3.14 copy and the current scene-gl.js). Full = one slice render of all four slices + compose + post.
const { chromium } = require('playwright');
const srcs = process.argv.slice(2).length ? process.argv.slice(2) : ['scene-gl-314.js', 'scene-gl.js'];
const qs = (process.env.CASES || 'r=86&h=13,sky=partly&h=13,sky=rain&h=13').split(',');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  for (let rep = 0; rep < 2; rep++) for (const src of srcs) for (const q of qs) {          // alternate builds twice: GPU clocks drift
    const p = await b.newPage({ viewport: { width: 412, height: 915 } });
    await p.goto(`http://127.0.0.1:8765/scene.html?${q}&anim=0&src=${src}`); await p.waitForFunction(() => window.S && S.ready, null, { timeout: 60000 });
    const t = await p.evaluate(() => [S.bench('full', 10), S.bench('tilt', 60)]);
    console.log(src.padEnd(18), q.padEnd(16), `full ${t[0].toFixed(2)} ms  compose+post ${t[1].toFixed(2)} ms`); await p.close();
  }
  await b.close();
})();
