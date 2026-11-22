// Round T1: glass + sky-text contrast and GPU cost per terrain direction, from ONE page (compile once; each case is a set()).
//   node contrastT.js [dirs] [extra opts JSON]      e.g. node contrastT.js control,loch '{"dry":0.15}'
// Glass: 22% white, blur 22 px, ink 2 #2E4A62 (AA 4.5). Sky text: ink #0E2A43, no glass (hero number 3:1, the rest 4.5). Same rules as contrast.js.
const { chromium } = require('playwright');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const dirs = (process.argv[2] || 'control,loch,chalk,downs,steppe,bay').split(','), extra = JSON.parse(process.argv[3] || '{}');
const dOpt = d => { const [terrain, tree, leaf, ground] = d.split(':'); return { terrain, tree: tree || null, leaf: leaf || null, ground: ground || null }; };   // T8: terrain[:tree[:leaf[:ground]]]
const CASES = [['fair', 13, 86], ['fair', 8, 86], ['fair', 17, 86], ['partly', 13, 86], ['stratus', 13, 86], ['rain', 13, 86], ['snow', 11, 86], ['valleyFog', 8, 86], [null, 13, null]];
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  await p.goto(`${BASE}/scene.html?sky=fair&h=13&anim=0&seed=${process.env.SEED || 20411}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 600000, polling: 1000 });
  let worstAll = 99;
  for (const d of dirs) {
    let worst = 99, worstAt = '', skyWorst = 99, skyAt = '';
    for (const [sky, h, r] of CASES) {
      await p.evaluate(([d, sky, h, r, ex]) => S.set(Object.assign({}, d, { sky: sky ? SceneGL.PRESETS[sky] : undefined, hour: h, readiness: r }, ex)), [dOpt(d), sky, h, r, extra]);
      await p.waitForTimeout(100); await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 }); await p.waitForTimeout(200);
      const m = await p.evaluate(() => ({ glass: glassCheck(document.getElementById('c'), GLASS_RECTS, '#2E4A62', .22), sky: glassCheck(document.getElementById('c'), SKY_TEXT_RECTS, '#0E2A43', 0, 0) }));
      const g = Math.min(...m.glass.map(x => x.worst)), gname = m.glass.find(x => x.worst === g).name;
      if (g < worst) { worst = g; worstAt = `${sky || 'first run'} ${h}h (${gname})`; }
      for (const x of m.sky) { const need = x.name === 'hero number' ? 3 : 4.5, rel = x.worst / need; if (rel < skyWorst) { skyWorst = rel; skyAt = `${x.name} ${x.worst} at ${sky || 'first run'} ${h}h`; } }
    }
    await p.evaluate(([d, ex]) => S.set(Object.assign({}, d, { sky: SceneGL.PRESETS.rain, hour: 13, readiness: 86 }, ex)), [dOpt(d), extra]);
    await p.waitForFunction(() => S.ready, null, { timeout: 120000, polling: 200 });
    const t = await p.evaluate(() => ({ full: S.bench('full', 8), tilt: S.bench('tilt', 40) }));
    console.log(`${d.padEnd(8)} glass worst ${worst.toFixed(2)} ${worst < 4.5 ? 'FAIL' : 'ok'} at ${worstAt} | sky text worst ${skyAt}${skyWorst < 1 ? ' FAIL' : ''} | rain: full ${t.full.toFixed(2)} ms, compose+post ${t.tilt.toFixed(3)} ms`);
    worstAll = Math.min(worstAll, worst);
  }
  console.log('worst glass', worstAll.toFixed(2)); await b.close();
})();
