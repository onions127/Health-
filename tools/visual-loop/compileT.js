// Shader compile time, per program and per variant (a function stubbed out), on headless D3D11 like shootT.js.
//   node compileT.js [variants=full] [prog=FRAG]   variant: full | stub:<fn>[+stub:<fn>...] | file:<path to a .glsl>
// Compile + link + one 1x1 draw into the program's real output layout (ANGLE D3D11 compiles some shader variants at first draw).
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const variants = (process.argv[2] || 'full').split(','), progName = process.argv[3] || 'FRAG';
function stub(src, fn) {                                     // replace the body of the first top-level function named fn with a zero return
  const re = new RegExp(String.raw`
(float|vec[234]|void|bool|int) ${fn}\(([^)]*)\)\s*\{`), m = re.exec(src); if (!m) throw new Error('no function ' + fn);
  let i = m.index + m[0].length, d = 1; while (d && i < src.length) { if (src[i] === '{') d++; else if (src[i] === '}') d--; i++; }
  const ret = { float: 'return 0.;', vec2: 'return vec2(0.);', vec3: 'return vec3(0.);', vec4: 'return vec4(0.);', void: '', bool: 'return false;', int: 'return 0;' }[m[1]];
  return src.slice(0, m.index) + `\n${m[1]} ${fn}(${m[2]}){ ${ret} }` + src.slice(i);
}
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist', '--disable-gpu-program-cache', '--disable-gpu-shader-disk-cache'] });
  const p = await b.newPage();
  await p.setContent(`<script src="${BASE}/scene-gl.js?${Date.now()}"></script>`);   // load the renderer without mounting it
  await p.waitForFunction(() => window.SceneGL && SceneGL._src);
  const srcs = await p.evaluate(() => SceneGL._src);
  for (const v of variants) {
    let src = srcs[progName];
    if (v.startsWith('file:')) src = fs.readFileSync(v.slice(5), 'utf8');
    else if (v !== 'full') for (const s of v.split('+')) src = stub(src, s.replace(/^stub:/, ''));
    const q = await b.newPage();                              // a fresh page (fresh context) per variant
    await q.setContent('<canvas id=c width=4 height=4></canvas>');
    const r = await q.evaluate(([VERT, FS, LAYOUT]) => {
      const gl = document.getElementById('c').getContext('webgl2'); gl.getExtension('EXT_color_buffer_float');
      const t0 = performance.now();
      const sh = (t, s) => { const o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o); if (!gl.getShaderParameter(o, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(o)); return o; };
      const pr = gl.createProgram(); gl.attachShader(pr, sh(gl.VERTEX_SHADER, VERT)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FS));
      gl.bindAttribLocation(pr, 0, 'a'); gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
      const t1 = performance.now();
      const nOut = (FS.match(/layout\(location = \d\) out/g) || []).length || 1, fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
      const fmts = LAYOUT === 'one' ? [gl.RGBA16F] : LAYOUT === 'pad' ? [gl.RGBA16F, gl.RGBA8] : [...Array(nOut)].map(() => gl.RGBA16F);
      fmts.forEach((f, i) => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t); gl.texStorage2D(gl.TEXTURE_2D, 1, f, 1, 1); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, t, 0); });
      gl.drawBuffers(fmts.map((_, i) => gl.COLOR_ATTACHMENT0 + i));
      if (LAYOUT === 'tex') {                                  // textures bound as the scene binds them: 3D RGBA16F noise at 0, 1; 2D weather at 2; RGBA32F tree data at 15
        const t3 = u => { gl.activeTexture(gl.TEXTURE0 + u); const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_3D, t); gl.texStorage3D(gl.TEXTURE_3D, 1, gl.RGBA16F, 4, 4, 4); };
        t3(0); t3(1); gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, gl.createTexture()); gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA16F, 4, 4);
        gl.activeTexture(gl.TEXTURE15); gl.bindTexture(gl.TEXTURE_2D, gl.createTexture()); gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA32F, 4, 4);
        gl.useProgram(pr); [['uShape', 0], ['uDetail', 1], ['uWeather', 2], ['uTreeTex', 15]].forEach(([n, u]) => { const l = gl.getUniformLocation(pr, n); if (l) gl.uniform1i(l, u); });
      } gl.viewport(0, 0, 1, 1); gl.useProgram(pr);
      const vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.drawArrays(gl.TRIANGLES, 0, 3); const px = new Uint16Array(4); gl.readBuffer(gl.COLOR_ATTACHMENT0); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.HALF_FLOAT, px);
      return { link: (t1 - t0) / 1000, draw: (performance.now() - t1) / 1000, chars: FS.length };
    }, [srcs.VERT, src, process.env.LAYOUT || '']).catch(e => ({ err: String(e).slice(0, 400) }));
    console.log(v.padEnd(40), r.err ? 'ERROR ' + r.err : `compile+link ${r.link.toFixed(1)} s, first draw ${r.draw.toFixed(1)} s, total ${(r.link + r.draw).toFixed(1)} s`);
    await q.close();
  }
  await b.close();
})();
