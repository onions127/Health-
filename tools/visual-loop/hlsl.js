// Dump ANGLE's translated HLSL for a program's fragment shader (no link, so no FXC compile): node hlsl.js [prog=FRAG] > out.hlsl
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist', '--enable-privileged-webgl-extensions'] });
  const p = await b.newPage();
  await p.setContent(`<canvas id=c></canvas><script src="http://127.0.0.1:8765/scene-gl.js?${Date.now()}"></script>`);
  await p.waitForFunction(() => window.SceneGL && SceneGL._src);
  const r = await p.evaluate(n => { const gl = document.getElementById('c').getContext('webgl2'), x = gl.getExtension('WEBGL_debug_shaders');
    const o = gl.createShader(gl.FRAGMENT_SHADER); gl.shaderSource(o, SceneGL._src[n]); gl.compileShader(o); return x ? x.getTranslatedShaderSource(o) : 'no WEBGL_debug_shaders'; }, process.argv[2] || 'FRAG');
  process.stdout.write(r); await b.close();
})();
