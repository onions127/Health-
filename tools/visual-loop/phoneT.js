// Phone test over adb (Galaxy S21, Chrome): live load (compile + converge), GPU cost, baked playback, page fps, heat.
// Needs: adb reverse tcp:8765 tcp:8765; adb forward tcp:9222 localabstract:chrome_devtools_remote; design-board on :8765; phone unlocked.
//   node phoneT.js [seed]
const { chromium } = require('playwright'); const { execSync } = require('child_process');
const seed = process.argv[2] || Math.floor(Date.now() / 864e5), URL0 = 'http://localhost:8765/phone.html';
const sh = c => execSync(`adb shell "${c}"`).toString();
const heat = () => { const b = sh('dumpsys battery'), t = +(/temperature: (\d+)/.exec(b) || [])[1] / 10, th = sh('dumpsys thermalservice'); const ap = (/mValue=([\d.]+), mType=0, mName=AP/.exec(th) || [])[1]; return `battery ${t} °C, AP ${ap} °C, thermal status ${(/Thermal Status: (\d)/.exec(th) || [])[1]}, level ${(/level: (\d+)/.exec(b) || [])[1]}%`; };
const fps = p => p.evaluate(() => new Promise(r => { let n = 0; const t0 = performance.now(); const f = () => { n++; performance.now() - t0 < 5000 ? requestAnimationFrame(f) : r(+(n * 1000 / (performance.now() - t0)).toFixed(1)); }; requestAnimationFrame(f); }));
(async () => {
  sh('input keyevent KEYCODE_WAKEUP'); sh('am start -n com.android.chrome/com.google.android.apps.chrome.Main'); await new Promise(r => setTimeout(r, 2500));
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222'), ctx = b.contexts()[0], p = ctx.pages().find(x => x.url().startsWith(URL0)) || await ctx.newPage();
  await p.bringToFront();   // a background tab is throttled to ~1 fps
  const only = (process.env.ONLY || '').split(',').filter(Boolean);
  const cdp = await ctx.newCDPSession(p); await cdp.send('Network.enable'); await cdp.send('Network.clearBrowserCache'); await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
  p.on('console', m => { const t = m.text(); if (t.startsWith('[load]') || m.type() === 'error') console.log('   ', t.slice(0, 200)); });
  p.on('response', r => { if (r.status() >= 400) console.log('   ', r.status(), r.url().slice(0, 120)); });
  p.on('pageerror', e => console.log('page error:', e.message.slice(0, 500)));
  await p.addInitScript(() => { window.SCENE_T = true; });
  console.log('start:', heat());
  for (const [name, q] of [['live, cold', 'h=13&sky=fair&anim=0'], ['live, warm reload', 'h=13&sky=fair&anim=0'], ['baked today 13:00', 'baked=today&h=13'], ['baked today 19:30 (blend)', 'baked=today&h=19.5']]) {
    if (only.length && !only.some(o => name.startsWith(o))) continue;
    const t0 = Date.now();
    await p.goto(`${URL0}?${q}&seed=${seed}&hud=1`, { waitUntil: 'commit' });
    await p.waitForFunction(() => window.PHONE && PHONE.S && PHONE.S.ready, null, { timeout: 900000, polling: 250 });
    const t = (Date.now() - t0) / 1000;
    await p.bringToFront(); await p.waitForTimeout(1500);
    const f = await fps(p), bn = await p.evaluate(() => { const r = PHONE.S.bench('tilt', 30); return r; }).catch(e => 'bench failed ' + e.message);
    console.log(`${name}: ready ${t.toFixed(1)} s after navigation; page ${f} fps idle; tilt frame ${JSON.stringify(bn)}; ${heat()}`);
    if (name === 'live, cold') { const full = await p.evaluate(() => PHONE.S.bench('full', 3)).catch(e => 'failed ' + e.message); console.log('   full render (4 slices + compose + post):', JSON.stringify(full)); }
  }
  await p.goto(`${URL0}?baked=today&h=13&seed=${seed}&hud=1`, { waitUntil: 'commit' });
  await p.waitForFunction(() => PHONE.S && PHONE.S.ready, null, { timeout: 120000, polling: 250 });
  await p.bringToFront(); console.log('baked, 3 min with wind and birds:');
  for (let i = 0; i < 6; i++) { await p.waitForTimeout(30000); console.log(`   ${(i + 1) * 30} s: page ${await fps(p)} fps; ${heat()}`); }
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: false });
  await b.close();
})().catch(e => { console.log('FAILED', e.message); process.exit(1); });
