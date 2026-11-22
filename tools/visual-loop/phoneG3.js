// G3 on the phone (Galaxy S21, Chrome over adb + DevTools): play today's bakes at the dusk / night / dawn hours, measure the sky text
// (number, header) on the phone's own canvas with no veil (glass-check.js, light and dark ink, 3:1 floor), the button, page fps, and a device screenshot.
// Raw CDP on the phone.html test tab (Playwright's browser-level attach hung with ~20 tabs open; Android Chrome refuses /json/new).
// Needs: adb reverse tcp:8765 tcp:8765; adb forward tcp:9222 localabstract:chrome_devtools_remote; design-board on :8765; phone unlocked.
//   node phoneG3.js [hours=19.75,20.5,23,5.5]
const { execSync } = require('child_process'); const path = require('path');
const hours = (process.argv[2] || '19.75,20.5,23,5.5').split(','), seed = Math.floor(Date.now() / 864e5), DT = 'http://127.0.0.1:9222', URL0 = 'http://localhost:8765/phone.html';
const OUT = path.join(__dirname, 'out'), sh = c => execSync(`adb shell "${c}"`).toString(), sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  sh('input keyevent KEYCODE_WAKEUP');
  const tab = (await (await fetch(`${DT}/json/list`)).json()).find(t => t.type === 'page' && t.url.startsWith(URL0));   // Chrome on Android refuses /json/new: reuse the test tab
  if (!tab) throw new Error('open ' + URL0 + ' in Chrome on the phone once'); await fetch(`${DT}/json/activate/${tab.id}`);
  const ws = new WebSocket(tab.webSocketDebuggerUrl); await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0; const wait = new Map(); ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && wait.has(m.id)) { wait.get(m.id)(m); wait.delete(m.id); } };
  const send = (method, params = {}) => new Promise(r => { const i = ++id; wait.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async (expr, timeout = 300000) => { const m = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true, timeout }); if (m.result?.exceptionDetails) throw new Error(JSON.stringify(m.result.exceptionDetails).slice(0, 400)); return m.result?.result?.value; };
  await send('Page.enable'); await send('Runtime.enable'); await send('Page.bringToFront');
  // the app is full screen (360x800 CSS on the S21, the bakes' 412x915 aspect); Chrome's address bar leaves 360x691, which moves the sky under the text
  await send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 2, mobile: true });
  for (const h of hours) {
    await send('Page.navigate', { url: `${URL0}?baked=today&h=${h}&seed=${seed}&hud=0` }); await sleep(1500);
    const t0 = Date.now();
    await ev(`new Promise((r, j) => { const t = setInterval(() => { if (window.PHONE && PHONE.S && PHONE.S.ready) { clearInterval(t); r(1); } }, 250); setTimeout(() => j('not ready'), 280000); })`);
    const ready = (Date.now() - t0) / 1000 + 1.5; await send('Page.bringToFront'); await sleep(1500);
    await ev(`new Promise(r => { const s = document.createElement('script'); s.src = 'glass-check.js?' + Date.now(); s.onload = r; document.head.appendChild(s); })`);
    const r = await ev(`(() => { const c = document.getElementById('c'), m = ink => Math.min(...glassCheck(c, SKY_TEXT_RECTS, ink, 0, 0).map(x => x.worst));
      const btn = ['#0B5FA5', '#FFFFFF', '#082F5A'].map(f => glassCheck(c, [['b', 250, 40, 392, 88]], f, 0, 0)[0].worst);
      return { W: c.width, H: c.height, css: [innerWidth, innerHeight], light: m('#F2F6FB'), dark: m('#0E2A43'), btn: Math.max(...btn), err: c.__gl ? c.__gl.getError() : -1 }; })()`);
    const f = await ev(`new Promise(r => { let n = 0; const t0 = performance.now(); const f = () => { n++; performance.now() - t0 < 4000 ? requestAnimationFrame(f) : r(+(n * 1000 / (performance.now() - t0)).toFixed(1)); }; requestAnimationFrame(f); })`);
    const shot = await send('Page.captureScreenshot', { format: 'png' }); require('fs').writeFileSync(path.join(OUT, `phone-g3-${h}.png`), Buffer.from(shot.result.data, 'base64'));
    console.log(`h ${h}: ready ${ready.toFixed(1)} s; canvas ${r.W}x${r.H} (css ${r.css}); GL error ${r.err}; sky text, no veil: light ink ${r.light.toFixed(2)}, dark ink ${r.dark.toFixed(2)} -> ${Math.max(r.light, r.dark) >= 3 ? 'PASS' : 'FAIL'} 3:1; button best ${r.btn.toFixed(2)}; page ${f} fps`);
  }
  await send('Emulation.clearDeviceMetricsOverride'); ws.close();
  const b = sh('dumpsys battery'); console.log(`battery ${+(/temperature: (\d+)/.exec(b) || [])[1] / 10} °C, level ${(/level: (\d+)/.exec(b) || [])[1]}%`);
})().catch(e => { console.log('FAILED', e.message || e); process.exit(1); });
