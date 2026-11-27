// LOCK phase 2 spike: the prototype's own frame at 824x1830 (412x915 at x2), to pixdiff against the S21 spike's dump.
//   node spike-ref.js fair13 'sky=fair&h=13'    -> out/spike/proto/skyspike-fair13.png
const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
const [name, q] = process.argv.slice(2);
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2 });
  await p.goto(`http://127.0.0.1:8765/scene.html?${q}&anim=0&seed=20411&doy=328`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.S && S.ready, null, { timeout: 900000, polling: 1000 });
  await p.waitForTimeout(500);
  const url = await p.evaluate(() => document.getElementById('c').toDataURL('image/png'));
  const dir = path.join(__dirname, 'out', 'spike', 'proto'); fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `skyspike-${name}.png`), Buffer.from(url.split(',')[1], 'base64'));
  console.log('wrote', name, await p.evaluate(() => [document.getElementById('c').width, document.getElementById('c').height]));
  await b.close();
})();
