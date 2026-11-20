/* Health++ meadow scene, GPU version 2 (exploration only).
   Clouds are a real volume, built the way current game engines build skies (Schneider, "Nubis", Guerrilla 2015-2023):
   - Noise is generated once on the GPU into tileable textures: a 128^3 Perlin-Worley volume for the cloud shapes,
     a 64^3 Worley volume for the billows, and a 512^2 Perlin weather map that says where clouds are and how tall they grow.
   - Each sky pixel marches a ray from the camera through the cloud layer (base 1.5 km, tops up to 8.5 km, no screen cap),
     so clouds have true perspective: near ones are big and run off the top of the screen, far ones shrink into the haze.
   - Light: Beer-Lambert shadowing toward the sun, a two-lobe Henyey-Greenstein phase (silver lining near the sun),
     an octave approximation of multiple scattering (soft bright interiors), sky and ground ambient, aerial perspective.
   - Sky: elevation-based gradient from the sampled weather-app colours plus a Mie forward-scattering halo.
   - Land: five layers as before; cloud shadows on the ground are now cast by the actual cloud volume.
   Everything is computed in linear light and converted to sRGB at the end.
   WebGL2 (GLSL ES 3.0). For Android: AGSL has no 3D textures, so the app version either packs the volumes into a 2D atlas
   or bakes frames per state on a desktop GPU (see LOG.md 3.4).
   Usage: const s = SceneGL.mount(canvas, { time:'day', readiness:86 }); s.set({ time:'dusk' }); */
(function () {
  const VERT = `#version 300 es
in vec2 a; void main(){ gl_Position = vec4(a, 0., 1.); }`;

  // ---------- one-time noise generation (tileable Perlin and Worley)
  const GEN = `#version 300 es
precision highp float;
uniform float uZ, uN; uniform int uMode;
out vec4 o;
vec3 h33(vec3 p){ p = fract(p*vec3(.1031, .1030, .0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx)*p.zyx); }
float grad(vec3 i, vec3 f, vec3 c, float per){ return dot(normalize(h33(mod(i + c, per))*2. - 1. + 1e-4), f - c); }
float perlin(vec3 p, float per){                       // classic gradient (Perlin) noise, quintic fade, period 'per' cells
  vec3 i = floor(p), f = fract(p), u = f*f*f*(f*(f*6. - 15.) + 10.);
  return mix(mix(mix(grad(i,f,vec3(0,0,0),per), grad(i,f,vec3(1,0,0),per), u.x), mix(grad(i,f,vec3(0,1,0),per), grad(i,f,vec3(1,1,0),per), u.x), u.y),
             mix(mix(grad(i,f,vec3(0,0,1),per), grad(i,f,vec3(1,0,1),per), u.x), mix(grad(i,f,vec3(0,1,1),per), grad(i,f,vec3(1,1,1),per), u.x), u.y), u.z);
}
float perlinFbm(vec3 p, float per, int oct){ float v = 0., a = 1., n = 0.;
  for (int k = 0; k < 8; k++) { if (k >= oct) break; v += a*perlin(p, per); n += a; p *= 2.; per *= 2.; a *= .5; }
  return clamp(v/n*1.25*.5 + .5, 0., 1.); }
float worley(vec3 p, float per){ vec3 i = floor(p), f = fract(p); float d = 1.;
  for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
    vec3 c = vec3(x, y, z); vec3 r = c + h33(mod(i + c, per)) - f; d = min(d, dot(r, r)); }
  return 1. - sqrt(d); }                                 // inverted: 1 at the centre of each billow
float worleyFbm(vec3 p, float per){ return worley(p, per)*.625 + worley(p*2., per*2.)*.25 + worley(p*4., per*4.)*.125; }
float remap(float v, float a, float b, float c, float d){ return c + (v - a)/(b - a)*(d - c); }
void main(){
  vec3 p = vec3(gl_FragCoord.xy, uZ + .5)/uN;
  if (uMode == 0) {                                      // shape: Perlin-Worley + three Worley octaves
    float pf = perlinFbm(p*4., 4., 7);
    float w1 = worleyFbm(p*4., 4.), w2 = worleyFbm(p*8., 8.), w3 = worleyFbm(p*16., 16.);
    o = vec4(clamp(remap(pf, 0., 1., w1, 1.), 0., 1.), w1, w2, w3);
  } else if (uMode == 1) {                               // detail: Worley at three scales
    o = vec4(worleyFbm(p*2., 2.), worleyFbm(p*4., 4.), worleyFbm(p*8., 8.), 1.);
  } else {                                               // weather map: coverage, tower height, cirrus
    o = vec4(perlinFbm(vec3(p.xy*5., .5), 5., 6), perlinFbm(vec3(p.xy*3. + 11., .5), 3., 4), perlinFbm(vec3(p.xy*8. + 5., .5), 8., 6), 1.);
  }
}`;

  // ---------- the scene
  const FRAG = `#version 300 es
precision highp float; precision highp sampler3D;
uniform sampler3D uShape, uDetail; uniform sampler2D uWeather;
uniform vec2 uRes; uniform float uT, uCover, uTall, uGrey, uNight, uHaze, uSunI;
uniform vec3 uZen, uMid, uHor, uSunC, uCLit, uCDark, uFog, uH0, uH1, uH2, uH3, uTree, uPath, uKey;
uniform vec2 uSun; uniform float uSunR, uShowSun, uStepK, uFrame; uniform vec2 uOff;
out vec4 o;

float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x*p.y); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); vec2 u = f*f*(3. - 2.*f);
  return mix(mix(hash(i), hash(i + vec2(1., 0.)), u.x), mix(hash(i + vec2(0., 1.)), hash(i + vec2(1., 1.)), u.x), u.y); }
float fbm(vec2 p){ float v = 0., a = .5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6); for (int i = 0; i < 6; i++) { v += a*noise(p); p = m*p; a *= .5; } return v; }
float fbm3(vec2 p){ float v = 0., a = .5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6); for (int i = 0; i < 3; i++) { v += a*noise(p); p = m*p; a *= .5; } return v; }
float fall(float a, float b, float x){ return 1. - smoothstep(b, a, x); }
float lum(vec3 c){ return dot(c, vec3(.299, .587, .114)); }
float remap(float v, float a, float b, float c, float d){ return c + (v - a)/(b - a)*(d - c); }
vec3 toL(vec3 c){ return pow(c, vec3(2.2)); }
vec3 toS(vec3 c){ return pow(max(c, 0.), vec3(1./2.2)); }
float hgN(float c, float g){ float g2 = g*g; return (1. - g2)/pow(1. + g2 - 2.*g*c, 1.5); }   // 4pi * Henyey-Greenstein

// ---------- camera: eye on the ground, horizon 40% down the screen
const float HOR = .40, FOC = 1.0;
vec3 rayAt(vec2 frag){ vec2 s = vec2(frag.x - .5*uRes.x, frag.y - (1. - HOR)*uRes.y)/uRes.y; return normalize(vec3(s, FOC)); }

// ---------- sky (linear light)
vec3 sky(vec3 rd, vec3 sd){
  float e = rd.y, c = dot(rd, sd);
  vec3 col = mix(toL(uHor), toL(uMid), 1. - exp(-max(e, 0.)*16.));
  col = mix(col, toL(uZen), smoothstep(.05, .42, e));
  col += toL(uSunC)*(.0035*hgN(c, .90) + .012*hgN(c, .45))*(1. - uNight*.85)*uShowSun;   // Mie halo
  return col;
}

// ---------- cloud volume (km)
const float BASE = 1.5, THICK = 7.0, SIG = 55.;
vec3 wind(){ return vec3(uT*.010, 0., uT*.003); }
float density(vec3 p, bool full){
  vec3 q = p + wind();
  vec4 w = textureLod(uWeather, q.xz/16., 0.);
  float wr = clamp((w.r - .51)*3.3 + .5, 0., 1.), wg = clamp((w.g - .51)*3.6 + .5, 0., 1.);   // stretch measured p10-p90 to 0..1
  float cov = smoothstep(.72 - uCover*.9, 1.30 - uCover*.9, wr)*smoothstep(4., 10., p.z);   // open sky overhead, clouds start a few km out
  if (cov <= 0.) return 0.;
  float hf = (p.y - BASE)/THICK;
  float top = mix(.12, 1., pow(wg, 1.7))*uTall*sqrt(cov);                 // domes: tallest at each cloud's core
  float hg = smoothstep(0., .03, hf)*sqrt(clamp(1. - hf/max(top, .02), 0., 1.));
  if (hg <= 0.) return 0.;
  vec4 n = textureLod(uShape, q/6., 0.);
  float pw = clamp((n.r - .62)/.26, 0., 1.);                              // Perlin-Worley, stretched from measured range
  float wf = clamp(((n.g*.625 + n.b*.25 + n.a*.125) - .30)/.36, 0., 1.);   // Worley billows
  float b = pw*.55 + wf*.45;
  b = remap(b*hg, 1. - cov*.85, 1., 0., 1.)*cov;
  if (b <= 0.) return 0.;
  if (full) {
    vec3 dn = textureLod(uDetail, q/2.6 + vec3(0., uT*.004, 0.), 0.).rgb;
    float far = smoothstep(9., 30., p.z);                                  // drop the finest octave with distance: it aliases at grazing angles
    float hfb = mix(dn.r*.6 + dn.g*.3 + dn.b*.1, dn.r, far);
    float m = mix(1. - hfb, hfb, clamp(hf/max(top, .05)*5., 0., 1.));   // wispy at the base, cauliflower above
    b = remap(b, m*mix(.32, .18, far), 1., 0., 1.);
  }
  return clamp(b*1.3, 0., 1.);
}
float lightOD(vec3 p, float j){ float od = 0., s = .06 + .08*j; for (int j = 0; j < 6; j++) { p += uKey*s; od += density(p, false)*s; s *= 1.75; } return od*SIG; }

// returns linear radiance in rgb, transmittance in a
vec4 clouds(vec3 rd, vec3 sd, vec3 skyH, float jit){
  if (rd.y < .004) return vec4(0., 0., 0., 1.);
  float t0 = BASE/rd.y, t1 = min((BASE + THICK)/rd.y, 70.);
  if (t0 > 70.) return vec4(0., 0., 0., 1.);
  float c = dot(rd, sd);
  float ph = min(mix(hgN(c, .50), hgN(c, -.20), .40), 2.4);   // capped so the side facing the sun glows without blowing out
  vec3 sunL = toL(uCLit)*uSunI, ambHi = mix(toL(uMid), vec3(1.), .30)*.48, ambLo = mix(toL(uCDark), toL(uMid), .25)*.62;
  float T = 1.; vec3 S = vec3(0.);
  float t = t0 + jit*max(.035, t0*.008);
  for (int i = 0; i < 200; i++) {
    if (t > t1 || T < .015) break;
    float dt = max(.035, t*.008)*uStepK;
    vec3 p = rd*t;
    float d = density(p, false);
    if (d > 0.) {
      d = density(p, true);
      if (d > 0.) {
        float od = lightOD(p, fract(jit + float(i)*.618));
        float e = 0., a = 1., k = 1.;
        for (int m = 0; m < 3; m++) { e += a*exp(-od*k)*mix(ph, 1., 1. - a); a *= .42; k *= .38; }   // multiple-scattering octaves
        float powder = 1. - exp(-d*SIG*.5);
        float hf = clamp((p.y - BASE)/(THICK*uTall), 0., 1.);
        vec3 L = sunL*e*mix(.35, 1., powder) + mix(ambLo, ambHi, smoothstep(0., .6, hf));
        L = mix(L, skyH, 1. - exp(-t/26.));                              // aerial perspective
        float st = exp(-d*SIG*dt);
        S += T*L*(1. - st); T *= st;
      }
    } else dt *= 1.3;
    t += dt;
  }
  float fade = smoothstep(.004, .03, rd.y);
  return vec4(S*fade, mix(1., T, fade));
}

// ---------- land
float R0(float x){ return .392 + .060*(fbm(vec2(x*7.0 + 2., .3)) - .45); }
float R1(float x){ return .468 + .070*(fbm3(vec2(x*4.2 + 7., 1.1)) - .40); }
float R2(float x){ return .575 + .085*(fbm3(vec2(x*3.0 + 13., 2.2)) - .40); }
float R3(float x){ return .705 + .095*(fbm3(vec2(x*2.2 + 21., 3.3)) - .40); }
float R4(float x){ return .855 + .050*(fbm3(vec2(x*2.6 + 31., 4.4)) - .40) - .006*noise(vec2(x*520., 1.)); }
float Rk(int k, float x){ if (k == 1) return R1(x); if (k == 2) return R2(x); return R3(x); }

vec3 shadeLayer(vec3 base, float x, float y, float r, float sl, float fog, float tex, float depth, float aspect){
  float t = y - r;
  vec2 n = normalize(vec2(sl, -1.));
  vec2 L = normalize(vec2(uSun.x*aspect - x, uSun.y - y - .2));
  float lam = clamp(dot(n, L), 0., 1.);
  float crest = 1. - smoothstep(0., .05, t);
  vec3 c = base*mix(.88, 1.10, mix(.55, lam, crest));
  c *= mix(1.03, .90, smoothstep(0., depth, t));
  c *= .95 + .09*fbm3(vec2(x*tex, y*tex*1.3));
  return mix(c, uFog, fog);
}

vec3 trees(float x, float y, int k, float seed, float cw, float prob, float sz, float spread){
  float m = 0., lit = 0., sh = 0.;
  float side = uSun.x > .5 ? 1. : -1.;
  float c0 = floor(x/cw);
  for (int i = -1; i <= 1; i++) {
    float c = c0 + float(i);
    if (hash(vec2(c, seed)) < prob) {
      float cx = (c + .15 + .7*hash(vec2(c, seed + 3.)))*cw;
      float gy = Rk(k, cx) + spread*hash(vec2(c, seed + 7.));
      float r = sz*(.7 + .6*hash(vec2(c, seed + 11.)));
      vec2 d = vec2(x - cx, (y - (gy - r*1.05))*1.15);
      vec2 u = d/r;
      float e = length(u) + (fbm3(u*3.2 + c*7.) - .40)*.38;
      float a = 1. - smoothstep(.93, 1.0, e);
      if (a > m) {
        vec3 nrm = normalize(vec3(u, sqrt(max(0., 1. - dot(u, u)))));
        float lam = clamp(dot(nrm, normalize(vec3(side*.55, -.65, .55))), 0., 1.);
        float leaf = fbm3(u*5. + c*3.);
        lit = clamp(lam*.85 + (leaf - .5)*.35 + .1, 0., 1.);
        m = a;
      }
      if (m < .5 && abs(x - cx) < r*.10 && y > gy - r*.3 && y < gy) { m = 1.; lit = .12; }
      vec2 sd = vec2((x - (cx - side*r*1.1))/(r*1.5), (y - gy)/(r*.32));
      if (m < .5 && length(sd) < 1.) sh = max(sh, 1. - length(sd));
    }
  }
  return vec3(m, lit, sh);
}

void main(){
  vec2 FC = gl_FragCoord.xy + uOff;                                   // sub-pixel jitter, averaged across frames
  float y = 1.0 - FC.y/uRes.y;
  vec2 P = vec2(FC.x/uRes.y, y);
  float aspect = uRes.x/uRes.y, x = P.x;
  vec2 S = vec2(uSun.x*aspect, uSun.y);
  float ds = length(P - S);
  vec3 rd = rayAt(FC);
  vec3 sd = rayAt(vec2(uSun.x*uRes.x, (1. - uSun.y)*uRes.y));
  float r0 = R0(x);

  // ---- sky and clouds (skipped where the far ridge already covers the pixel)
  vec3 col = vec3(0.);
  if (y <= r0 + .002) {
    vec3 sk = sky(rd, sd);
    if (uNight > .5) {
      vec2 g = floor(P*vec2(300.)); float h = hash(g);
      sk += toL(vec3(.85, .9, 1.))*step(.9968, h)*(.55 + .45*sin(uT*.8 + h*40.))*fall(.5, .08, y);
    }
    if (uShowSun > .5) {
      float disc = fall(uSunR, uSunR*.82, ds);
      if (uNight > .5) disc *= smoothstep(uSunR*.55, uSunR*.95, length(P - S - vec2(uSunR*.45, -uSunR*.35)));
      sk = mix(sk, toL(uSunC)*(uNight > .5 ? 1. : 2.2), disc);
    }
    if (rd.y > .004) {                                                          // high cirrus at 9 km
      vec2 cp = rd.xz*(9./rd.y) + vec2(uT*.02, 0.);
      float ci = textureLod(uWeather, cp*vec2(1./70., 1./14.), 0.).b;
      sk = mix(sk, mix(sk, toL(uCLit), .9), smoothstep(.55, .78, ci)*.35*smoothstep(.02, .10, rd.y)*(1. - uNight*.6));
    }
    vec3 skyH = sky(normalize(vec3(rd.x, .015, rd.z)), sd);
    vec4 cl = clouds(rd, sd, skyH, hash(FC*1.37 + 11. + uFrame*7.31));
    vec3 lin = sk*cl.a + cl.rgb;
    lin = mix(lin, vec3(1.) - (vec3(1.) - .8)*exp(-(lin - .8)/.2), step(.8, lin));   // soft highlight shoulder
    col = toS(lin);
    col = mix(col, vec3(lum(col)), uGrey);
  }

  // ---- cloud shadows on the ground, cast by the volume
  float gd = .5/max(y - .37, .004);
  vec3 gp = vec3((FC.x - .5*uRes.x)/uRes.y*gd/FOC, 0., gd);
  vec3 sp = gp + uKey*((BASE + 1.0)/max(uKey.y, .15)) + vec3(0., 0., 9.);   // sample the field beyond the open-sky zone
  float shadow = smoothstep(.0, .25, density(sp, false));

  // ---- land, far to near (painter's order)
  float e = .002;
  float r1 = R1(x), r2 = R2(x), r3 = R3(x), r4 = R4(x);
  bool land = false; vec3 L = col;
  if (y > r0) {
    float s0 = (R0(x + .012) - R0(x - .012))/.024;                           // broad slope only: fine ridge noise would streak
    float face = clamp(.5 + s0*(uSun.x > .5 ? -3. : 3.), 0., 1.)*fall(.06, 0., y - r0) + .5*smoothstep(0., .06, y - r0);   // sun-facing slopes lighter near the crest
    float gully = fbm(vec2(x*34., (y - r0)*46.) + fbm3(vec2(x*6., y*6.))*1.5);            // soft forest and rock mottling
    vec3 m0 = mix(uFog, uMid*.80, .38);
    m0 *= mix(.90, 1.10, face)*(.90 + .20*gully);
    m0 = mix(m0, m0*1.06 + .03, (1. - smoothstep(0., .015, y - r0))*.6);     // lit crest
    L = mix(m0, uFog, smoothstep(0., .07, y - r0)*.55); land = true; }
  if (y > r1) { L = shadeLayer(uH0, x, y, r1, (R1(x + e) - R1(x - e))/(2.*e), .32, 46., .08, aspect); land = true; }
  vec3 t1 = trees(x, y, 1, 4., .010, .55, .0085, .012);
  if (t1.x > .01) { L = mix(L, mix(mix(uTree*.8, uTree*1.5 + .05, t1.y), uFog, .40), t1.x); land = true; }
  if (y > r2) { L = shadeLayer(uH1, x, y, r2, (R2(x + e) - R2(x - e))/(2.*e), .20, 40., .10, aspect); L *= 1. - .10*shadow; land = true; }
  vec3 t2 = trees(x, y, 2, 9., .030, .42, .016, .05);
  if (y > r2 && t2.z > 0.) L *= 1. - .22*t2.z;
  if (t2.x > .01) { L = mix(L, mix(mix(uTree*.75, uTree*1.6 + .06, t2.y), uFog, .18), t2.x); land = true; }
  if (y > r3) { L = shadeLayer(uH2, x, y, r3, (R3(x + e) - R3(x - e))/(2.*e), .06, 34., .12, aspect); L *= 1. - .12*shadow; land = true;
    float px = aspect*(.30 + .10*sin(y*7.5 + 1.)), pw = .004 + max(0., y - .66)*.07;
    float pe = smoothstep(pw, pw*.7, abs(x - px));
    L = mix(L, uPath*(.94 + .1*noise(vec2(x*300., y*120.))), pe*smoothstep(r3 + .004, r3 + .03, y));
  }
  vec3 t3 = trees(x, y, 3, 17., .085, .45, .030, .07);
  if (y > r3 && t3.z > 0.) L *= 1. - .18*t3.z;
  if (t3.x > .01) { L = mix(L, mix(mix(uTree*.92, uTree*1.6 + .08, t3.y), uFog, .10), t3.x); land = true; }
  if (y > r4) {
    float t = y - r4;
    vec3 c = uH3*mix(1.04, .96, smoothstep(0., .14, t));
    float blade = noise(vec2(x*720., y*48.))*.6 + noise(vec2(x*1300., y*90.))*.4;
    c *= .90 + .20*blade;
    c *= 1. - .10*shadow;
    vec2 fc = floor(P*vec2(120., 120.)); float fh = hash(fc + 3.);
    if (fh < .06 && t > .02) { float fd = length(fract(P*vec2(120.)) - .5 - (hash(fc + 5.) - .5)*.5); if (fd < .16) c = mix(c, fh < .03 ? vec3(1., .98, .9) : vec3(1., .86, .45), smoothstep(.16, .08, fd)); }
    float px = aspect*(.30 + .10*sin(y*7.5 + 1.)), pw = .004 + max(0., y - .66)*.07;
    c = mix(c, uPath*(.94 + .1*noise(vec2(x*300., y*120.))), smoothstep(pw, pw*.7, abs(x - px + .004*noise(vec2(y*200., 3.)))));
    L = c; land = true;
  }
  if (land) {
    L = mix(L, vec3(lum(L)), uGrey*.6);
    L = mix(L, uFog, uHaze*fall(.70, .40, y));
    if (uNight > .5) L *= .9;
    col = L;
  }
  col += (hash(FC + fract(uT)) - .5)/255.*2.;
  o = vec4(col, 1.);
}`;

  const hex = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255);
  const norm = v => { const l = Math.hypot(...v); return v.map(c => c / l); };
  // Day sky sampled from the Samsung Weather reference. key = direction the clouds are lit from (x right, y up, z into the scene).
  const PAL = {
    dawn: { zen: '#6F93C8', mid: '#B9D3EC', hor: '#FAD8B8', sunC: '#FFE2BC', sun: [.20, .42], sunR: .026, cl: '#FFE9D6', cd: '#B9AEB4', fog: '#E8DED6', path: '#E6D6BC',
      h: ['#A9C4A2', '#9FC48E', '#A2C78C', '#A6CB8C'], tree: '#4F6E57', night: 0, I: 1.15, key: [-.75, .30, .35] },
    day: { zen: '#3E9BEA', mid: '#80C6F5', hor: '#D6EEFC', sunC: '#FFF6DE', sun: [.80, .12], sunR: .030, cl: '#FFFFFF', cd: '#9FB3C8', fog: '#D3E8F2', path: '#E4DCC2',
      h: ['#9CC79A', '#92C57C', '#97CA79', '#9DCF7B'], tree: '#55895C', night: 0, I: .86, key: [.45, .80, -.35] },
    dusk: { zen: '#24477C', mid: '#8FA8C8', hor: '#F7AD78', sunC: '#FFC48C', sun: [.80, .42], sunR: .028, cl: '#FFC9A0', cd: '#6E6A80', fog: '#C9A99C', path: '#C9A88A',
      h: ['#7F8F8C', '#6D8466', '#627C5A', '#5B7654'], tree: '#33473A', night: 0, I: 1.1, key: [.75, .22, .35] },
    night: { zen: '#040A18', mid: '#0E1E3C', hor: '#1E3557', sunC: '#E6ECF5', sun: [.78, .11], sunR: .022, cl: '#8DA2C4', cd: '#16223A', fog: '#1E3352', path: '#2E3E50',
      h: ['#1C3248', '#1A3042', '#172B3C', '#142636'], tree: '#0B1724', night: 1, I: .30, key: [.35, .85, -.25] },
  };
  // readiness drives the weather: fair-weather cumulus when ready, taller and greyer as readiness falls, low flat cover when poor
  const weather = r => r == null ? { cover: .52, tall: .60, grey: .05, haze: .05 } : r >= 80 ? { cover: .46, tall: .62, grey: 0, haze: 0 }
    : r >= 65 ? { cover: .62, tall: .80, grey: .12, haze: .08 } : { cover: .88, tall: .45, grey: .35, haze: .22 };

  function prog(gl, fs) {
    const sh = (t, s) => { const o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o); if (!gl.getShaderParameter(o, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(o)); return o; };
    const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, VERT)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, 'a'); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p)); return p;
  }
  function makeNoise(gl) {
    const p = prog(gl, GEN), fb = gl.createFramebuffer(); gl.useProgram(p);
    const hf = !!gl.getExtension('EXT_color_buffer_float');               // 16-bit float noise: 8-bit steps show up as walls once coverage is thresholded
    const IF = hf ? gl.RGBA16F : gl.RGBA8, TY = hf ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE;
    const U = n => gl.getUniformLocation(p, n);
    const setup = (target) => { gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T, gl.TEXTURE_WRAP_R].forEach(w => gl.texParameteri(target, w, gl.REPEAT)); };
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    const vol = (N, mode) => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_3D, t); gl.texImage3D(gl.TEXTURE_3D, 0, IF, N, N, N, 0, gl.RGBA, TY, null); setup(gl.TEXTURE_3D);
      gl.viewport(0, 0, N, N); gl.uniform1f(U('uN'), N); gl.uniform1i(U('uMode'), mode);
      for (let z = 0; z < N; z++) { gl.framebufferTextureLayer(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, t, 0, z); gl.uniform1f(U('uZ'), z); gl.drawArrays(gl.TRIANGLES, 0, 3); }
      return t; };
    const flat = (N) => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t); gl.texImage2D(gl.TEXTURE_2D, 0, IF, N, N, 0, gl.RGBA, TY, null); setup(gl.TEXTURE_2D);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
      gl.viewport(0, 0, N, N); gl.uniform1f(U('uN'), N); gl.uniform1i(U('uMode'), 2); gl.uniform1f(U('uZ'), 0); gl.drawArrays(gl.TRIANGLES, 0, 3); return t; };
    const shape = vol(128, 0), detail = vol(64, 1), weather = flat(512);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return { shape, detail, weather };
  }

  function mount(canvas, opts) {
    const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
    if (!gl) { canvas.replaceWith(Object.assign(document.createElement('div'), { textContent: 'WebGL2 unavailable' })); return { set() {} }; }
    canvas.__gl = gl;
    const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    const tex = makeNoise(gl), pr = prog(gl, FRAG); gl.useProgram(pr);
    const U = n => gl.getUniformLocation(pr, n);
    [[tex.shape, gl.TEXTURE_3D, 'uShape'], [tex.detail, gl.TEXTURE_3D, 'uDetail'], [tex.weather, gl.TEXTURE_2D, 'uWeather']].forEach(([t, k, n], i) => { gl.activeTexture(gl.TEXTURE0 + i); gl.bindTexture(k, t); gl.uniform1i(U(n), i); });
    let st = Object.assign({ time: 'day', readiness: 86, showSun: true, animate: true }, opts), t0 = performance.now(), last = 0, cost = 30;
    function size() { const dpr = Math.min(2, window.devicePixelRatio || 1); const w = Math.round(canvas.clientWidth * dpr), h = Math.round(canvas.clientHeight * dpr); if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; } gl.viewport(0, 0, w, h); }
    function draw(now) {
      const a = performance.now();
      size();
      const p = PAL[st.time], w = weather(st.readiness);
      gl.uniform2f(U('uRes'), canvas.width, canvas.height); gl.uniform1f(U('uT'), st.animate ? (now - t0) / 1000 + 40 : 40);
      gl.uniform1f(U('uCover'), w.cover); gl.uniform1f(U('uTall'), w.tall); gl.uniform1f(U('uGrey'), w.grey); gl.uniform1f(U('uHaze'), w.haze); gl.uniform1f(U('uNight'), p.night); gl.uniform1f(U('uSunI'), p.I);
      [['uZen', p.zen], ['uMid', p.mid], ['uHor', p.hor], ['uSunC', p.sunC], ['uCLit', p.cl], ['uCDark', p.cd], ['uFog', p.fog], ['uH0', p.h[0]], ['uH1', p.h[1]], ['uH2', p.h[2]], ['uH3', p.h[3]], ['uTree', p.tree], ['uPath', p.path]]
        .forEach(([n, c]) => gl.uniform3fv(U(n), hex(c)));
      gl.uniform3fv(U('uKey'), norm(p.key));
      const sp = st.sunAt || p.sun; gl.uniform2f(U('uSun'), sp[0], sp[1]); gl.uniform1f(U('uSunR'), p.sunR); gl.uniform1f(U('uShowSun'), st.showSun ? 1 : 0);
      gl.uniform1f(U("uStepK"), st.stepK || 1);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      cost = performance.now() - a;
    }
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches, live = st.animate && !reduce;
    // Progressive accumulation: each frame uses a new ray jitter and sub-pixel offset and is blended into a running average,
    // so 16 frames give a noise-free, anti-aliased image. While the sky drifts, frames blend at 25% (the motion is far below a pixel per frame).
    let k = 0, cw = 0, chh = 0;
    const next = f => { let done = false; const go = t => { if (!done) { done = true; f(t); } }; requestAnimationFrame(go); setTimeout(() => go(performance.now()), 120); };   // keep converging when frames are throttled
    function frame(now) {
      if (canvas.width !== cw || canvas.height !== chh) k = 0;
      const alpha = k === 0 ? 1 : Math.max(1 / (k + 1), live ? .25 : 0);
      if (k === 0) gl.disable(gl.BLEND); else { gl.enable(gl.BLEND); gl.blendColor(0, 0, 0, alpha); gl.blendFunc(gl.CONSTANT_ALPHA, gl.ONE_MINUS_CONSTANT_ALPHA); }
      gl.uniform2f(U('uOff'), k === 0 ? 0 : Math.random() - .5, k === 0 ? 0 : Math.random() - .5); gl.uniform1f(U('uFrame'), k % 97);
      draw(now); cw = canvas.width; chh = canvas.height; k++;
    }
    function loop(now) {
      if (k < 16) frame(live ? now : t0);
      else if (live && now - last > Math.max(120, cost * 3)) { frame(now); last = now; }
      if (k < 16 || live) next(loop);
    }
    frame(live ? performance.now() : t0); next(loop);
    return { set(o) { Object.assign(st, o); const run = k >= 16 && !live; k = 0; frame(live ? performance.now() : t0); if (run) next(loop); }, canvas, get cost() { return cost; }, get frames() { return k; } };
  }
  window.SceneGL34 = { mount, PAL };
})();
