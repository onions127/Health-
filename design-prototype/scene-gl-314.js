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
  const G = typeof window !== 'undefined' ? window : self;               // page or worker (the on-device baker runs in a worker, 3.13)
  const fail = (canvas, msg) => { if (canvas.replaceWith) canvas.replaceWith(Object.assign(document.createElement('div'), { textContent: msg })); else throw new Error(msg); };
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
uniform float uSheet, uSType, uSB, uST, uFogD, uFogH, uRain;
uniform float uCirrus, uCiHook, uCiVeil, uCiAng;
uniform float uStyle, uPaintCov;   // 0 = volumetric sky, 1 = painted sky (soft airbrushed layer, after the weather-app reference)   // cirrus cover, hooked heads (mares' tails), milky veil with halo, streak direction   // sheet cloud amount, type (0 stratocumulus .. 1 stratus), base/top km; fog density, fog height; rain
uniform vec3 uZen, uMid, uHor, uSunC, uCLit, uCDark, uFog, uH0, uH1, uH2, uH3, uTree, uPath, uKey;
uniform vec3 uHill, uWater, uPg0, uPg1, uPg2, uPg3; uniform float uSnow;   // 3.15: far hills, lake, the page meadow under the cards (top .. bottom), snow on the ground
uniform vec2 uSun; uniform float uSunR, uShowSun, uStepK, uFrame, uSlice, uSplit; uniform vec2 uOff, uMargin;   // depth slice to render (0 sky+far clouds+far ridge, 1 near clouds, 2 mid land, 3 near land), overscan margin (px)
uniform vec2 uSeed; uniform vec4 uHero1, uHero2;   // day seed offset (km); hero clouds: x, z, radius (km), height
layout(location = 0) out vec4 o;
layout(location = 1) out vec4 o2;   // land slices only: what the wind moves (r grass, g tree crowns), premultiplied like o; other slices have no second target

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

// ---------- camera: eye on the ground, horizon 37% down the screen (3.15, after the reference: lake strip, far hills, grassy lip, cards on the lip).
// FOC .64 = .37/.58 keeps the sky's vertical field of view from the 58% camera (3.7), so the clouds keep their tuning: same sky, fewer pixels
const float HOR = .37, FOC = .64;
vec3 rayAt(vec2 frag){ vec2 s = vec2(frag.x - .5*uRes.x, frag.y - (1. - HOR)*uRes.y)/uRes.y; return normalize(vec3(s, FOC)); }

// ---------- sky (linear light)
vec3 skyBase(vec3 rd){
  float e = rd.y;
  vec3 col = mix(toL(uHor), toL(uMid), 1. - exp(-max(e, 0.)*16.));
  return mix(col, toL(uZen), .78*smoothstep(.08, .55, e));                // the weather app's sky stays light even high up
}
vec3 sky(vec3 rd, vec3 sd){                                               // with the Mie halo: the light the clouds receive from the sky
  return skyBase(rd) + toL(uSunC)*(.0035*hgN(dot(rd, sd), .90) + .012*hgN(dot(rd, sd), .45))*(1. - uNight*.85)*uShowSun;
}

// ---------- cloud volume (km)
const float BASE = 1.5, THICK = 7.0, SIG = 55.;
vec3 wind(){ return vec3(uT*.010, 0., uT*.003); }
float cumulusD(vec3 p, bool full){
  vec3 q = p + wind();
  vec4 w = textureLod(uWeather, (q.xz + uSeed)/16., 0.);
  float wr = clamp((w.r - .51)*3.3 + .5, 0., 1.), wg = clamp((w.g - .51)*3.6 + .5, 0., 1.);   // stretch measured p10-p90 to 0..1
  float cov = smoothstep(.80 - uCover*.9, 1.30 - uCover*.9, wr)*.85;
  cov *= 1. - .75*smoothstep(16., 40., p.z);                              // thin the far field: a clean horizon
  // hero clouds: one or two large cumulus placed per day, so the sky has a subject
  vec2 h1 = (q.xz - uHero1.xy)/uHero1.z, h2 = (q.xz - uHero2.xy)/uHero2.z;
  float hero1 = exp(-dot(h1, h1)*1.6), hero2 = exp(-dot(h2, h2)*1.6)*step(.01, uHero2.z);
  float hero = max(hero1, hero2)*smoothstep(.05, .30, uCover);          // no cumulus sky, no hero cumulus
  cov = max(cov, hero);
  wg = mix(wg, max(uHero1.w*step(hero2, hero1), uHero2.w*step(hero1, hero2)), smoothstep(.2, .7, hero));
  cov *= smoothstep(4., 10., p.z);                                        // open sky overhead, clouds start a few km out
  if (cov <= 0.) return 0.;
  float hf = (p.y - BASE)/THICK;
  vec4 n = textureLod(uShape, q/6. + vec3(0., -uT*.0005, 0.), 0.);       // shapes rise slowly: the clouds boil
  float pw = clamp((n.r - .62)/.26, 0., 1.);                              // Perlin-Worley, stretched from measured range
  float wf = clamp(((n.g*.40 + n.b*.35 + n.a*.25) - .30)/.36, 0., 1.);    // Worley billows, more weight on the smaller octaves
  float top = mix(.12, 1., pow(wg, 1.7))*uTall*pow(cov, .75)*mix(.70, 1.25, pw);   // .75, not sqrt: thin cloud rims stay low, no stalks; not linear: small clouds turned into flat lenses (3.14)   // domes with uneven crowns, never a flat plateau
  float lift = (1. - cov)*.035;                                         // the base curls up at the cloud's edges: no flat skirts
  float hg = smoothstep(lift, lift + .012, hf)*sqrt(clamp(1. - hf/max(top, .02), 0., 1.))*smoothstep(.05, .11, top);
  if (hg <= 0.) return 0.;
  float b = pw*.50 + wf*.50;
  b = remap(b*hg, 1. - cov*.85, 1., 0., 1.)*cov;
  if (b <= 0.) return 0.;
  if (full) {
    vec3 dq = q + vec3(sin(uT*.03)*.04, -uT*.0016, cos(uT*.02)*.04);
    // billows from 800 m down to ~140 m (3.14: the detail octaves were 325-1300 m, the same scale as the shapes, so lobes read as smooth balloons)
    vec3 dn = textureLod(uDetail, dq/1.6, 0.).rgb;
    float fine = textureLod(uDetail, dq/.55, 0.).g;                       // small billows on near clouds
    float far = smoothstep(9., 30., p.z);                                  // drop the finest octave with distance: it aliases at grazing angles
    float hfb = mix(dn.r*.4 + dn.g*.22 + dn.b*.1 + fine*.28, dn.r, far);
    float up = clamp(hf/max(top, .05)*4., 0., 1.);
    float m = mix(hfb, 1. - hfb, up);                                      // erode between Worley cells above: round cauliflower lobes
    b = remap(b, m*mix(.60, .22, far)*mix(.5, 1., smoothstep(0., .03, hf)), 1., 0., 1.);   // lighter erosion at the base keeps it level
  }
  return clamp(b*1.6, 0., 1.);
}
// low sheet clouds: stratocumulus (lumpy cells with gaps of blue) blending to stratus (an unbroken grey sheet)
float sheetD(vec3 p, bool full){
  if (uSheet <= 0. || p.y < uSB - .2 || p.y > uST + .2) return 0.;
  vec3 q = p + wind()*.6;
  vec4 n = textureLod(uShape, q*vec3(1./4.5, 1./1.6, 1./4.5), 0.);     // cells flattened into a layer
  float pw = clamp((n.r - .62)/.26, 0., 1.), wf = clamp(((n.g*.40 + n.b*.35 + n.a*.25) - .30)/.36, 0., 1.);
  float und = (pw - .5)*.22;                                            // base and top undulate
  float hf = (p.y - uSB - und)/(uST - uSB);
  float prof = smoothstep(0., .2, hf)*(1. - smoothstep(.5, 1., hf));
  if (prof <= 0.) return 0.;
  float cells = remap((wf*.65 + pw*.35)*prof, 1. - uSheet*.9, 1., 0., 1.);
  float sheet = (.45 + .55*pw)*prof*uSheet;
  float b = mix(cells, sheet, smoothstep(.45, 1., uSType));
  if (full && b > 0.) { float dn = textureLod(uDetail, q/1.6, 0.).r; b = remap(b, (1. - dn)*.30*(1. - uSType*.7), 1., 0., 1.); }
  return clamp(b*1.25, 0., 1.);
}
float density(vec3 p, bool full){ float c = cumulusD(p, full); return uSheet > 0. ? max(c, sheetD(p, full)) : c; }
float lightOD(vec3 p, vec3 L, float j){ float od = 0., s = .06 + .08*j; for (int j = 0; j < 6; j++) { p += L*s; od += density(p, false)*s; s *= 1.75; } return od*SIG; }

// returns linear radiance in rgb, transmittance in a
vec4 clouds(vec3 rd, vec3 sd, vec3 skyH, float jit, float tLo, float tHi){
  if (rd.y < .004) return vec4(0., 0., 0., 1.);
  float lo = uSheet > 0. ? min(BASE, uSB - .2) : BASE;
  float t0 = max(lo/rd.y, tLo), t1 = min(min((BASE + THICK)/rd.y, 70.), tHi);
  if (t0 >= t1) return vec4(0., 0., 0., 1.);
  float c = dot(rd, sd);
  float ph = min(mix(hgN(c, .50), hgN(c, -.20), .40), 2.4);   // capped so the side facing the sun glows without blowing out
  vec3 sunL = toL(uCLit)*uSunI, ambHi = mix(toL(uMid), vec3(1.), .30)*.50, ambLo = mix(toL(uCDark), toL(uMid), .20)*.52;
  // the sun is at infinity: one direction for every cloud (the palette's key, which follows the sun's side). A point light placed over
  // the field (3.5) lit each lobe from its own angle and gave every billow a round highlight, like bubble wrap (3.14)
  vec3 Ld = normalize(uKey);
  float T = 1.; vec3 S = vec3(0.);
  float t = t0 + jit*max(.035, t0*.008);
  for (int i = 0; i < 200; i++) {
    if (t > t1 || T < .015) break;
    float dt = max(.035, t*.008)*uStepK*(.85 + .3*fract(uFrame*.618));   // step length varies per frame, so no fixed slicing pattern survives the average
    vec3 p = rd*t;
    float d = density(p, false);
    if (d > 0.) {
      dt *= .55;                                                         // inside cloud: finer steps (the 24-frame average left sampling grain in thin bases, 3.14)
      d = density(p, true);
      if (d > 0.) {
        float od = lightOD(p, Ld, fract(jit + float(i)*.618));
        od += density(p + Ld*.045, true)*.045*SIG;                       // a short look toward the sun with full detail: each small billow gets a lit and a shaded side (3.14)
        float e = 0., a = 1., k = 1.;
        for (int m = 0; m < 3; m++) { e += a*exp(-od*k)*mix(ph, 1., 1. - a); a *= .42; k *= .38; }   // multiple-scattering octaves
        e += .22*exp(-od*.035);                                          // deep diffusion: light still seeps through a thick sheet
        float powder = 1. - exp(-d*SIG*.5);
        float hf = p.y < BASE ? clamp((p.y - uSB)/max(uST - uSB, .1), 0., 1.)*.6 : clamp((p.y - BASE)/(THICK*uTall), 0., 1.);
        float ao = exp(-density(p + vec3(0., .28, 0.), false)*1.6);          // sky light is blocked in the creases between billows
        float shw = p.y < BASE ? 1. : 0.;                                // sheet clouds: an overcast underside is a bright, even grey
        vec3 L = sunL*e*mix(.25, 1., powder) + mix(ambLo, ambHi, smoothstep(0., .6, hf))*mix(.55, 1., ao)*(1. + .45*shw) + sunL*.10*shw;
        L += sunL*exp(-od*.35)*(1. - powder)*min(hgN(c, .86), 7.)*.20;     // silver lining: thin edges near the sun light up
        L = mix(L, skyH, 1. - exp(-t/26.));                              // aerial perspective
        float st = exp(-d*SIG*dt);
        S += T*L*(1. - st); T *= st;
      }
    } else dt *= 1.5;
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

float ovc(){ return clamp(uSheet*mix(.6, 1., uSType), 0., 1.); }   // how overcast: flattens sun light on the land
vec3 shadeLayer(vec3 base, float x, float y, float r, float sl, float fog, float tex, float depth, float aspect){
  float t = y - r;
  vec2 n = normalize(vec2(sl, -1.));
  vec2 L = normalize(vec2(uSun.x*aspect - x, uSun.y - y - .2));
  float lam = clamp(dot(n, L), 0., 1.);
  float crest = 1. - smoothstep(0., .05, t);
  vec3 c = base*mix(.88, 1.10, mix(.55, lam, crest*(1. - ovc())));
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
        lit = mix(clamp(lam*.85 + (leaf - .5)*.35 + .1, 0., 1.), .40 + (leaf - .5)*.25, ovc());
        m = a;
      }
      if (m < .5 && abs(x - cx) < r*.10 && y > gy - r*.3 && y < gy) { m = 1.; lit = .12; }
      vec2 sd = vec2((x - (cx - side*r*1.1))/(r*1.5), (y - gy)/(r*.32));
      if (m < .5 && length(sd) < 1.) sh = max(sh, 1. - length(sd));
    }
  }
  return vec3(m, lit, sh);
}

// ---------- cirrus: ice cloud on a plane at 9.5 km, drawn as long fibres that follow a bending flow
vec2 ciQ(vec2 p){                                                          // plane point (km) -> wind-aligned, bent coordinates
  // the flow bends across the sky. Only the smooth channel (g: 4 octaves): the 6-octave channels' fine octaves made every fibre zigzag in step (3.14)
  vec2 lw = vec2(textureLod(uWeather, p/220., 0.).g, textureLod(uWeather, p/220. + .5, 0.).g) - .51;
  return mat2(cos(uCiAng), -sin(uCiAng), sin(uCiAng), cos(uCiAng))*p + lw*vec2(18., 5.);   // one wind direction; a rotation that varies in space smears into marbling
}
float ciBand(vec2 q){ return clamp((textureLod(uWeather, q*vec2(1./150., 1./42.) + .31, 0.).r - .51)*3.3 + .5, 0., 1.); }   // where the cirrus lies
vec4 cirrus(vec3 rd, vec3 sd){
  if (uCirrus <= 0. || rd.y < .006) return vec4(0.);
  float H = 9.5, dist = H/rd.y;
  vec2 drift = uSeed*.7 + vec2(uT*.025, uT*.008);
  vec2 q = ciQ(rd.xz*dist + drift);
  vec3 rc = normalize(vec3(0., .28, 1.));                                  // mid-sky reference point
  float lift = max(0., .62 - ciBand(ciQ(rc.xz*(H/rc.y) + drift)));         // cirrus is the main cloud: never let a day's seed leave the middle of the sky empty
  float band = clamp(ciBand(q) + lift*smoothstep(.0, .5, ciBand(q)), 0., 1.);
  float mask = smoothstep(1. - uCirrus, 1.25 - uCirrus*.55, band);
  if (mask <= 0.) return vec4(0.);
  q.y += uCiHook*(textureLod(uWeather, q/60. + .2, 0.).g - .51)*18.;    // fibres curl and hook: mares' tails (smooth channel: the b channel's fine octaves zigzagged every fibre)
  vec2 wq = q + (vec2(textureLod(uWeather, q/40., 0.).g, textureLod(uWeather, q/40. + .5, 0.).g) - .51)*vec2(6., 1.2);   // gentle warp, smooth channel only
  float f1 = textureLod(uWeather, wq*vec2(1./70., 1./6.), 0.).b;
  float f2 = textureLod(uWeather, wq*vec2(1./30., 1./2.4) + .7, 0.).r;
  float f3 = textureLod(uWeather, wq*vec2(1./14., 1./1.), 0.).g;
  float f4 = textureLod(uWeather, wq*vec2(1./7., 1./.45) + .37, 0.).b;
  float fib = clamp(((f1*.42 + f2*.26 + f3*.18 + f4*.14) - .51)*3.0 + .5, 0., 1.);   // broad wisps combed into fine fibres (3.14: mostly broad read as smeared paint)
  float stroke = clamp((textureLod(uWeather, q*vec2(1./40., 1./9.) + .55, 0.).g - .51)*3. + .5, 0., 1.);   // each wisp fades in and out along its length
  float d = mask*pow(fib, 2.0)*smoothstep(.15, .75, stroke)*1.7;          // soft falloff, no hard threshold: no contour lines
  d = mix(d, mask*(.42 + .35*fib), uCiVeil);                                 // veil: a milky sheet with fibres in it
  d *= exp(-dist/95.)*smoothstep(.006, .05, rd.y);                         // far cirrus thins into the haze
  float c = dot(rd, sd);
  vec3 L = toL(uCLit)*uSunI*(.72 + .30*min(hgN(c, .75), 2.))*(1. - .16*d*d) + toL(uMid)*.18;   // ice scatters forward: bright near the sun
  float a = acos(clamp(c, -1., 1.));
  float halo = exp(-pow((a - .384)/.011, 2.)) + .35*exp(-pow((a - .40)/.02, 2.));   // 22 degree halo in the veil
  L += toL(uSunC)*vec3(1.1, 1., .9)*halo*uCiVeil*.9*(1. - uNight);
  float al = clamp(d*.70, 0., .85);                                         // cirrus is thin ice: the blue always shows through a little
  return vec4(L*al, al);
}
// ---------- painted sky: a flat, soft cloud layer seen almost straight up, airbrushed, with a fine watercolour grain
vec4 painted(vec2 P, float y, vec2 FC){
  vec3 sky = mix(vec3(.47, .74, .95), vec3(.33, .63, .90), smoothstep(.0, .62, y));      // saturated blue in the gaps
  sky = mix(sky, vec3(.78, .90, .98), .55*exp(-length(P - vec2(.02, -.02))*2.4));      // the light comes from the top left
  vec2 q = P*vec2(1., 1.2)*.95 + vec2(uT*.0015, uT*.0004) + uSeed*.004;
  vec4 a = textureLod(uShape, vec3(q*.55, .37), 0.);                                     // billowy Perlin-Worley masses
  float pwv = clamp((a.r - .62)/.26, 0., 1.), wfv = clamp(((a.g*.5 + a.b*.3 + a.a*.2) - .30)/.36, 0., 1.);
  float det = textureLod(uDetail, vec3(q*1.7, .61), 0.).r;                               // small puffs
  float n = pwv*.55 + wfv*.45 - (1. - det)*.18;
  float cov = .62 - uPaintCov*.50;
  float m = smoothstep(cov - .02, cov + .26, n);                                       // soft, airbrushed edges
  vec2 lo = q + vec2(-.035, .045);                                                      // toward the light, top left
  vec4 a2 = textureLod(uShape, vec3(lo*.55, .37), 0.);
  float n2 = clamp((a2.r - .62)/.26, 0., 1.)*.55 + clamp(((a2.g*.5 + a2.b*.3 + a2.a*.2) - .30)/.36, 0., 1.)*.45;
  float lit = clamp((n2 - (pwv*.55 + wfv*.45))*-5. + .6, 0., 1.);                        // thinner toward the light: brighter
  float core = smoothstep(cov + .06, cov + .34, n);
  vec3 cw = mix(vec3(.79, .87, .97), vec3(1.), clamp(lit*.5 + core*.4 + (det - .5)*.5, 0., 1.));   // pale-blue shade, white light, cotton puffs
  float grain = noise(FC*.55)*.6 + noise(FC*1.7)*.4;                                     // watercolour granulation
  cw *= .965 + .06*grain;
  vec3 holes = mix(sky, vec3(.86, .93, .99), smoothstep(cov - .16, cov + .02, n)*.35);    // a light, misty halo around each cloud
  return vec4(mix(holes, cw, m*.95), m);
}
float rainAt(vec2 P, float s, float sp, float seed){
  vec2 uv = vec2((P.x + P.y*.10)*s, P.y*s*.06 - uT*sp);                // slight slant; cells tall and thin
  vec2 id = floor(uv), f = fract(uv);
  if (hash(id + seed) > .42) return 0.;
  float xo = .15 + .7*hash(id + seed + 7.), yo = .5*hash(id + seed + 13.);
  float line = 1. - smoothstep(0., s/uRes.y*1.3, abs(f.x - xo));       // about one pixel wide
  return line*smoothstep(yo, yo + .12, f.y)*(1. - smoothstep(yo + .30, yo + .48, f.y));
}
// sRGB painter's compositing with coverage, so a land slice keeps soft, anti-aliased edges over the slices behind it
void over(inout vec3 C, inout float A, vec3 c, float a){ C = c*a + C*(1. - a); A = a + A*(1. - a); }
void overM(inout vec2 M, vec2 m, float a){ M = m*a + M*(1. - a); }   // the wind material, composited the same way
vec3 landFinish(vec3 L, float x, float y, float aspect, float r0, float r1, float r2, float r3, float r4){
  if (uFogD > 0. || uRain > 0.) {
    // valley fog pools in the hollow of each land layer, just above the next ridge, and drifts in banks; far layers sit deeper in it
    float lay = y < r1 ? 0. : y < r2 ? 1. : y < r3 ? 2. : y < r4 ? 3. : 4.;
    float crest = lay < .5 ? r0 : lay < 1.5 ? r1 : lay < 2.5 ? r2 : lay < 3.5 ? r3 : r4;
    float nxt = lay < .5 ? r1 : lay < 1.5 ? r2 : lay < 2.5 ? r3 : lay < 3.5 ? r4 : 1.15;
    float hollow = smoothstep(crest, mix(crest, nxt, .85), y);
    float bank = fbm(vec2(x*3.2 + uT*.008, y*13.));
    float dist = 1. - lay/4.;
    float f = uFogD*clamp(hollow*(.45 + .55*dist) + uFogH*(.25 + .75*dist), 0., 1.)*mix(.65, 1.2, bank);
    f = max(f, uRain*.55*dist);                                                      // rain hides the distance
    vec3 fc = mix(uFog, vec3(.97, .98, .99), .45*(1. - uNight)) + vec3(1., .95, .85)*exp(-abs(x - uSun.x*aspect)*3.5)*.10*(1. - uNight)*uShowSun;   // fog glows toward the sun
    L = mix(L, fc, clamp(f, 0., .93));
  }
  L = mix(L, vec3(lum(L)), uGrey*.6);
  L = mix(L, uFog, uHaze*fall(.70, .40, y));
  return L*(1. - .1*smoothstep(.3, .9, uNight));
}
void main(){
  vec2 FC = gl_FragCoord.xy + uOff - uMargin;                         // screen pixel; slices extend past the screen by the margin so they can slide
  float y = 1.0 - FC.y/uRes.y;
  vec2 P = vec2(FC.x/uRes.y, y);
  float aspect = uRes.x/uRes.y, x = P.x;
  vec2 S = vec2(uSun.x*aspect, uSun.y);
  float ds = length(P - S);
  vec3 rd = rayAt(FC);
  vec3 sd = rayAt(vec2(uSun.x*uRes.x, (1. - uSun.y)*uRes.y));
  float r0 = R0(x);
  float yl = .40 + (y - HOR)*(.60/(1. - HOR));                         // land is drawn in its own frame, pushed down below the lower horizon
  float jit = hash(FC*1.37 + 11. + uFrame*7.31);
  vec3 fogVeil = mix(uFog, vec3(1.), .35*(1. - uNight));
  float veil = clamp(uFogD*.9 + uRain*.5, 0., 1.)*smoothstep(.22, .40, yl)*.85;     // fog and rain veil the horizon

  if (uSlice < .5) {
    // ---- slice 0: sky, sun, stars, cirrus, far clouds, far ridge (opaque; alpha carries the open-sky mask for light shafts)
    vec3 col = vec3(0.);
    float skyMask = 0.;
    if (yl <= r0 + .002) {
      vec3 sk = skyBase(rd);                                             // halo and stars are drawn live in COMPOSE (3.12): they switch with the hour, which a blend of baked hours cannot follow
      // the sun and moon discs are drawn live in COMPOSE (3.12), so a baked sky can follow the hour; the halo stays here
      vec4 ci = cirrus(rd, sd);
      sk = sk*(1. - ci.a) + ci.rgb;
      vec3 skyH = sky(normalize(vec3(rd.x, .015, rd.z)), sd);
      vec4 cl = clouds(rd, sd, skyH, jit, uSplit, 70.);
      vec3 lin = sk*cl.a + cl.rgb;
      lin = mix(lin, vec3(1.) - (vec3(1.) - .8)*exp(-(lin - .8)/.2), step(.8, lin));   // soft highlight shoulder
      col = toS(lin);
      skyMask = cl.a*(1. - ci.a*.6);
      if (uStyle > .5) { vec4 pt = painted(P, y, FC); float hz = smoothstep(.36, .40, yl); col = mix(pt.rgb, col, hz); skyMask = mix(1. - pt.a, skyMask, hz); }   // painted sky; the real haze takes over just above the horizon
      col = mix(col, vec3(lum(col)), uGrey);
      col = mix(col, fogVeil, veil);
    }
    y = yl;
    if (y > r0) {
      float s0 = (R0(x + .012) - R0(x - .012))/.024;                           // broad slope only: fine ridge noise would streak
      float face = clamp(.5 + s0*(uSun.x > .5 ? -3. : 3.), 0., 1.)*fall(.06, 0., y - r0) + .5*smoothstep(0., .06, y - r0);   // sun-facing slopes lighter near the crest
      float gully = fbm(vec2(x*34., (y - r0)*46.) + fbm3(vec2(x*6., y*6.))*1.5);            // soft forest and rock mottling
      vec3 m0 = mix(uFog, uMid*.80, .38);
      m0 *= mix(.90, 1.10, face)*(.90 + .20*gully);
      m0 = mix(m0, m0*1.06 + .03, (1. - smoothstep(0., .015, y - r0))*.6);     // lit crest
      m0 = mix(m0, uFog, smoothstep(0., .07, y - r0)*.55);
      col = landFinish(m0, x, y, aspect, r0, R1(x), R2(x), R3(x), R4(x)); skyMask = 0.;
    }
    o = vec4(toL(max(col, 0.)), skyMask);
    return;
  }

  if (uSlice < 1.5) {
    // ---- slice 1: near clouds, premultiplied (they may sit in front of the far ridge)
    if (rd.y <= .004 || uStyle > .5) { o = vec4(0.); return; }
    vec3 skyH = sky(normalize(vec3(rd.x, .015, rd.z)), sd);
    vec4 cl = clouds(rd, sd, skyH, jit, 0., uSplit);
    float a = 1. - cl.a;
    vec3 S1 = cl.rgb;
    S1 = mix(S1, vec3(dot(S1, vec3(.2126, .7152, .0722))), uGrey);
    S1 = S1*(1. - veil) + toL(fogVeil)*veil*a;
    o = vec4(S1, a);
    return;
  }

  // ---- slices 2 (mid land) and 3 (near land), premultiplied
  y = yl; P.y = yl;
  float gd = .5/max(y - .37, .004);                                     // cloud shadows on the ground, cast by the volume
  vec3 gp = vec3((FC.x - .5*uRes.x)/uRes.y*gd/FOC, 0., gd);
  vec3 sp = gp + uKey*((BASE + 1.0)/max(uKey.y, .15)) + vec3(0., 0., 9.);   // sample the field beyond the open-sky zone
  float shadow = smoothstep(.0, .25, density(sp, false));
  float e = .002;
  float r1 = R1(x), r2 = R2(x), r3 = R3(x), r4 = R4(x);
  vec3 C = vec3(0.); float A = 0.; vec2 M = vec2(0.);
  if (uSlice < 2.5) {
    if (y > r1) { over(C, A, shadeLayer(uH0, x, y, r1, (R1(x + e) - R1(x - e))/(2.*e), .32, 46., .08, aspect), 1.); overM(M, vec2(1., 0.), 1.); }
    vec3 t1 = trees(x, y, 1, 4., .010, .55, .0085, .012);
    if (t1.x > .01) { over(C, A, mix(mix(uTree*.8, uTree*1.5 + .05, t1.y), uFog, .40), t1.x); overM(M, vec2(0., 1.), t1.x); }
    if (y > r2) { over(C, A, shadeLayer(uH1, x, y, r2, (R2(x + e) - R2(x - e))/(2.*e), .20, 40., .10, aspect)*(1. - .07*shadow), 1.); overM(M, vec2(1., 0.), 1.); }
    vec3 t2 = trees(x, y, 2, 9., .030, .42, .016, .05);
    if (y > r2 && t2.z > 0.) C *= 1. - .22*t2.z*(1. - ovc());
    if (t2.x > .01) { over(C, A, mix(mix(uTree*.75, uTree*1.6 + .06, t2.y), uFog, .18), t2.x); overM(M, vec2(0., 1.), t2.x); }
  } else {
    if (y > r3) {
      vec3 L = shadeLayer(uH2, x, y, r3, (R3(x + e) - R3(x - e))/(2.*e), .06, 34., .12, aspect)*(1. - .05*shadow);
      float px = aspect*(.30 + .10*sin(y*7.5 + 1.)), pw = .004 + max(0., y - .66)*.07;
      float pe = smoothstep(pw, pw*.7, abs(x - px));
      L = mix(L, uPath*(.94 + .1*noise(vec2(x*300., y*120.))), pe*smoothstep(r3 + .004, r3 + .03, y));
      over(C, A, L, 1.); overM(M, vec2(1. - pe*smoothstep(r3 + .004, r3 + .03, y), 0.), 1.);   // the path does not wave
    }
    vec3 t3 = trees(x, y, 3, 17., .085, .45, .030, .07);
    if (y > r3 && t3.z > 0.) C *= 1. - .18*t3.z*(1. - ovc());
    if (t3.x > .01) { over(C, A, mix(mix(uTree*.92, uTree*1.6 + .08, t3.y), uFog, .10), t3.x); overM(M, vec2(0., 1.), t3.x); }
    if (y > r4) {
      float t = y - r4;
      vec3 c = uH3*mix(1.04, .96, smoothstep(0., .14, t));
      float blade = noise(vec2(x*720., y*48.))*.6 + noise(vec2(x*1300., y*90.))*.4;
      c *= .90 + .20*blade;
      c *= 1. - .045*shadow;
      vec2 fc = floor(P*vec2(120., 120.)); float fh = hash(fc + 3.);
      if (fh < .06 && t > .02) { float fd = length(fract(P*vec2(120.)) - .5 - (hash(fc + 5.) - .5)*.5); if (fd < .16) c = mix(c, fh < .03 ? vec3(1., .98, .9) : vec3(1., .86, .45), smoothstep(.16, .08, fd)); }
      float px = aspect*(.30 + .10*sin(y*7.5 + 1.)), pw = .004 + max(0., y - .66)*.07;
      float pm = smoothstep(pw, pw*.7, abs(x - px + .004*noise(vec2(y*200., 3.))));
      c = mix(c, uPath*(.94 + .1*noise(vec2(x*300., y*120.))), pm);
      over(C, A, c, 1.); overM(M, vec2(1. - pm, 0.), 1.);
    }
  }
  if (A < .001) { o = vec4(0.); o2 = vec4(0.); return; }
  vec3 L = landFinish(C/A, x, y, aspect, r0, r1, r2, r3, r4);
  o = vec4(toL(max(L, 0.))*A, A);
  o2 = vec4(M*(1. - clamp(uFogD*.8 + uRain*.3, 0., .9)*smoothstep(.55, .40, y)), 0., A);   // fog-veiled distance shows no wind
}`;

  // ---------- post: bloom (soft-threshold bright pass, two blurred levels), light shafts (radial march toward the sun over
  // the open-sky mask), then the colour grade for the hour, tone shoulder, sRGB and dither
  const BRIGHT = `#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst, uSrcTexel; uniform float uTh; out vec4 o;
void main(){ vec2 uv = gl_FragCoord.xy/uDst, d = uSrcTexel;
  vec3 c = (texture(uSrc, uv + d*vec2(-1., -1.)).rgb + texture(uSrc, uv + d*vec2(1., -1.)).rgb + texture(uSrc, uv + d*vec2(-1., 1.)).rgb + texture(uSrc, uv + d*vec2(1., 1.)).rgb)*.25;
  float l = dot(c, vec3(.2126, .7152, .0722));
  o = vec4(c*smoothstep(uTh, uTh + .35, l), 1.); }`;
  const BLUR = `#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst, uDir; out vec4 o;
void main(){ vec2 uv = gl_FragCoord.xy/uDst;                     // 9-tap Gaussian folded into 5 linear taps
  vec3 c = texture(uSrc, uv).rgb*.227027 + (texture(uSrc, uv + uDir*1.3846).rgb + texture(uSrc, uv - uDir*1.3846).rgb)*.316216
         + (texture(uSrc, uv + uDir*3.2308).rgb + texture(uSrc, uv - uDir*3.2308).rgb)*.070270;
  o = vec4(c, 1.); }`;
  const COMPOSE = `#version 300 es
precision highp float;
uniform sampler2D uS0, uS1, uS2, uS3, uM2, uM3; uniform vec2 uRes, uMargin, uSliceRes, uSliceRes1; uniform vec2 uO0, uO1, uO2, uO3;
uniform vec4 uXf;                                                       // canvas px -> frame px (scale, offset): a baked frame covers any screen aspect
uniform vec2 uSun; uniform float uSunR, uShowSun, uVeil, uStars; uniform vec3 uSunDisc, uHalo;
uniform float uT, uRain, uNight, uWind, uBirds, uDpr, uWindDbg; uniform vec3 uRainCol, uBirdCol;
out vec4 o;
float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x*p.y); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); vec2 u = f*f*(3. - 2.*f);
  return mix(mix(hash(i), hash(i + vec2(1., 0.)), u.x), mix(hash(i + vec2(0., 1.)), hash(i + vec2(1., 1.)), u.x), u.y); }
float noiseAA(vec2 p, vec2 fw){ float f = max(fw.x, fw.y); return f > .45 ? .5 : mix(noise(p), .5, smoothstep(.15, .45, f)); }   // fw: cells per px; fades to its mean once finer than ~3 px, so the receding ground does not shimmer
// ---- wind. Gusts roll across the meadow the way the low clouds drift (to the left and toward the eye). Grass bending in a gust
// shows its paler side, so a gust reads as a lighter patch travelling over the field, with fine ripples running through it;
// tree crowns silver as their leaves turn. Runs per frame here, on the cached slices: the scene itself is not re-rendered.
const float HOR = .58, FOC = 1.;
float hgN(float c, float g){ float g2 = g*g; return (1. - g2)/pow(1. + g2 - 2.*g*c, 1.5); }   // 4pi * Henyey-Greenstein
const vec2 WD = vec2(-.958, -.287);                                     // ground wind direction (x right, z away), as the clouds' wind()
vec2 ground(vec2 q){                                                    // screen px -> ground point, the land slices' projection
  float yl = .40 + ((1. - q.y/uRes.y) - HOR)*(.60/(1. - HOR)), gd = .5/max(yl - .37, .004);
  return vec2((q.x - .5*uRes.x)/uRes.y*gd/FOC, gd);
}
vec3 windAt(vec2 q){                                                    // x gust 0..1, y ripple -1..1, z leaf flicker 0..1
  vec2 G = ground(q), a = vec2(dot(G, WD), dot(G, vec2(-WD.y, WD.x)));   // along and across the wind
  // ground units per screen px (analytic: derivatives are undefined in this branchy code): across dx, and down the screen dz
  float dx = G.y/uRes.y/FOC, dz = 2.*G.y*G.y*(.60/(1. - HOR))/uRes.y, gx = G.x/G.y;
  vec2 fa = vec2(max(abs(WD.x)*dx, abs(WD.x*gx + WD.y)*dz), max(abs(WD.y)*dx, abs(WD.x - WD.y*gx)*dz));   // per px, along and across
  float sp = .05 + .07*uWind, t = uT;                                   // ground units per second: a gust crosses the near field in ~5 s
  float n = noiseAA(vec2((a.x - t*sp)*4.5, a.y*1.6), fa*vec2(4.5, 1.6))*.65
          + noiseAA(vec2((a.x - t*sp*1.3)*9.5, a.y*3.4 + t*.04), fa*vec2(9.5, 3.4))*.35;   // patches long across the wind, reshaping as they travel
  float g = smoothstep(.50 - .10*uWind, .78 - .06*uWind, n);
  float r = noiseAA(vec2((a.x - t*sp*1.8)*60., a.y*18.), fa*vec2(60., 18.))*2. - 1.;
  float f = noise(q/(2.6*uDpr) + vec2(t*1.7, -t*1.1));                 // leaf clusters, a few px across
  return vec3(g*(.5 + .6*uWind)*(1. - .75*uNight), r, f);               // even a light breeze shows its gusts
}
vec4 windLand(vec4 s, vec4 m, vec2 q){                                  // s premultiplied colour, m premultiplied material
  if (s.a < .002 || m.r + m.g < .01 || uWind <= 0.) return s;
  vec3 w = windAt(q), c = s.rgb/s.a;
  float gr = m.r/s.a, cr = m.g/s.a;
  float k = gr*(w.x + .12*uWind);                                       // a light breeze keeps the field faintly alive between gusts
  vec3 sheen = c*1.30 + vec3(.012, .016, .020)*(1. - uNight);           // the paler blade side, with a little sky in it
  c = mix(c, sheen, clamp(k, 0., 1.)) * (1. + .08*w.y*k);               // lighter patch, ripples inside it
  c = mix(c, c*1.28 + vec3(.008, .012, .008)*(1. - uNight), cr*clamp(w.x*1.3 + .1*uWind, 0., 1.)*(.25 + .75*w.z));   // leaves turn: crowns silver
  if (uWindDbg > 0.) c = mix(c, vec3(1., 0., .6), clamp(k*.8, 0., .8));   // review only: gust strength in magenta
  return vec4(c*s.a, s.a);
}
// ---- a few birds cross the sky now and then (day only): a loose group, flapping a few beats, then gliding
float bird(vec2 p, float span, float up){                               // p: px from the bird, span: wingspan px, up: wing pose -1 (down) .. 1 (up)
  vec2 u = p/span; float x = abs(u.x);
  if (x > .55 || abs(u.y) > .5) return 0.;
  float yw = (.06 + .30*up)*x*2. + .16*(1. - up*.5)*x*2.*(1. - x*2.);  // shoulder arch, tip up or down with the beat
  float th = (.055*(1. - x*1.6) + .02)*span;                            // wings taper to the tip
  float a = 1. - smoothstep(th*.5, th*.5 + .9, abs(u.y - yw)*span);
  a *= 1. - smoothstep(.46, .52, x);
  float body = 1. - smoothstep(.9, 1.8, length(p*vec2(.55, 1.2)));
  return max(a, body);
}
float birds(vec2 q){
  const float PER = 90., DUR = 28.;                                      // a group crosses once every 90 s, taking 28 s: seen on about a third of glances
  float cyc = floor(uT/PER), u = (uT - cyc*PER)/DUR;
  if (u > 1.) return 0.;
  float dir = hash(vec2(cyc, 5.)) > .5 ? 1. : -1., n = 2. + floor(hash(vec2(cyc, 7.))*3.);
  float y0 = (1. - (.135 + .04*hash(vec2(cyc, 9.))))*uRes.y,   // the lane between the header and the top of the readiness arc: never behind the hero number
        span0 = (7. + 4.*hash(vec2(cyc, 11.)))*uDpr;
  float a = 0.;
  for (int j = 0; j < 4; j++) {
    if (float(j) >= n) break;
    float fj = float(j), hj = hash(vec2(cyc, 20. + fj));
    float x = mix(-.08, 1.08, dir > 0. ? u : 1. - u)*uRes.x - dir*fj*(16. + 10.*hj)*uDpr;
    float y = y0 + ((hj - .5)*12. + 2.5*sin(uT*.9 + fj*2.))*uDpr;
    float span = span0*(.85 + .3*hj);
    float tf = uT + hj*5.;
    float flap = smoothstep(.0, .25, sin(tf*.9)) ;                       // flap for a few beats, then glide
    float up = mix(.25, sin(tf*2.*3.14159*3.), flap);
    vec2 p = (q - vec2(x, y))*vec2(dir, 1.);
    if (abs(p.x) < span && abs(p.y) < span) a = max(a, bird(p, span, up));
  }
  return a*smoothstep(0., .06, u)*smoothstep(1., .94, u);
}
float rainAt(vec2 P, float s, float sp, float seed){
  vec2 uv = vec2((P.x + P.y*.10)*s, P.y*s*.06 - uT*sp);                // slight slant; cells tall and thin
  vec2 id = floor(uv), f = fract(uv);
  if (hash(id + seed) > .42) return 0.;
  float xo = .15 + .7*hash(id + seed + 7.), yo = .5*hash(id + seed + 13.);
  float line = 1. - smoothstep(0., s/uRes.y*1.3, abs(f.x - xo));       // about one pixel wide
  return line*smoothstep(yo, yo + .12, f.y)*(1. - smoothstep(yo + .30, yo + .48, f.y));
}
vec4 tap(sampler2D t, vec2 px){ return texture(t, (px + uMargin)/uSliceRes); }
vec4 tap1(vec2 px){ return texture(uS1, (px + uMargin)/uSliceRes1); }     // the near-cloud slice is wider when baked: room to drift
void main(){
  vec2 px = gl_FragCoord.xy*uXf.xy + uXf.zw;
  vec4 s0 = tap(uS0, px + uO0), s1 = tap1(px + uO1), s2 = tap(uS2, px + uO2), s3 = tap(uS3, px + uO3);
  s2 = windLand(s2, tap(uM2, px + uO2), px + uO2); s3 = windLand(s3, tap(uM3, px + uO3), px + uO3);
  vec3 c = s0.rgb;
  {                                                                     // live over the open sky (slice 0's mask): the sun's or moon's halo, and stars that twinkle
    vec2 q0 = px + uO0, P = vec2(q0.x/uRes.y, 1. - q0.y/uRes.y);
    vec3 rd = normalize(vec3((q0.x - .5*uRes.x)/uRes.y, (q0.y - (1. - HOR)*uRes.y)/uRes.y, FOC));
    vec3 sd = normalize(vec3((uSun.x - .5)*uRes.x/uRes.y, ((1. - uSun.y) - (1. - HOR))*uRes.y/uRes.y, FOC));
    float cc = dot(rd, sd), hz = 1. - uVeil*smoothstep(.22, .40, .40 + (P.y - HOR)*(.60/(1. - HOR)))*.85;
    c += uHalo*(.0035*hgN(cc, .90) + .012*hgN(cc, .45))*s0.a*hz;
    if (uStars > .001) {
      vec2 g = floor(P*300.); float h = hash(g);
      if (h > .9968) {                                                  // one star in ~300 cells: a soft point at the cell centre
        float d = length(P - (g + .5)/300.)*uRes.y;
        float tw = .55 + .45*sin(uT*.8 + h*40.);
        c += vec3(.70, .79, 1.)*(1. - smoothstep(.5*uDpr, 1.3*uDpr, d))*tw*(1. - smoothstep(.08, .5, P.y))*uStars*s0.a;
      }
    }
  }
  if (uShowSun > .001) {                                                // sun or moon disc, behind the clouds of slice 0 through its open-sky mask
    vec2 q0 = px + uO0, P = vec2(q0.x/uRes.y, 1. - q0.y/uRes.y), S = vec2(uSun.x*uRes.x/uRes.y, uSun.y);
    float disc = 1. - smoothstep(uSunR*.82, uSunR, length(P - S));
    if (uNight > .5) disc *= smoothstep(uSunR*.55, uSunR*.95, length(P - S - vec2(uSunR*.45, -uSunR*.35)));   // crescent
    float yl = .40 + (P.y - HOR)*(.60/(1. - HOR));
    disc *= 1. - uVeil*smoothstep(.22, .40, yl)*.85;                    // fog and rain veil the horizon
    c = mix(c, uSunDisc, disc*uShowSun*s0.a);
  }
  c = c*(1. - s1.a) + s1.rgb;
  if (uBirds > 0.) c = mix(c, uBirdCol, birds(px + uO1*1.2)*uBirds*.85);   // in front of the clouds, behind the land
  c = c*(1. - s2.a) + s2.rgb; c = c*(1. - s3.a) + s3.rgb;
  float mask = s0.a*(1. - s1.a)*(1. - s2.a)*(1. - s3.a);
  if (uRain > 0.) {
    vec2 P = vec2(px.x/uRes.y, 1. - px.y/uRes.y);
    float r = rainAt(P, 70., 9., 1.)*.38 + rainAt(P, 130., 7., 5.)*.26 + rainAt(P, 230., 5., 9.)*.16;
    c = mix(c, uRainCol, r*uRain);
  }
  o = vec4(c, mask);
}`;
  // Baked layers (3.12): colour as RGBM in sqrt space (0..8 linear; sqrt gives the dark night sky more steps than linear RGBM),
  // and an aux image (coverage or open-sky mask, wind material r g). Encoded on the GPU, read back, written as PNG.
  const ENCODE = `#version 300 es
precision highp float; uniform sampler2D uC, uM; uniform float uMode, uHasM; uniform vec2 uDst; out vec4 o;
const float R = 2.8284271;
void main(){ vec2 uv = gl_FragCoord.xy/uDst; vec4 c = texture(uC, uv);
  if (uMode < .5) { vec3 g = sqrt(max(c.rgb, 0.)); float m = clamp(max(max(g.r, g.g), max(g.b, 1e-6))/R, 1./255., 1.); m = ceil(m*255.)/255.; o = vec4(g/(m*R), m); }
  else { vec2 mt = uHasM > .5 ? texture(uM, uv).rg : vec2(0.); o = vec4(clamp(c.a, 0., 1.), clamp(mt, 0., 1.), 1.); } }`;
  const DECODE = `#version 300 es
precision highp float; uniform sampler2D uAc, uAa, uBc, uBa; uniform float uW; uniform vec2 uDst;
layout(location = 0) out vec4 o; layout(location = 1) out vec4 o2;
const float R = 2.8284271;
vec3 dec(vec4 e){ vec3 g = e.rgb*e.a*R; return g*g; }
void main(){ vec2 uv = gl_FragCoord.xy/uDst;                          // the hour between two baked keyframes: blend in linear light
  vec3 c = mix(dec(texture(uAc, uv)), dec(texture(uBc, uv)), uW); vec4 x = mix(texture(uAa, uv), texture(uBa, uv), uW);
  o = vec4(c, x.r); o2 = vec4(x.g, x.b, 0., x.r); }`;
  // Light shafts: march toward the sun gathering open sky near it. Soft by nature, so they run at quarter resolution and are
  // blurred (3.12: at full resolution this march was ~15 of the 24 ms a frame took on the phone).
  const RAYS = `#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst, uSunUV, uRes; out vec4 o;
float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x*p.y); }
void main(){ vec2 uv = gl_FragCoord.xy/uDst;
  vec2 d = (uv - uSunUV)/48.*.95, q = uv - d*hash(gl_FragCoord.xy);
  float acc = 0., w = 1., asp = uRes.x/uRes.y;
  for (int i = 0; i < 48; i++) { q -= d; vec2 r = (q - uSunUV)*vec2(asp, 1.); acc += texture(uSrc, q).a*exp(-dot(r, r)*18.)*w; w *= .968; }
  o = vec4(vec3(acc/48.), 1.); }`;
  const FINAL = `#version 300 es
precision highp float;
uniform sampler2D uScene, uB1, uB2, uR; uniform vec2 uRes; uniform float uBloom, uRays, uSat, uCon, uT; uniform vec3 uGain, uLift, uSunCol;
out vec4 o;
float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x*p.y); }
void main(){
  vec2 uv = gl_FragCoord.xy/uRes;
  vec3 c = texture(uScene, uv).rgb;
  c += (texture(uB1, uv).rgb*.65 + texture(uB2, uv).rgb*.35)*uBloom;
  if (uRays > 0.) c += uSunCol*texture(uR, uv).r*uRays;          // light shafts, marched at quarter resolution (RAYS)
  c *= uGain;                                                     // white balance for the hour
  c = mix(c, vec3(1.) - .2*exp(-(c - .8)/.2), step(.8, c));        // soft highlight shoulder
  vec3 s = pow(max(c, 0.), vec3(1./2.2));
  s += uLift*(1. - s);                                            // tint the shadows
  s = mix(vec3(dot(s, vec3(.299, .587, .114))), s, uSat);
  s = mix(s, s*s*(3. - 2.*s), uCon);                              // gentle S-curve
  o = vec4(clamp(s + (hash(gl_FragCoord.xy + fract(uT)) - .5)/255.*2., 0., 1.), 1.);
}`;

  const hex = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255);
  const norm = v => { const l = Math.hypot(...v); return v.map(c => c / l); };
  // Day sky sampled from the Samsung Weather reference. key = direction the clouds are lit from (x right, y up, z into the scene).
  const PAL = {
    dawn: { zen: '#6F93C8', mid: '#B9D3EC', hor: '#FAD8B8', sunC: '#FFE2BC', sun: [.20, .42], sunR: .026, cl: '#FFE9D6', cd: '#B9AEB4', fog: '#E8DED6', path: '#E6D6BC',
      h: ['#A9C4A2', '#9FC48E', '#A2C78C', '#A6CB8C'], tree: '#4F6E57', night: 0, I: 1.15, key: [-.75, .30, .35],
      g: { gain: [1.05, .99, .94], lift: [0, .006, .03], sat: 1.06, con: .05, rays: .55, bloom: .5 } },
    day: { zen: '#3E9BEA', mid: '#80C6F5', hor: '#D6EEFC', sunC: '#FFF6DE', sun: [.80, .12], sunR: .030, cl: '#FFF9EE', cd: '#8FA4BC', fog: '#D3E8F2', path: '#E4DCC2',
      h: ['#9CC79A', '#92C57C', '#97CA79', '#9DCF7B'], tree: '#55895C', night: 0, I: 1.25, key: [.45, .80, -.35],
      g: { gain: [1.01, 1, .99], lift: [0, .004, .012], sat: 1.03, con: .06, rays: .22, bloom: .35 } },
    dusk: { zen: '#24477C', mid: '#8FA8C8', hor: '#F7AD78', sunC: '#FFC48C', sun: [.80, .42], sunR: .028, cl: '#FFC9A0', cd: '#6E6A80', fog: '#C9A99C', path: '#C9A88A',
      h: ['#7F8F8C', '#6D8466', '#627C5A', '#5B7654'], tree: '#33473A', night: 0, I: 1.1, key: [.75, .22, .35],
      g: { gain: [1.07, .97, .90], lift: [.012, 0, .035], sat: 1.08, con: .08, rays: .65, bloom: .55 } },
    night: { zen: '#040A18', mid: '#0E1E3C', hor: '#1E3557', sunC: '#E6ECF5', sun: [.78, .11], sunR: .022, cl: '#8DA2C4', cd: '#16223A', fog: '#1E3352', path: '#2E3E50',
      h: ['#1C3248', '#1A3042', '#172B3C', '#142636'], tree: '#0B1724', night: 1, I: .30, key: [.35, .85, -.25],
      g: { gain: [.96, .99, 1.06], lift: [0, .006, .02], sat: .92, con: .04, rays: .06, bloom: .4 } },
  };
  // Hour of day: palettes are keyframes, blended in linear light, so the scene moves smoothly through the day.
  const KEYS = [[0, 'night'], [5, 'night'], [6.5, 'dawn'], [9, 'day'], [16.5, 'day'], [19, 'dusk'], [21, 'night'], [24, 'night']];
  const SUN = [[5.5, [.10, .68]], [6.5, [.13, .56]], [8.5, [.22, .32]], [13, [.62, .13]], [16.5, [.80, .26]], [19, [.86, .555]], [20, [.90, .68]]];   // screen x, y from the top; horizon at .58
  const MOON = [.78, .16], HOURS = { dawn: 6.5, day: 13, dusk: 19, night: 23 };
  const sstep = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
  const toHex = a => '#' + a.map(c => Math.round(Math.min(1, Math.max(0, c)) ** (1 / 2.2) * 255).toString(16).padStart(2, '0')).join('');
  const mixV = (a, b, f) => typeof a === 'number' ? a + (b - a) * f
    : typeof a === 'string' ? toHex(hex(a).map((c, i) => c ** 2.2 + (hex(b)[i] ** 2.2 - c ** 2.2) * f))
    : Array.isArray(a) ? a.map((x, i) => mixV(x, b[i], f)) : Object.fromEntries(Object.keys(a).map(k => [k, mixV(a[k], b[k], f)]));
  const seg = (T, h, get) => { if (h <= T[0][0]) return get(T[0]); for (let i = 1; i < T.length; i++) if (h <= T[i][0]) return mixV(get(T[i - 1]), get(T[i]), sstep(T[i - 1][0], T[i][0], h)); return get(T[T.length - 1]); };
  const palAt = h => seg(KEYS, ((h % 24) + 24) % 24, k => PAL[k[1]]);
  const sunAt = h => seg(SUN, ((h % 24) + 24) % 24, k => k[1]);

  // readiness drives the weather: fair-weather cumulus when ready, taller and greyer as readiness falls, low flat cover when poor
  // SkyState: everything the sky can be. Readiness picks the low-cloud regime (PLAN-sky.md section 4); sleep, HRV, activity and illness add layers in Round 3.8.
  const SKY0 = { style: 0, paintCov: .5, cirrus: .66, ciHook: .6, ciVeil: 0, ciAng: .95, cover: .30, tall: .62, grey: 0, haze: 0, sheet: 0, sType: 0, sb: 1.0, st: 1.7, fog: 0, fogH: 0, rain: 0, wind: .45 };   // wind: breeze over the meadow, 0 calm .. 1 gusty
  const weather = r => Object.assign({}, SKY0, r == null ? { cover: .52, tall: .60, grey: .05, haze: .05 }
    : r >= 80 ? {}                                                                                   // cirrus with a few fair-weather cumulus
    : r >= 65 ? { cover: .50, tall: .75, grey: .06, haze: .04, wind: .60 }                           // partly cloudy
    : r >= 50 ? { cover: .30, tall: .40, grey: .20, haze: .08, sheet: .75, sType: .30, sb: 1.0, st: 1.75, wind: .65 }   // stratocumulus
    : { cover: .15, tall: .35, grey: .42, haze: .15, sheet: 1, sType: 1, sb: .55, st: 1.25, wind: .50 });   // low stratus
  const PRESETS = {
    painted: { style: 1, paintCov: .72, cover: 0, cirrus: 0 }, paintedClear: { style: 1, paintCov: .25, cover: 0, cirrus: 0 }, paintedCloudy: { style: 1, paintCov: 1, cover: 0, cirrus: 0 },
    fair: {}, partly: { cover: .50, tall: .75, grey: .06, haze: .04, wind: .60 },
    cirrusFibratus: { cirrus: .70, ciHook: 0, cover: 0 }, cirrusUncinus: { cirrus: .62, ciHook: 1, cover: 0, ciAng: 1.2 },
    cirrusDense: { cirrus: .92, ciHook: .3, cover: 0, ciAng: .7 }, cirrostratus: { cirrus: .95, ciVeil: 1, ciHook: 0, cover: 0, haze: .05 },
    stratocumulus: { cover: .30, tall: .40, grey: .20, haze: .08, sheet: .75, sType: .30, sb: 1.0, st: 1.75, wind: .65 },
    stratus: { cover: .15, tall: .35, grey: .42, haze: .15, sheet: 1, sType: 1, sb: .55, st: 1.25, wind: .50 },
    valleyFog: { fog: .85, fogH: .10, wind: .05 }, mist: { cover: .40, fog: .55, fogH: .55, haze: .05, wind: .15 },
    rain: { cover: .10, tall: .30, grey: .48, haze: .15, sheet: 1, sType: 1, sb: .50, st: 1.4, fog: .30, fogH: .35, rain: .85, wind: .90 },
  };

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

  // ---------- PNG writer for baked layers: exact 8-bit RGBA (a canvas would premultiply alpha and lose the RGBM data)
  const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
  const crc32 = (b, c = 0xFFFFFFFF) => { for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 255] ^ (c >>> 8); return c; };
  async function encodePNG(w, h, px) {                                   // px: RGBA rows bottom-up (GL order); written top-down
    const st = w * 4, raw = new Uint8Array((st + 1) * h), cand = [0, 1, 2, 3, 4].map(() => new Uint8Array(st));
    for (let y = 0; y < h; y++) {
      const row = px.subarray((h - 1 - y) * st, (h - y) * st), up = y ? px.subarray((h - y) * st, (h - y + 1) * st) : null;
      for (let i = 0; i < st; i++) {                                     // the five PNG filters; keep the row that sums smallest (standard heuristic)
        const a = i >= 4 ? row[i - 4] : 0, b = up ? up[i] : 0, c = up && i >= 4 ? up[i - 4] : 0, x = row[i];
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        cand[0][i] = x; cand[1][i] = x - a; cand[2][i] = x - b; cand[3][i] = x - ((a + b) >> 1); cand[4][i] = x - (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      }
      let best = 0, bs = Infinity;
      for (let f = 0; f < 5; f++) { let s = 0; const r = cand[f]; for (let i = 0; i < st; i++) s += r[i] < 128 ? r[i] : 256 - r[i]; if (s < bs) { bs = s; best = f; } }
      raw[y * (st + 1)] = best; raw.set(cand[best], y * (st + 1) + 1);
    }
    const z = new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new CompressionStream('deflate'))).arrayBuffer());
    const chunk = (type, data) => { const out = new Uint8Array(12 + data.length), dv = new DataView(out.buffer); dv.setUint32(0, data.length); out.set([...type].map(ch => ch.charCodeAt(0)), 4); out.set(data, 8); dv.setUint32(8 + data.length, (crc32(out.subarray(4, 8 + data.length)) ^ 0xFFFFFFFF) >>> 0); return out; };
    const ih = new Uint8Array(13), dv = new DataView(ih.buffer); dv.setUint32(0, w); dv.setUint32(4, h); ih.set([8, 6, 0, 0, 0], 8);   // 8-bit RGBA, no interlace
    return new Blob([new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', z), chunk('IEND', new Uint8Array(0))], { type: 'image/png' });
  }
  const shoulder = v => v < .8 ? v : 1 - .2 * Math.exp(-(v - .8) / .2);   // the same soft highlight shoulder the sky slice applies
  // the light of an hour as numbers: palette colours in linear light, its scalars, and the sun's place (the grade is live, so left out)
  const lightVec = h => { const p = palAt(h), v = [];
    for (const [k, x] of Object.entries(p)) { if (k === 'g') continue; (Array.isArray(x) ? x : [x]).forEach(y => typeof y === 'string' ? v.push(...hex(y).map(c => c ** 2.2)) : typeof y === 'number' && v.push(y)); }
    return v.concat(p.night < .5 ? sunAt(h) : MOON); };
  // Hours baked per weather: densest where the light changes fastest; 21:00 holds the night until 05:00.
  const KEYFRAMES = [5, 5.75, 6.5, 7.5, 9, 11, 13, 15, 16.5, 17.75, 19, 20, 21];
  const bracketHours = (h, list = KEYFRAMES) => { const L = list.slice().sort((a, b) => a - b), i = L.findIndex(x => x > ((h % 24) + 24) % 24); return [L[i <= 0 ? L.length - 1 : i - 1], L[i < 0 ? 0 : i]]; };
  const SKY_CACHE = 'sky-v2';                                             // Cache Storage for layers baked on this device. Bump it whenever the sky's look changes: bakes from an older shader are then dropped (v2: 3.14 clouds)
  const cached = async url => { if (G.caches) { const r = await (await G.caches.open(SKY_CACHE)).match(new URL(url, G.location.href).href); if (r) return r; } return fetch(url); };
  const regimeOf = r => r == null ? 'firstRun' : r >= 80 ? 'fair' : r >= 65 ? 'partly' : r >= 50 ? 'stratocumulus' : 'stratus';
  const DRIFT_ROOM = 72, DRIFT_V = .8;                                   // baked near clouds: CSS px of extra layer to drift into, CSS px per second (clouds ~10 km away at 10 m/s)

  // mount(canvas, opts). opts.baked = 'baked/<set>/' plays pre-rendered layers instead of rendering the sky (3.12);
  // opts.bake = true renders a frame for export (fixed dpr, extra room on the near-cloud layer), see bake().
  function mount(canvas, opts) {
    const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
    if (!gl) { fail(canvas, 'WebGL2 unavailable'); return { set() {} }; }
    canvas.__gl = gl;
    const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    let st = Object.assign({ time: 'day', readiness: 86, showSun: true, animate: true, post: true }, opts), t0 = performance.now(), last = 0, lastC = 0, cost = 30, gpu = 30, cur = null;
    const baked = !!st.baked;
    // The sky renders into depth slices that are composited, graded and post-processed: float render targets are required.
    if (!gl.getExtension('EXT_color_buffer_float')) { fail(canvas, 'Needs float render targets (EXT_color_buffer_float)'); return { set() {} }; }   // shortcut: no 8-bit fallback; current desktop and phone browsers support it
    // live: the heavy scene shader and its noise volumes. Baked: none of it, only the cheap passes below.
    let pr = null, U = null;
    if (!baked) {
      const tex = makeNoise(gl); pr = prog(gl, FRAG); gl.useProgram(pr); U = n => gl.getUniformLocation(pr, n);
      [[tex.shape, gl.TEXTURE_3D, 'uShape'], [tex.detail, gl.TEXTURE_3D, 'uDetail'], [tex.weather, gl.TEXTURE_2D, 'uWeather']].forEach(([t, k, n], i) => { gl.activeTexture(gl.TEXTURE0 + i); gl.bindTexture(k, t); gl.uniform1i(U(n), i); });
    }
    const pB = prog(gl, BRIGHT), pBl = prog(gl, BLUR), pF = prog(gl, FINAL), pC = prog(gl, COMPOSE), pR = prog(gl, RAYS), Up = (q, n) => gl.getUniformLocation(q, n);
    // Parallax: each slice slides by its own amount (CSS px at full tilt). Nearer slices slide further, as when the eye moves sideways;
    // the cards stay put, so the scene reads as a world behind the glass. Slices are rendered past the screen edge by MARGIN.
    const AMP = [3, 9, 14, 22], MARGIN = 28, SPLIT = 30;                 // SPLIT: clouds nearer than this (km) go in the near-cloud slice. 30, not 14 (3.12): hero clouds reach ~24 km, and a cloud cut by the split tears apart when the near layer drifts or tilts; beyond 30 km clouds sit low enough to stay behind the far ridge
    const cssDpr = () => st.dpr || Math.min(2, G.devicePixelRatio || 1);
    let RT = null, man = null;
    function target(w, h, mat, fmt) {
      gl.activeTexture(gl.TEXTURE6);                                       // units 0-2 hold the noise; binding here must not replace them
      const tex = () => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
        if (fmt === 'u8') gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null); else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
        [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.LINEAR));
        [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.CLAMP_TO_EDGE)); return t; };
      const t = tex(), f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
      if (!mat) return { t, f, w, h };
      const m = tex(); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT1, gl.TEXTURE_2D, m, 0);   // wind material, accumulated with the colour
      gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]);
      return { t, m, f, w, h };
    }
    const drop = x => { gl.deleteTexture(x.t); if (x.m) gl.deleteTexture(x.m); gl.deleteFramebuffer(x.f); };
    // The frame is the picture the slices hold; the canvas shows it (cover fit). Live, they are the same; baked, the frame is the bake's.
    function targets() {
      const CW = canvas.width, CH = canvas.height;
      const F = baked ? { W: man.W, H: man.H, dpr: man.dpr, E: man.E } : { W: CW, H: CH, dpr: cssDpr(), E: st.bake ? Math.round(DRIFT_ROOM * cssDpr()) : 0 };
      if (RT && RT.W === F.W && RT.H === F.H && RT.CW === CW && RT.CH === CH && RT.E === F.E) return false;
      if (RT) [...RT.s, RT.acc, RT.a, RT.b, RT.c, RT.d, RT.r].forEach(drop);
      k = 0;                                                               // new buffers: restart the average
      const m = Math.ceil(MARGIN * F.dpr), q = n => [Math.max(1, Math.ceil(CW / n)), Math.max(1, Math.ceil(CH / n))];
      RT = { W: F.W, H: F.H, CW, CH, m, E: F.E, dpr: F.dpr, s: [0, 1, 2, 3].map(i => target(F.W + 2 * m + (i === 1 ? F.E : 0), F.H + 2 * m, i >= 2)),
        acc: target(CW, CH), a: target(...q(4)), b: target(...q(4)), c: target(...q(16)), d: target(...q(16)), r: target(...q(4)) };
      const kx = Math.min(RT.W / CW, RT.H / CH); RT.xf = [kx, kx, RT.W / 2 - CW / 2 * kx, RT.H / 2 - CH / 2 * kx];   // canvas px -> frame px, cover fit, centred
      return true;
    }
    function pass(q, dst, src, set) {
      gl.useProgram(q); gl.bindFramebuffer(gl.FRAMEBUFFER, dst ? dst.f : null); gl.viewport(0, 0, dst ? dst.w : RT.CW, dst ? dst.h : RT.CH);
      gl.activeTexture(gl.TEXTURE3); gl.bindTexture(gl.TEXTURE_2D, src.t); gl.uniform1i(Up(q, 'uSrc'), 3);
      set(); gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    // Baked near clouds drift by sliding their layer into its extra room, easing to a stop as the room runs out (~70 s; sessions are shorter).
    function driftAt(now) {
      if (!baked || !live || !st.animate || !RT.E) return 0;
      const E = RT.E, d = (now - t0) / 1000 * DRIFT_V * RT.dpr, k0 = .8 * E;
      return d < k0 ? d : k0 + .2 * E * (1 - Math.exp(-(d - k0) / (.2 * E)));
    }
    function compose(now) {
      const dpr = RT.dpr;
      gl.useProgram(pC); gl.bindFramebuffer(gl.FRAMEBUFFER, RT.acc.f); gl.viewport(0, 0, RT.CW, RT.CH);
      const drift = driftAt(now);
      RT.s.forEach((x, i) => { gl.activeTexture(gl.TEXTURE7 + i); gl.bindTexture(gl.TEXTURE_2D, x.t); gl.uniform1i(Up(pC, 'uS' + i), 7 + i); gl.uniform2f(Up(pC, 'uO' + i), tilt.x * AMP[i] * dpr + (i === 1 ? drift : 0), tilt.y * AMP[i] * .6 * dpr); });
      gl.uniform2f(Up(pC, 'uRes'), RT.W, RT.H); gl.uniform2f(Up(pC, 'uMargin'), RT.m, RT.m); gl.uniform2f(Up(pC, 'uSliceRes'), RT.W + 2 * RT.m, RT.H + 2 * RT.m); gl.uniform2f(Up(pC, 'uSliceRes1'), RT.W + 2 * RT.m + RT.E, RT.H + 2 * RT.m);
      gl.uniform4f(Up(pC, 'uXf'), ...RT.xf);
      gl.uniform1f(Up(pC, 'uT'), st.animate ? (now - t0) / 1000 + 40 : 40); gl.uniform1f(Up(pC, 'uRain'), cur.w.rain); gl.uniform1f(Up(pC, 'uNight'), cur.p.night);
      gl.uniform3fv(Up(pC, 'uRainCol'), hex(mixV('#EDF2FA', cur.p.fog, cur.p.night)).map(v => v ** 2.2));   // light streaks by day, fog-coloured at night
      [2, 3].forEach((i, j) => { gl.activeTexture(gl.TEXTURE11 + j); gl.bindTexture(gl.TEXTURE_2D, RT.s[i].m); gl.uniform1i(Up(pC, 'uM' + i), 11 + j); });
      gl.uniform1f(Up(pC, 'uWind'), cur.w.wind); gl.uniform1f(Up(pC, 'uDpr'), dpr); gl.uniform1f(Up(pC, 'uWindDbg'), st.windDebug ? 1 : 0);
      gl.uniform1f(Up(pC, 'uBirds'), live ? (1 - cur.p.night) * (1 - cur.w.rain) * (1 - .8 * Math.min(1, cur.w.fog)) * (1 - .7 * cur.w.grey) : 0);   // day, dry, clear enough to see them; none when still
      gl.uniform3fv(Up(pC, 'uBirdCol'), hex(mixV('#2F3B48', cur.p.mid, .18)).map(v => v ** 2.2));   // dark, a little hazed by the air between
      gl.uniform2f(Up(pC, 'uSun'), cur.sp[0], cur.sp[1]); gl.uniform1f(Up(pC, 'uSunR'), cur.p.sunR);
      gl.uniform1f(Up(pC, 'uShowSun'), st.showSun && !st.bake ? cur.vis * (1 - cur.w.style) : 0);   // a bake never holds the disc: it is drawn here, where the hour puts it
      gl.uniform1f(Up(pC, 'uVeil'), Math.min(1, cur.w.fog * .9 + cur.w.rain * .5));
      gl.uniform3fv(Up(pC, 'uSunDisc'), hex(cur.p.sunC).map(c => shoulder(c ** 2.2 * (cur.p.night > .5 ? 1 : 2.2))));
      const halo = st.showSun ? cur.vis * (1 - cur.p.night * .85) * (1 - cur.w.style) : 0;
      gl.uniform3fv(Up(pC, 'uHalo'), hex(cur.p.sunC).map(c => c ** 2.2 * halo)); gl.uniform1f(Up(pC, 'uStars'), sstep(.3, .9, cur.p.night) * (1 - cur.w.style));
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    function post(now) {
      const g = st.post ? cur.p.g : { gain: [1, 1, 1], lift: [0, 0, 0], sat: 1, con: 0, rays: 0, bloom: 0 };
      const blur = (src, tmp, dst) => {
        pass(pBl, tmp, src, () => { gl.uniform2f(Up(pBl, 'uDst'), tmp.w, tmp.h); gl.uniform2f(Up(pBl, 'uDir'), 1 / src.w, 0); });
        pass(pBl, dst, tmp, () => { gl.uniform2f(Up(pBl, 'uDst'), dst.w, dst.h); gl.uniform2f(Up(pBl, 'uDir'), 0, 1 / tmp.h); });
      };
      pass(pB, RT.a, RT.acc, () => { gl.uniform2f(Up(pB, 'uDst'), RT.a.w, RT.a.h); gl.uniform2f(Up(pB, 'uSrcTexel'), 1.5 / RT.acc.w, 1.5 / RT.acc.h); gl.uniform1f(Up(pB, 'uTh'), .72); });
      blur(RT.a, RT.b, RT.a);
      pass(pB, RT.c, RT.a, () => { gl.uniform2f(Up(pB, 'uDst'), RT.c.w, RT.c.h); gl.uniform2f(Up(pB, 'uSrcTexel'), 1.5 / RT.a.w, 1.5 / RT.a.h); gl.uniform1f(Up(pB, 'uTh'), -10); });
      blur(RT.c, RT.d, RT.c);
      const [kx, , ox, oy] = RT.xf, sunUV = [(cur.sp[0] * RT.W - ox) / kx / RT.CW, ((1 - cur.sp[1]) * RT.H - oy) / kx / RT.CH];   // the sun in canvas uv
      const rays = g.rays * (st.showSun ? 1 : 0) * (1 - cur.w.style) * (1 - .8 * Math.min(1, cur.w.sheet));   // no shafts without a visible sun or under a closed sheet
      if (rays > 0) {
        pass(pR, RT.r, RT.acc, () => { gl.uniform2f(Up(pR, 'uDst'), RT.r.w, RT.r.h); gl.uniform2f(Up(pR, 'uSunUV'), sunUV[0], sunUV[1]); gl.uniform2f(Up(pR, 'uRes'), RT.CW, RT.CH); });
        blur(RT.r, RT.b, RT.r);
      }
      pass(pF, null, RT.acc, () => {
        gl.uniform1i(Up(pF, 'uScene'), 3);
        gl.activeTexture(gl.TEXTURE4); gl.bindTexture(gl.TEXTURE_2D, RT.a.t); gl.uniform1i(Up(pF, 'uB1'), 4);
        gl.activeTexture(gl.TEXTURE5); gl.bindTexture(gl.TEXTURE_2D, RT.c.t); gl.uniform1i(Up(pF, 'uB2'), 5);
        gl.activeTexture(gl.TEXTURE6); gl.bindTexture(gl.TEXTURE_2D, RT.r.t); gl.uniform1i(Up(pF, 'uR'), 6);
        gl.uniform2f(Up(pF, 'uRes'), RT.CW, RT.CH);
        gl.uniform1f(Up(pF, 'uBloom'), g.bloom); gl.uniform1f(Up(pF, 'uRays'), rays);
        gl.uniform1f(Up(pF, 'uSat'), g.sat); gl.uniform1f(Up(pF, 'uCon'), g.con); gl.uniform1f(Up(pF, 'uT'), (now / 1000) % 100);
        gl.uniform3fv(Up(pF, 'uGain'), g.gain); gl.uniform3fv(Up(pF, 'uLift'), g.lift); gl.uniform3fv(Up(pF, 'uSunCol'), hex(cur.p.sunC).map(c => c ** 2.2));
      });
    }
    function size() { const dpr = cssDpr(); const w = st.frameSize ? st.frameSize[0] : Math.round(canvas.clientWidth * dpr), h = st.frameSize ? st.frameSize[1] : Math.round(canvas.clientHeight * dpr); if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; } }
    // What the hour, readiness and sky options mean right now (palette, sun, weather): shared by the live and baked paths.
    function state() {
      const hour = st.hour != null ? st.hour : HOURS[st.time] != null ? HOURS[st.time] : 13;
      const p = palAt(hour), w = Object.assign(weather(st.readiness), st.sky || {});
      const vis = p.night < .5 ? 1 - sstep(.3, .5, p.night) : sstep(.5, .7, p.night);   // the sun sets, then the moon comes up
      const sp = st.sunAt || (p.night < .5 ? sunAt(hour) : MOON);
      cur = { p, sp, w, vis, hour };
    }
    function setScene(now) {
      state(); const { p, sp, w, vis } = cur;
      const dayN = st.seed != null ? st.seed : Math.floor(Date.now() / 864e5), rndDay = k => { const x = Math.sin(dayN * 127.1 + k * 311.7) * 43758.5453; return x - Math.floor(x); };   // the upper wind turns from day to day
      gl.uniform2f(U('uRes'), RT.W, RT.H); gl.uniform1f(U('uT'), st.animate ? (now - t0) / 1000 + 40 : 40);
      gl.uniform1f(U('uCover'), w.cover); gl.uniform1f(U('uTall'), w.tall); gl.uniform1f(U('uGrey'), w.grey); gl.uniform1f(U('uHaze'), w.haze);
      [['uStyle', w.style], ['uPaintCov', w.paintCov], ['uCirrus', w.cirrus], ['uCiHook', w.ciHook], ['uCiVeil', w.ciVeil], ['uCiAng', w.ciAng + (rndDay(12) - .5) * 1.1]].forEach(([n, v]) => gl.uniform1f(U(n), v));
      [['uSheet', w.sheet], ['uSType', w.sType], ['uSB', w.sb], ['uST', w.st], ['uFogD', w.fog], ['uFogH', w.fogH], ['uRain', w.rain]].forEach(([n, v]) => gl.uniform1f(U(n), v)); gl.uniform1f(U('uNight'), p.night); gl.uniform1f(U('uSunI'), p.I);
      [['uZen', p.zen], ['uMid', p.mid], ['uHor', p.hor], ['uSunC', p.sunC], ['uCLit', p.cl], ['uCDark', p.cd], ['uFog', p.fog], ['uH0', p.h[0]], ['uH1', p.h[1]], ['uH2', p.h[2]], ['uH3', p.h[3]], ['uTree', p.tree], ['uPath', p.path]]
        .forEach(([n, c]) => gl.uniform3fv(U(n), hex(c)));
      gl.uniform3fv(U('uKey'), norm(p.key));
      const day = st.seed != null ? st.seed : Math.floor(Date.now() / 864e5), rnd = k => { const x = Math.sin(day * 127.1 + k * 311.7) * 43758.5453; return x - Math.floor(x); };
      gl.uniform2f(U('uSeed'), rnd(1) * 160, rnd(2) * 160);
      // hero clouds: one or two large cumulus in the mid distance. Their side is chosen per day, not from the sun: placed by the sun they
      // jumped across the sky when it passed the middle, and baked hours of one day must share one formation (3.12)
      const side = rnd(13) > .5 ? 1 : -1;
      gl.uniform4f(U('uHero1'), side * (1.2 + rnd(3) * 2.2), 9 + rnd(4) * 4, 2.6 + rnd(5) * 1.0, .55 + .35 * rnd(6));
      gl.uniform4f(U('uHero2'), -side * (2.5 + rnd(7) * 3), 15 + rnd(8) * 6, rnd(9) > .45 ? 2.2 + rnd(10) : 0, .45 + .3 * rnd(11));
      gl.uniform2f(U('uSun'), sp[0], sp[1]); gl.uniform1f(U('uSunR'), p.sunR); gl.uniform1f(U('uShowSun'), st.showSun ? vis : 0);   // halo and fog glow; the disc itself is drawn in compose
      gl.uniform1f(U("uStepK"), st.stepK || 1);
    }
    const reduce = !!(G.matchMedia && G.matchMedia('(prefers-reduced-motion: reduce)').matches), live = st.animate && !reduce && !st.bake;
    // Progressive accumulation: each frame uses a new ray jitter and sub-pixel offset and is blended into a running average per slice,
    // so 24 frames give a noise-free, anti-aliased image. While the sky drifts, frames blend at 25%. Tilting only re-composites.
    let k = 0, running = false, dirty = true;
    const DRIFT_MAX = 60;   // live only: ms per slice re-render; above it (phones: ~150-320 ms) the clouds hold still once converged while the wind keeps moving. Phones should play baked layers instead (opts.baked)
    const next = f => { let done = false; const go = t => { if (!done) { done = true; f(t); } }; requestAnimationFrame(go); setTimeout(() => go(performance.now()), 120); };   // keep converging when frames are throttled
    const px1 = new Uint8Array(4), sync = () => { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px1); };   // waits for the GPU
    function accumulate(now) {
      const timed = k === 4;                                               // once per scene, time a frame on the GPU (the CPU-side time says nothing about GPU load); frames 1-3 still carry driver warm-up
      if (timed) sync();
      const a = performance.now();
      size(); targets(); gl.useProgram(pr); setScene(now);
      gl.uniform2f(U('uMargin'), RT.m, RT.m); gl.uniform1f(U('uSplit'), SPLIT);
      gl.uniform2f(U('uOff'), k === 0 ? 0 : Math.random() - .5, k === 0 ? 0 : Math.random() - .5); gl.uniform1f(U('uFrame'), k % 97);
      const alpha = k === 0 ? 1 : Math.max(1 / (k + 1), live ? .25 : 0);
      if (k === 0) gl.disable(gl.BLEND); else { gl.enable(gl.BLEND); gl.blendColor(0, 0, 0, alpha); gl.blendFunc(gl.CONSTANT_ALPHA, gl.ONE_MINUS_CONSTANT_ALPHA); }
      RT.s.forEach((x, i) => { gl.bindFramebuffer(gl.FRAMEBUFFER, x.f); gl.viewport(0, 0, x.w, x.h); gl.uniform1f(U('uSlice'), i); gl.drawArrays(gl.TRIANGLES, 0, 3); });
      gl.disable(gl.BLEND); k++; dirty = true;
      if (timed) sync();
      cost = performance.now() - a; if (timed) gpu = cost;
    }

    // ---------- baked: load keyframes (one per baked hour), blend the two around the current hour into the slices
    const pD = baked ? prog(gl, DECODE) : null, cache = new Map();          // cache: bake name -> 8 textures (colour and aux per slice), at most the two in use
    let ready = false, want = null, loadMs = 0;
    async function loadBake(e) {
      if (cache.has(e.name)) return cache.get(e.name);
      const p = (async () => {
        const files = [0, 1, 2, 3].flatMap(i => [`${e.name}-s${i}-c.png`, `${e.name}-s${i}-a.png`]);
        const bms = await Promise.all(files.map(f => cached(st.baked + f).then(r => { if (!r.ok) throw new Error(f + ' ' + r.status); return r.blob(); })
          .then(bl => createImageBitmap(bl, { premultiplyAlpha: 'none', colorSpaceConversion: 'none', imageOrientation: 'flipY' }))));   // exact bytes: no premultiply, no colour management
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false); gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE); gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.activeTexture(gl.TEXTURE6);
        const tx = bms.map(bm => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, bm); bm.close();
          [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.NEAREST)); [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.CLAMP_TO_EDGE)); return t; });
        return tx;
      })();
      cache.set(e.name, p); return p;
    }
    function bracket() {                                                   // the two baked hours around the current one, and how far between
      state();
      const reg = regimeOf(st.readiness), list = man.bakes.filter(e => e.regime === reg);
      const L = (list.length ? list : man.bakes.filter(e => e.regime === man.bakes[0].regime)).slice().sort((a, b) => a.hour - b.hour), h = cur.hour;
      if (L.length === 1) return [L[0], L[0], 0];
      let i = L.findIndex(e => e.hour > h); const B = L[i < 0 ? 0 : i], A = L[i <= 0 ? L.length - 1 : i - 1];
      // The weight is where the current light sits between the two keyframes' light, not how far the clock is between them:
      // palettes move along a smoothstep in the hour, so at 05:23 the light is 16% of the way to 05:45, not 50%.
      // Project this hour's palette and sun onto the line between the keyframes' (least squares); fall back to the clock.
      const a = lightVec(A.hour), b = lightVec(B.hour), x = lightVec(h);
      let num = 0, den = 0; for (let j = 0; j < a.length; j++) { num += (x[j] - a[j]) * (b[j] - a[j]); den += (b[j] - a[j]) ** 2; }
      const span = ((B.hour - A.hour) + 24) % 24 || 24, into = ((h - A.hour) + 24) % 24;
      return [A, B, den > 1e-9 ? Math.min(1, Math.max(0, num / den)) : into / span];
    }
    async function bakedScene() {
      const t = performance.now();
      if (!man) man = await (await cached(st.baked + 'manifest.json')).json();
      size(); targets();
      const [A, B, w] = bracket(), key = A.name + '|' + B.name + '|' + w.toFixed(3);
      if (key === want) return; want = key;
      const [ta, tb] = await Promise.all([loadBake(A), loadBake(B)]);
      if (key !== want) return;                                            // a newer hour arrived while loading
      for (const [n, v] of cache) if (n !== A.name && n !== B.name) { cache.delete(n); (await v).forEach(x => gl.deleteTexture(x)); }   // keep two keyframes resident
      decode(ta, tb, w);
      loadMs = loadMs || performance.now() - t; ready = true; k = 24; dirty = true; kick();
    }
    let lastDec = null;
    function decode(ta, tb, w) {
      lastDec = [ta, tb, w];
      gl.useProgram(pD); gl.disable(gl.BLEND);
      RT.s.forEach((x, i) => {
        gl.bindFramebuffer(gl.FRAMEBUFFER, x.f); gl.viewport(0, 0, x.w, x.h);
        [[ta[2 * i], 'uAc'], [ta[2 * i + 1], 'uAa'], [tb[2 * i], 'uBc'], [tb[2 * i + 1], 'uBa']].forEach(([t, n], j) => { gl.activeTexture(gl.TEXTURE7 + j); gl.bindTexture(gl.TEXTURE_2D, t); gl.uniform1i(Up(pD, n), 7 + j); });
        gl.uniform1f(Up(pD, 'uW'), w); gl.uniform2f(Up(pD, 'uDst'), x.w, x.h); gl.drawArrays(gl.TRIANGLES, 0, 3);
      });
    }
    function resizeBaked() { size(); if (targets() && lastDec) { decode(...lastDec); k = 24; } }

    // Tilt: a critically damped spring follows the target, so motion eases in and out and never overshoots.
    const tilt = { x: 0, y: 0, vx: 0, vy: 0, tx: 0, ty: 0, base: null, t: performance.now() };
    const clamp1 = v => Math.max(-1, Math.min(1, v));
    function setTarget(x, y) { tilt.tx = reduce ? 0 : clamp1(x); tilt.ty = reduce ? 0 : clamp1(y); kick(); }
    function step(now) {
      const dt = Math.min(.05, (now - tilt.t) / 1000), w = 9; tilt.t = now;
      tilt.vx += (w * w * (tilt.tx - tilt.x) - 2 * w * tilt.vx) * dt; tilt.x += tilt.vx * dt;
      tilt.vy += (w * w * (tilt.ty - tilt.y) - 2 * w * tilt.vy) * dt; tilt.y += tilt.vy * dt;
      const moving = Math.abs(tilt.tx - tilt.x) > 1e-3 || Math.abs(tilt.ty - tilt.y) > 1e-3 || Math.abs(tilt.vx) + Math.abs(tilt.vy) > 1e-3;
      if (!moving) { tilt.x = tilt.tx; tilt.y = tilt.ty; tilt.vx = tilt.vy = 0; }
      return moving;
    }
    if (st.tilt) {
      // phone: device orientation, re-centred slowly (about 3 s) so whatever angle the phone is held at becomes neutral
      window.addEventListener('deviceorientation', e => {
        if (e.gamma == null) return;
        const b = tilt.base || (tilt.base = { g: e.gamma, b: e.beta });
        b.g += (e.gamma - b.g) * .005; b.b += (e.beta - b.b) * .005;
        setTarget((e.gamma - b.g) / 12, (e.beta - b.b) / 12);              // 12 degrees of tilt reaches full parallax
      });
      // desktop stand-in: the pointer's position over the scene
      window.addEventListener('pointermove', e => {                      // on the window: cards on top of the scene would swallow canvas events
        const r = canvas.getBoundingClientRect(), x = (e.clientX - r.left) / r.width * 2 - 1, y = (e.clientY - r.top) / r.height * 2 - 1;
        setTarget(Math.abs(x) <= 1 && Math.abs(y) <= 1 ? x : 0, Math.abs(x) <= 1 && Math.abs(y) <= 1 ? y : 0);
      });
    }
    function loop(now) {
      const moving = step(now);
      if (baked) { if (ready) resizeBaked(); }
      else if (k < 24) accumulate(live ? now : t0);
      else if (live && gpu < DRIFT_MAX && now - last > Math.max(120, gpu * 4)) { accumulate(now); last = now; }   // cloud drift re-renders the slices: only where the GPU can afford it
      const ambient = live && now - lastC > 33;                            // wind, birds, baked drift: 30 fps is enough, and spares the battery
      if ((!baked || ready) && (dirty || moving || ambient)) { compose(now); post(now); dirty = false; lastC = now; }
      if ((baked ? !ready : k < 24) || live || moving) next(loop); else running = false;
    }
    function kick() { if (!running) { running = true; tilt.t = performance.now(); next(loop); } }
    if (baked) bakedScene().catch(e => { console.error(e); fail(canvas, 'Baked sky failed to load: ' + e.message); });
    else if (!st.manual) kick();                                           // manual: the on-device baker drives rendering itself (renderSliced)
    return {
      set(o) { Object.assign(st, o); if (baked) { if (man) bakedScene(); dirty = true; kick(); } else { k = 0; if (!st.manual) kick(); } },
      setTilt(x, y) { setTarget(x, y); },
      skip(sec) { t0 -= sec * 1000; dirty = true; kick(); },              // jump the clock (board: bring the birds now)
      get t() { return (performance.now() - t0) / 1000 + 40; },
      canvas, baked, get ready() { return baked ? ready : k >= 24; }, get loadMs() { return loadMs; }, get drift() { return RT ? driftAt(performance.now()) / RT.dpr : 0; },   // CSS px the baked near clouds have slid
      get cost() { return cost; }, get gpu() { return gpu; }, get frames() { return k; }, get tilt() { return { x: tilt.x, y: tilt.y }; },
      bench(kind, n) {                                                     // GPU timing for the board: n synchronous frames, waits for the GPU
        const px = new Uint8Array(4), sync = () => { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); };
        sync(); const t = performance.now(), now = t;
        for (let i = 0; i < n; i++) { if (kind === 'compose') compose(now); else if (kind === 'post') post(now); else if (kind === 'tilt') { tilt.x = Math.sin(i); tilt.y = Math.cos(i) * .5; compose(now); post(now); } else { if (baked) decode(...lastDec); else accumulate(now); compose(now); post(now); } }
        sync(); tilt.x = tilt.y = 0; dirty = true; kick(); return (performance.now() - t) / n;
      },
      // On-device bake (3.13): converge 24 frames in horizontal bands sized so each takes about budgetMs on the GPU, pausing gapMs
      // between bands so the visible scene and the rest of the phone keep the GPU most of the time. Needs opts.bake and opts.manual.
      async renderSliced({ budgetMs = 8, gapMs = 16, onProgress, signal } = {}) {
        size(); targets(); k = 0; gl.useProgram(pr); setScene(t0);
        gl.uniform2f(U('uMargin'), RT.m, RT.m); gl.uniform1f(U('uSplit'), SPLIT);
        const pause = ms => new Promise(r => setTimeout(r, ms));
        let bh = 32, gpuMs = 0;
        for (; k < 24;) {
          gl.useProgram(pr); setScene(t0);
          gl.uniform2f(U('uOff'), k === 0 ? 0 : Math.random() - .5, k === 0 ? 0 : Math.random() - .5); gl.uniform1f(U('uFrame'), k % 97);
          const alpha = k === 0 ? 1 : 1 / (k + 1);
          for (let i = 0; i < 4; i++) {
            const x = RT.s[i];
            for (let y = 0; y < x.h;) {
              if (signal && signal.aborted) throw new Error('aborted');
              const h = Math.min(bh, x.h - y);
              sync(); const t = performance.now();
              gl.useProgram(pr); gl.bindFramebuffer(gl.FRAMEBUFFER, x.f); gl.viewport(0, 0, x.w, x.h); gl.uniform1f(U('uSlice'), i);
              gl.enable(gl.SCISSOR_TEST); gl.scissor(0, y, x.w, h);
              if (k === 0) gl.disable(gl.BLEND); else { gl.enable(gl.BLEND); gl.blendColor(0, 0, 0, alpha); gl.blendFunc(gl.CONSTANT_ALPHA, gl.ONE_MINUS_CONSTANT_ALPHA); }
              gl.drawArrays(gl.TRIANGLES, 0, 3); gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST);
              sync(); const dt = performance.now() - t; gpuMs += dt;
              bh = Math.max(4, Math.min(x.h, Math.round(h * budgetMs / Math.max(dt, .25))));   // next band: as tall as fits the budget at this band's cost
              y += h; await pause(gapMs);
            }
          }
          k++; if (onProgress) onProgress(k / 24, gpuMs);
        }
        return gpuMs;
      },
      // Export the converged frame as baked layers: { name: Blob } (PNG). Needs opts.bake (fixed dpr, extra drift room) and 24 frames.
      async bake(name) {
        if (!st.bake || k < 24) throw new Error('bake needs opts.bake and a converged frame');
        const pE = prog(gl, ENCODE), out = {};
        for (let i = 0; i < 4; i++) {
          const x = RT.s[i], o = target(x.w, x.h, false, 'u8'), px = new Uint8Array(x.w * x.h * 4);
          for (const mode of [0, 1]) {
            gl.useProgram(pE); gl.bindFramebuffer(gl.FRAMEBUFFER, o.f); gl.viewport(0, 0, x.w, x.h);
            gl.activeTexture(gl.TEXTURE3); gl.bindTexture(gl.TEXTURE_2D, x.t); gl.uniform1i(Up(pE, 'uC'), 3);
            gl.activeTexture(gl.TEXTURE4); gl.bindTexture(gl.TEXTURE_2D, x.m || x.t); gl.uniform1i(Up(pE, 'uM'), 4);
            gl.uniform1f(Up(pE, 'uMode'), mode); gl.uniform1f(Up(pE, 'uHasM'), x.m ? 1 : 0); gl.uniform2f(Up(pE, 'uDst'), x.w, x.h);
            gl.drawArrays(gl.TRIANGLES, 0, 3); gl.readPixels(0, 0, x.w, x.h, gl.RGBA, gl.UNSIGNED_BYTE, px);
            out[`${name}-s${i}-${mode ? 'a' : 'c'}.png`] = await encodePNG(x.w, x.h, px);
          }
          drop(o);
        }
        state();
        return { files: out, frame: { W: RT.W, H: RT.H, dpr: RT.dpr, E: RT.E, m: RT.m }, entry: { name, hour: cur.hour, regime: regimeOf(st.readiness), readiness: st.readiness, seed: st.seed } };
      },
    };
  }
  G.SceneGL = { mount, PAL, PRESETS, weather, palAt, sunAt, HOURS, regimeOf, KEYFRAMES, bracketHours, cached, SKY_CACHE, _noise: makeNoise, _png: encodePNG };
})();
