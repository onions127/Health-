/* Health++ on-device sky baker (3.13). A dedicated Web Worker with its own WebGL2 context on an OffscreenCanvas:
   the browser stand-in for the Android app's background job (WorkManager, while charging).
   It renders each requested keyframe with the full scene shader in small GPU-budgeted bands (renderSliced), packs the four
   depth layers as PNG (same format as the PC bake, bake.html) and stores them in Cache Storage, updating the manifest after
   every keyframe so the scene can play whatever is ready.
   In:  { seed, readiness, hours: [..], prefix: 'sky-cache/<seed>-<regime>/', budgetMs, gapMs }   or   { stop: true }
   Out: { type: 'progress', hour, frac } · { type: 'keyframe', hour, bytes, renderMs, gpuMs, encodeMs } · { type: 'done', ms } · { type: 'error', message } */
importScripts('scene-gl.js');

const FRAME = [824, 1830], DPR = 2;                                       // the PC bake's frame: 412×915 CSS at 2x
let ctl = null;

self.onmessage = async ({ data }) => {
  if (data.stop) { if (ctl) ctl.abort(); return; }
  if (ctl) return;                                                         // one bake at a time
  ctl = new AbortController();
  const t0 = performance.now();
  try {
    const { seed, readiness, hours, prefix, budgetMs = 8, gapMs = 16 } = data;
    const cache = await caches.open(SceneGL.SKY_CACHE), key = f => new URL(prefix + f, self.location.href).href;
    const prev = await cache.match(key('manifest.json'));
    const man = prev ? await prev.json() : { fmt: 'rgbm-sqrt8 + aux(a, wind r, wind g); rows top-down; baked on device', seed, made: new Date().toISOString(), bakes: [] };
    const canvas = new OffscreenCanvas(FRAME[0], FRAME[1]);
    const S = SceneGL.mount(canvas, { bake: true, manual: true, frameSize: FRAME, dpr: DPR, seed, readiness, hour: hours[0], animate: false });
    for (const hour of hours) {
      const name = `${SceneGL.regimeOf(readiness)}-${String(hour).replace('.', '_')}`;
      if (man.bakes.some(e => e.name === name)) continue;                  // already in the cache
      S.set({ hour });
      const t = performance.now();
      const gpuMs = await S.renderSliced({ budgetMs, gapMs, signal: ctl.signal, onProgress: frac => self.postMessage({ type: 'progress', hour, frac }) });
      const renderMs = performance.now() - t, te = performance.now();
      const r = await S.bake(name);                                        // GPU encode + PNG (filtering and deflate run here, off the page's thread)
      let bytes = 0;
      for (const [f, blob] of Object.entries(r.files)) { await cache.put(key(f), new Response(blob, { headers: { 'Content-Type': 'image/png' } })); bytes += blob.size; }
      Object.assign(man, r.frame); man.bakes.push(Object.assign(r.entry, { bytes }));
      await cache.put(key('manifest.json'), new Response(JSON.stringify(man), { headers: { 'Content-Type': 'application/json' } }));
      self.postMessage({ type: 'keyframe', hour, bytes, renderMs, gpuMs, encodeMs: performance.now() - te });
    }
    self.postMessage({ type: 'done', ms: performance.now() - t0 });
  } catch (e) {
    self.postMessage({ type: e.message === 'aborted' ? 'stopped' : 'error', message: e.message });
  } finally { ctl = null; }
};
