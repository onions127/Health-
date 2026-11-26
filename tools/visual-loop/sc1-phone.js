// SC1 on the S21 (Chrome over CDP): compose + post at rest and scrolled, page fps during real swipes, heat.
// Needs: adb reverse tcp:8765 tcp:8765; adb forward tcp:9222 localabstract:chrome_devtools_remote; design-board on :8765; phone unlocked.
const { chromium } = require('playwright'); const { execSync } = require('child_process');
const sh = c => execSync(`adb shell "${c}"`).toString();
const heat = () => { const b = sh('dumpsys battery'), th = sh('dumpsys thermalservice'); return `battery ${+(/temperature: (\d+)/.exec(b) || [])[1] / 10} °C, thermal status ${(/Thermal Status: (\d)/.exec(th) || [])[1]}`; };
(async () => {
  sh('input keyevent KEYCODE_WAKEUP'); sh('am start -n com.android.chrome/com.google.android.apps.chrome.Main'); await new Promise(r => setTimeout(r, 2500));
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222'), ctx = b.contexts()[0], p = ctx.pages()[0] || await ctx.newPage();
  await p.bringToFront(); p.on('pageerror', e => console.log('page error:', e.message.slice(0, 300)));
  console.log('start:', heat());
  const t0 = Date.now();
  await p.goto(`http://localhost:8765/sc1.html?device=1&hud=1&h=7.7&seed=20411&doy=328&anim=${process.env.ANIM || 0}`, { waitUntil: 'commit' });
  await p.waitForFunction(() => window.SC1 && SC1.S.ready, null, { timeout: 900000, polling: 250 });
  console.log(`converged ${((Date.now() - t0) / 1000).toFixed(1)} s`); await p.bringToFront(); await p.waitForTimeout(1500);
  const bench = await p.evaluate(() => { const S = SC1.S, o = {}; for (const y of [0, 150, 0, 150]) { SC1.setY(y); S.bench('tilt', 5); (o['y' + y] = o['y' + y] || []).push(+S.bench('tilt', 30).toFixed(1)); } SC1.setY(0); return o; });
  console.log('compose+post ms/frame:', JSON.stringify(bench));
  // page fps while swiping: count rAF over 6 swipes (down, down, down, up, up, up)
  await p.evaluate(() => { window.__f = []; let last = performance.now(); (function t(n) { window.__f.push(n - last); last = n; requestAnimationFrame(t); })(last); });
  for (const [a, c] of [[1700, 1300], [1700, 1300], [1700, 1300], [900, 1700], [900, 1700], [900, 1700]]) { sh(`input swipe 540 ${a} 540 ${c} 600`); await p.waitForTimeout(900); }
  const fr = await p.evaluate(() => { const f = window.__f.slice(2), s = f.slice().sort((a, b) => a - b); return { frames: f.length, avgFps: +(1000 / (f.reduce((a, b) => a + b, 0) / f.length)).toFixed(1), p50: +s[s.length >> 1].toFixed(1), p95: +s[Math.floor(s.length * .95)].toFixed(1), over17: f.filter(x => x > 17).length, over34: f.filter(x => x > 34).length }; });
  console.log('frame times during swipes (ms):', JSON.stringify(fr), heat());
  await b.close();
})().catch(e => { console.log('FAILED', e.message); process.exit(1); });
