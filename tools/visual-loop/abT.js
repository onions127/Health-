// Baked vs live, headless (design-prototype/ab.html): node abT.js [hours=6.5,13,19,21] [seed] [b=baked:today]. Prints PSNR per hour and the worst tenth.
const { chromium } = require('playwright');
const hours = process.argv[2] || '6.5,13,19,21', seed = process.argv[3] || Math.floor(Date.now() / 864e5), bb = process.argv[4] || 'baked:today';
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 } });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 800)));
  await p.goto(`${process.env.BASE || 'http://127.0.0.1:8765'}/ab.html?a=scene-gl.js&b=${bb}&h=${hours}&seed=${seed}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.AB, null, { timeout: 900000, polling: 1000 });
  console.log(JSON.stringify(await p.evaluate(() => window.AB)));
  await b.close();
})();
