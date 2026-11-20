// Measured contrast on the rendered scene (glass-check.js) and GPU frame cost, headless on the real GPU.
//   node contrast.js            -> every day state below; prints the worst point per card and per text-on-sky block
//   node contrast.js cost       -> also times a full slice render and a compose+post (tilt) frame
// Glass: 22% white, blur 22 px, ink 2 #2E4A62 (AA 4.5). Sky text: ink #0E2A43, no glass (hero number needs 3:1, the rest 4.5).
const { chromium } = require('playwright');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const CASES = (process.env.CASES || 'r=86&h=13,r=70&h=13,r=55&h=13,r=40&h=13,r=86&h=8,r=86&h=17,sky=partly&h=13,sky=stratus&h=13,sky=rain&h=13,sky=snow&h=11,sky=valleyFog&h=8,sky=painted&h=13,r=null&h=13').split(',');
const cost = process.argv.includes('cost');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  let worst = 99;
  for (const c of CASES) {
    const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
    await p.goto(`${BASE}/scene.html?${c}&anim=0&seed=${process.env.SEED || 20411}`, { waitUntil: 'load' });
    await p.waitForFunction(() => window.S && S.ready, null, { timeout: 60000 }); await p.waitForTimeout(300);
    const r = await p.evaluate(() => ({ glass: glassCheck(document.getElementById('c'), GLASS_RECTS, '#2E4A62', .22), sky: glassCheck(document.getElementById('c'), SKY_TEXT_RECTS, '#0E2A43', 0, 0) }));
    const g = Math.min(...r.glass.map(x => x.worst)); worst = Math.min(worst, g);
    const sky = r.sky.map(x => `${x.name} ${x.worst}${x.worst < (x.name === 'hero number' ? 3 : 4.5) ? ' FAIL' : ''}`).join(', ');
    let line = `${c.padEnd(22)} glass worst ${g.toFixed(2)} (${r.glass.find(x => x.worst === g).name})${g < 4.5 ? ' FAIL' : ''} | sky text: ${sky}`;
    if (cost) { const t = await p.evaluate(() => ({ full: S.bench('full', 8), tilt: S.bench('tilt', 40) })); line += ` | full ${t.full.toFixed(1)} ms, compose+post ${t.tilt.toFixed(2)} ms`; }
    console.log(line); await p.close();
  }
  console.log('worst glass', worst.toFixed(2)); await b.close();
})();
