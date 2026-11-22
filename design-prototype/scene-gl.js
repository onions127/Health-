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
uniform vec3 uPg0, uPg1, uPg2, uPg3; uniform float uSnow;   // 3.15: far hills, lake, the page meadow under the cards (top .. bottom), snow on the ground
uniform float uDry; uniform vec3 uTk[8]; uniform vec4 uLy[18], uLz[9], uLip[3]; uniform float uNL; uniform vec4 uGr[2], uTr[2]; uniform vec4 uLm[3], uTs[8], uSd, uTst; uniform highp sampler2D uTreeTex; uniform ivec2 uTreeN;   // T5: the trees' grown branching (2 texels per segment, one row per tree)   // T3: per-layer mountain (peak height, snow 0..1, rock, -); per-tree species and season (shape; leaf rgb + fullness; leaf2 rgb + bark red; bloom rgb + amount); shore (beach width, earth patches, litter, -)   // T2: per-layer woodland patches; grass rows (back dens, height, front dens, height; mask u0, u1, comb); two small trees (u, height, seed, on) and their colour
   // + per-layer crest shape (6 vec4 each), layer props (3 vec4 each), the lip's shape   // T1: terrain direction (0 = the 3.15 land, the control), season dryness (0 green .. 1 straw), the direction's colour tokens (sRGB albedo)
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
  vec3 col = mix(toL(uHor), toL(uMid), 1. - exp(-max(e, 0.)*9.));
  return mix(col, toL(uZen), .92*smoothstep(.06, .52, e));                // 3.15: saturated azure overhead, ~25 L* paler at the horizon haze (reference ramps)
}
vec3 sky(vec3 rd, vec3 sd){                                               // with the Mie halo: the light the clouds receive from the sky
  return skyBase(rd) + toL(uSunC)*(.0035*hgN(dot(rd, sd), .90) + .004*hgN(dot(rd, sd), .45))*(1. - uNight*.94)*uShowSun;   // narrower Mie glow (3.15): the wide lobe washed the whole upper sky white
}

// ---------- cloud volume (km)
const float BASE = 1.5, THICK = 7.0, SIG = 55.;
vec3 wind(){ return vec3(uT*.010, 0., uT*.003); }
float cumulusD(vec3 p, bool full){
  vec3 q = p + wind();
  vec4 w = textureLod(uWeather, (q.xz + uSeed)/26., 0.);                // 3.15: larger, fewer cloud cells (was /16: a field of small puffs)
  float wr = clamp((w.r - .51)*3.3 + .5, 0., 1.), wg = clamp((w.g - .51)*3.6 + .5, 0., 1.);   // stretch measured p10-p90 to 0..1
  float cov = smoothstep(.86 - uCover*.9, 1.24 - uCover*.9, wr)*.85;
  cov *= 1. - .75*smoothstep(16., 40., p.z);                              // thin the far field: a clean horizon
  // hero clouds: one or two large cumulus placed per day, so the sky has a subject
  vec2 h1 = (q.xz - uHero1.xy)/uHero1.z, h2 = (q.xz - uHero2.xy)/uHero2.z;
  float hero1 = exp(-dot(h1, h1)*1.6), hero2 = exp(-dot(h2, h2)*1.6)*step(.01, uHero2.z);
  float hero = max(hero1, hero2)*smoothstep(.05, .30, uCover);          // no cumulus sky, no hero cumulus
  cov = max(cov, hero);
  wg = mix(wg, max(uHero1.w*step(hero2, hero1), uHero2.w*step(hero1, hero2)), smoothstep(.2, .7, hero));
  cov *= smoothstep(2.5, 6., p.z);                                        // open sky overhead; 3.15: from 2.5 km, so the hero masses can sit near and run off the top edge
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
    b = remap(b, m*mix(.44, .18, far)*mix(.5, 1., smoothstep(0., .03, hf)), 1., 0., 1.);   // 3.15: gentler erosion: cotton, not cauliflower   // lighter erosion at the base keeps it level
  }
  return clamp(b*1.15, 0., 1.);                                          // 3.15: softer density ramp: misty edges, no hard silhouette (was 1.6)
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
  const float AMBN = 1.;   // G3: after sunset the sky light on cloud tops is the dark sky, not white (white lit the moonlit hero cloud to L* 70-81 behind the sky text)
  vec3 sunL = toL(uCLit)*uSunI, ambHi = mix(toL(uMid), vec3(1.), .55*(1. - AMBN*uNight))*.62, ambLo = mix(toL(uCDark), toL(uMid), .35)*.62;   // 3.15: high-key, low self-shadow contrast (reference)
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
        for (int m = 0; m < 3; m++) { e += a*exp(-od*k)*mix(ph, 1., 1. - a); a *= .50; k *= .36; }   // multiple-scattering octaves
        e += .22*exp(-od*.035);                                          // deep diffusion: light still seeps through a thick sheet
        float powder = 1. - exp(-d*SIG*.5);
        float hf = p.y < BASE ? clamp((p.y - uSB)/max(uST - uSB, .1), 0., 1.)*.6 : clamp((p.y - BASE)/(THICK*uTall), 0., 1.);
        float ao = exp(-density(p + vec3(0., .28, 0.), false)*1.6);          // sky light is blocked in the creases between billows
        float shw = p.y < BASE ? 1. : 0.;                                // sheet clouds: an overcast underside is a bright, even grey
        vec3 L = sunL*e*mix(.50, 1., powder) + mix(ambLo, ambHi, smoothstep(0., .6, hf))*mix(.55, 1., ao)*(1. + .45*shw) + sunL*.10*shw;
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

// ---------- land (3.15): low far hills on the horizon, a lake strip, a grassy lip just below it, then the meadow the cards sit on.
// Screen space, y from the top. The first card starts just under the lip (~.42); below it the page meadow runs green -> teal -> blue.
const float LIP = .398;
float ovcF(){ return clamp(uSheet*mix(.6, 1., uSType), 0., 1.); }   // how overcast: flattens sun light on the land
// ---------- terrain (locked T9: the open calm loch). Parametric and branch-free: the land is
// numbers in uniforms (set in JS, LAND), so the shader stays one small program (per-direction branches inside the grass loops
// made the D3D compiler flatten them all and stall) and ports to AGSL as data. Each far layer is a crest line (designed massing + fbm detail) with a foot.
float gAsp = .45;                                                          // screen width / height, set in main
vec3 gPgTop = vec3(0.);
float gNear = 0.;                                                          // T3: how much of this pixel is a near tree (fog and rain veil the distance, not the tree 300 m away)                                                    // the page's top colour, lifted from the near land's colour
float bump(float u, float c, float w){ float d = (u - c)/w; return exp(-d*d); }
float rdg(float x, float f, float o){ return fbm3(vec2(x*f + o, o*.73)) - .45; }
int tN(){ return int(uNL + .5); }                                           // far layers in this direction (the near land is the lip)
float tDist(int i){ return uLz[i*3].x; }                                   // km: aerial perspective
float tBase(int i){ return uLz[i*3].y; }                                   // the layer's foot (screen y); > 1: it runs down under the nearer land
int tSlice(int i){ return int(uLz[i*3].z + .5); }                          // depth slice: 0 far, 2 mid
float tMass(int i, float x){                                              // the designed massing only (height above y0): smooth, for slope light
  int k = i*6; float u = x/gAsp;
  vec4 a = uLy[k], b = uLy[k + 1], c = uLy[k + 2], d = uLy[k + 3];
  return (a.y + a.z*bump(u, a.w, b.x) + b.y*bump(u, b.z, b.w) + c.x*smoothstep(c.y, c.z, u))*(1. - c.w + c.w*cos(u*d.x + d.y))*smoothstep(d.z, d.w, u);
}
float tCrest(int i, float x){
  int k = i*6; float u = x/gAsp;
  vec4 a = uLy[k], e = uLy[k + 4], f = uLy[k + 5];
  float h = tMass(i, x);
  float pk = uLm[i].x;
  if (pk > 0.) {                                                            // T4: a mountain range: sharp ridged detail that lives only on the massif, so the range ends and open horizon stays open
    float m = clamp(h/(pk*.30), 0., 1.), r1 = 1. - abs(2.*noise(vec2(x*e.y, e.z)) - 1.), r2 = 1. - abs(2.*noise(vec2(x*e.y*2.3, e.z + 4.)) - 1.);
    return a.x - h - m*(e.x*(r1*r1 - .35) + e.x*.45*(r2*r2 - .35) + e.w*(noise(vec2(x*f.x, e.z)) - .5));
  }
  h += e.x*rdg(x, e.y, e.z) + e.w*noise(vec2(x*f.x, e.z));                 // ridge detail, fine break-up of the line
  return a.x - h;
}
// the near lip (control: the 3.15 rolling shore): p0 + rise*smoothstep(s0, s1, u) + bump + fbm swell + fine break-up
float lipTop(float x){
  vec4 a = uLip[0], b = uLip[1], c = uLip[2]; float u = x/gAsp;
  float r = .0030*(fbm3(vec2(x*11. + 4., 2.)) - .45) + .0015*(noise(vec2(x*60., 7.)) - .5);
  return a.x + a.y*smoothstep(a.z, a.w, u) + b.x*bump(u, b.y, b.z) + b.w*(fbm3(vec2(x*c.x + c.y, 2.)) - .45) + c.z*r;
}
// grass blades rising off the lip: cell width cw, height up to hm. Returns coverage, place along the blade (0 base .. 1 tip), a per-blade random
vec3 blades(vec2 P, float cw, float hm, float seed, float dens){
  float c0 = floor(P.x/cw); vec3 r = vec3(0.);
  for (int i = -2; i <= 2; i++) {
    float c = c0 + float(i);
    float clump = smoothstep(.30, .75, noise(vec2(c*cw*38., seed)));      // tufts come in clumps
    if (hash(vec2(c, seed)) > dens*(.30 + .70*clump)) continue;
    float cx = (c + hash(vec2(c, seed + 1.)))*cw, base = lipTop(cx) + .003;
    float h = hm*(.25 + .75*hash(vec2(c, seed + 2.)))*(.35 + .65*clump);
    float t = (base - P.y)/h; if (t < 0. || t > 1.) continue;
    float lean = (hash(vec2(c, seed + 3.)) - .5)*.9 - .22;                // most lean a little with the breeze (to the left)
    float w = cw*.55*(1. - t*.85);
    float a = 1. - smoothstep(w*.5, w*.5 + .8/uRes.y, abs(P.x - cx - lean*h*t*t));
    if (a > r.x) r = vec3(a, t, hash(vec2(c, seed + 4.)));
  }
  return r;
}
vec3 page(float x, float y){                                              // the meadow continues under the cards as one long calm gradient
  vec3 c = mix(mix(uPg0, gPgTop, .7*(1. - uNight)), uPg1, smoothstep(.42, .62, y));   // T1: the page starts in the near land's own colour, lifted light
  c = mix(c, uPg2, smoothstep(.60, .82, y)); c = mix(c, uPg3, smoothstep(.80, 1., y));
  return c*(.988 + .024*fbm3(vec2(x*2.5, y*3.)));
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
  float d = mask*pow(fib, 2.6)*smoothstep(.25, .80, stroke)*1.7;          // soft falloff, no hard threshold: no contour lines
  d = mix(d, mask*(.42 + .35*fib), uCiVeil);                                 // veil: a milky sheet with fibres in it
  d *= exp(-dist/95.)*smoothstep(.006, .05, rd.y);                         // far cirrus thins into the haze
  float c = dot(rd, sd);
  vec3 L = toL(uCLit)*uSunI*(.72 + .30*min(hgN(c, .75), 2.))*(1. - .16*d*d) + toL(uMid)*.18;   // ice scatters forward: bright near the sun
  float a = acos(clamp(c, -1., 1.));
  float halo = exp(-pow((a - .384)/.011, 2.)) + .35*exp(-pow((a - .40)/.02, 2.));   // 22 degree halo in the veil
  L += toL(uSunC)*vec3(1.1, 1., .9)*halo*uCiVeil*.9*(1. - uNight);
  float al = clamp(d*.45, 0., .55);                                         // 3.15: thinner ice (was .70/.85): a fine veil, not white brush strokes                                         // cirrus is thin ice: the blue always shows through a little
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
// sRGB painter's compositing with coverage, so a land slice keeps soft, anti-aliased edges over the slices behind it
void over(inout vec3 C, inout float A, vec3 c, float a){ C = c*a + C*(1. - a); A = a + A*(1. - a); }
void overM(inout vec2 M, vec2 m, float a){ M = m*a + M*(1. - a); }   // the wind material, composited the same way
vec3 landFinish(vec3 L, float x, float y, float aspect){
  float far = smoothstep(LIP + .03, HOR - .01, y)*(1. - .8*gNear);         // 1 at the hills and the lake, 0 from the lip down: fog and haze live in the distance
  if ((uFogD > 0. || uRain > 0.) && far > 0.) {
    // fog lies in horizontal banks over the lake and the far hills, thickest at the horizon; fogH lifts it higher
    float bank = fbm(vec2(x*3.2 + uT*.008, y*70.));
    float f = uFogD*(far*(.55 + .45*uFogH) + uFogH*.20*far)*mix(.65, 1.2, bank);
    f = max(f, uRain*.50*far);                                                   // rain hides the distance
    vec3 fc = mix(uFog, vec3(.97, .98, .99), .45*(1. - uNight)) + vec3(1., .95, .85)*exp(-abs(x - uSun.x*aspect)*3.5)*.10*(1. - uNight)*uShowSun;   // fog glows toward the sun
    L = mix(L, fc, clamp(f*mix(1., .78, uFogD), 0., .93));   // 3.16: thick fog still lets the hills and the lake show through faintly, in layers
  }
  L = mix(L, lum(L)*vec3(.95, .96, 1.06), uGrey*.35*far);
  return L*(1. - .1*smoothstep(.3, .9, uNight));
}
// ---------- T1 terrain: light, air and the layers (painterly-real: each layer one lit plane, pushed into the haze by its distance, misted at its foot)
vec3 grassAlb(){ return mix(uTk[3], uTk[4], uDry); }                        // the season: green winter and spring .. straw summer (Mediterranean year)
vec3 snowy(vec3 a, float k){ return mix(a, vec3(.93, .95, .98), uSnow*k); }
vec3 lift(vec3 c, float L){ float l = lum(c); return mix(c, vec3(1.), clamp((L - l)/max(1. - l, .01), 0., 1.)); }
vec3 tLight(vec3 albS, float lam, float shadow){                            // sun (or moon) on a plane plus sky light; overcast trades the sun for a brighter, even sky
  float ov = ovcF();
  return toL(albS)*(toL(uSunC)*(uSunI/1.25)*(.14 + .72*lam)*(1. - .8*ov)*(1. - .55*shadow)*(1. - .8*uNight) + toL(mix(uHor, uMid, .5))*(.26 + .45*ov)*(1. - .35*uNight));   // ~1x albedo in full sun: brighter flattened the value steps
}
vec3 tAir(vec3 c, float d, float x){                                        // aerial perspective: toward the horizon colour with distance; the haze glows toward the sun
  float D = mix(42., 9., clamp(uRain*.8 + uHaze*2.5 + uFogD*.5 + uGrey*.3, 0., 1.));
  vec3 hz = toL(uHor)*(1. + .45*exp(-abs(x - uSun.x*gAsp)*5.)*uShowSun*(1. - uNight)*(1. - ovcF()));
  return mix(c, hz, 1. - exp(-d/D));
}
float tLam(float sl, float x, float y){ vec2 n = normalize(vec2(sl, -1.)), L = normalize(vec2(uSun.x*gAsp - x, uSun.y - y - .2)); return clamp(dot(n, L), 0., 1.); }
float cDen(float x, float d){ vec3 gp = vec3((x - .5*gAsp)*d/FOC, 0., d); return density(gp + uKey*((BASE + 1.0)/max(uKey.y, .15)) + vec3(0., 0., 9.), false); }   // cloud density over the ground point at distance d, toward the sun
float tShadow(float x, float d){                                            // the volume's cloud shadow on the ground at distance d
  if (uCover + uSheet <= 0.) return 0.;
  return smoothstep(.0, .25, cDen(x, d));
}
float tCover(int i, float x, float y){
  float c = tCrest(i, x), b = tBase(i), px = .8/uRes.y;
  return smoothstep(c - px, c + px, y)*(1. - smoothstep(b - px, b + px, y))*smoothstep(.0004, .0025, b - c);
}
vec3 tColor(int i, float x, float y, float shadow){                         // a far layer's colour (linear) at (x, y); shadow: cloud shadow, sampled once per pixel by the caller
  vec4 z0 = uLz[i*3], z1 = uLz[i*3 + 1], z2 = uLz[i*3 + 2];
  float d = z0.x, b = z0.y;
  float c = tCrest(i, x), e = .004, sl = (tMass(i, x - e) - tMass(i, x + e))/(2.*e), t = max(y - c, 0.);   // slope from the smooth massing (y = y0 - h)
  float lam = tLam(sl, x, y);
  vec3 a = mix(uTk[int(z1.w + .5)], grassAlb(), z2.x);                    // local colour: a token, part grass (the season)
  float rock = z2.w*step(b - .0045 - .0015*noise(vec2(x*120., 2.)), y);    // limestone at the waterline
  a = mix(a, mix(uTk[7], vec3(.55, .52, .48), .35), rock);
  vec4 mt = uLm[i];                                                         // T3: a high mountain far off: rock faces, snow above a line that follows the season, gullies carry it down
  if (mt.x > 0.) {
    float hg = b - y, gq = noise(vec2(x*520., y*30.))*.7 + noise(vec2(x*1300., y*60.))*.3, gl2 = noise(vec2(x*95., 1.3));
    a = mix(a, vec3(.50, .50, .52), mt.z*smoothstep(.2, 1.4, abs(sl)));      // steep faces: bare rock
    float line = mt.x*(1. - .58*mt.y) + .012*(gl2 - .5) - .020*(gq - .45)*mt.y;   // snow runs down the gullies, rock ribs stay dark
    float sn = mt.y*smoothstep(line - .0015, line + .0025, hg);
    a = mix(a, vec3(.95, .96, .99), sn);
    lam = clamp(lam + (gq - .5)*.45, 0., 1.);                               // ribs and gullies catch the light
  }
  a = snowy(a, .85);
  vec3 L = tLight(a, lam, shadow);
  L *= 1. + .35*exp(-t*320.)*lam*(1. - ovcF());                             // crest light: a thin bright band along a sunlit top
  L = tAir(L, d, x);
  L = mix(L, toL(mix(uHor, vec3(1.), .25)), smoothstep(.004, .035, t)*z0.w*(1. + 1.5*uFogD)*.6);   // mist pools at each layer's foot
  return L*(.97 + .06*noise(vec2(x, y)*uRes.y*.45));                       // print grain
}
vec3 tWater(vec3 rd, vec2 P, float aspect){                                 // lake or sea (linear): sky and far shore mirrored, ripples, a light path
  float dy = max(P.y - HOR, 1e-4), dist = .03/dy;
  vec2 wp = vec2((P.x - .5*aspect)*dist/FOC, dist);
  float fade = smoothstep(.001, .016, dy);
  float n = (fbm3(wp*vec2(2.5, 16.) + vec2(uT*.02, uT*.06)) - .45)*fade;
  vec3 refl = skyBase(normalize(vec3(rd.x + n*.02, max(-rd.y + n*.012, .002), rd.z)));
  float mx = P.x + n*.003;
  int ri = -1; float rcv = 0., ry = 0.;
  for (int i = 2; i >= 0; i--) {                                            // the far shore mirrored about its foot, nearest layer first
    if (ri >= 0 || i >= tN()) continue;
    float b = tBase(i); if (b > 1. || P.y <= b) continue;
    float cv = tCover(i, mx, 2.*b - P.y + n*.004);
    if (cv > .01) { ri = i; rcv = cv; ry = 2.*b - P.y + n*.004; }
  }
  if (ri >= 0) refl = mix(refl, tColor(ri, mx, ry, 0.)*.86, rcv*.85);
  float ov = ovcF(), nr = smoothstep(.0, .035, dy);
  vec3 amb = toL(mix(uHor, uMid, .5))*.80 + toL(uSunC)*(uSunI/1.25)*.30*(1. - ov);
  vec3 body = toL(mix(uTk[5], uTk[6], nr))*amb;
  float F = .02 + .98*pow(1. - clamp(-rd.y, 0., 1.), 5.);
  vec3 w = mix(body, refl, F*.55)*(1. + .10*n);
  w += refl*.18*smoothstep(.58, .80, noise(wp*vec2(1.1, 34.) + vec2(uT*.03, 0.)))*fade;   // long light streaks
  if (uSun.y < HOR) {                                                       // the sun's (or moon's) path, widest near the shore
    float sx = uSun.x*aspect, wd = .004 + .35*dy, g = exp(-pow((P.x - sx)/wd, 2.));
    float spark = smoothstep(.55, .85, noise(wp*vec2(5., 40.) + vec2(uT*.3, uT*.5)));
    float k = mix(.25, 1., smoothstep(.12, .32, uSun.y))*mix(1., .35, uNight)*uShowSun*(1. - ov);
    w += toL(uSunC)*g*(.10 + .9*spark*fade)*k*uSunI;
  }
  return tAir(w, dist*.6, P.x);
}
vec4 tWor(vec2 p, float s){                                                  // cellular noise: x = distance to the nearest feature point (cell units), y = to the second, zw = the nearest cell
  vec2 i = floor(p), f = fract(p); vec4 r = vec4(9., 9., 0., 0.);
  for (int yy = -1; yy <= 1; yy++) for (int xx = -1; xx <= 1; xx++) {
    vec2 o = vec2(float(xx), float(yy)), id = i + o;
    float d = length(o + .15 + .7*vec2(hash(id + s), hash(id + s + 17.3)) - f);
    if (d < r.x) r = vec4(d, r.x, id); else if (d < r.y) r.y = d;
  }
  return r;
}
vec4 tOver(vec4 dst, vec3 c, float a){ float ra = a + dst.a*(1. - a); return vec4((c*a + dst.rgb*dst.a*(1. - a))/max(ra, 1e-4), ra); }   // straight-alpha over
vec4 tTree(vec2 P, int k, float shadow){                                     // T5: a near deciduous tree drawn from its grown branching (uTreeTex, see treeSkel): every
  vec4 q4 = uTr[k], sp = uTs[k*4], lf = uTs[k*4 + 1], l2 = uTs[k*4 + 2], bl = uTs[k*4 + 3];   // limb and twig is a tapered, lit cylinder (twigs thinner than a pixel fade
  float cx = q4.x*gAsp, h = q4.y, s = q4.z, g = lipTop(cx) + .003;                              // by their width, so the bare crown is real twigs, not a haze); the foliage
  float wR = sp.x, hR = sp.y, cy = 1. - hR, pl = 1./(uRes.y*h);                                 // is leaf clumps hung on the outer branches, shaded as crown, limb mass, clump
  vec2 Q = vec2(P.x - cx, g - P.y)/h;                                       // tree units: height 1, y up from the root
  if (abs(Q.x) > wR*1.25 || Q.y < -.03 || Q.y > 1.10) return vec4(0.);
  vec3 Ld = normalize(vec3(uSun.x*gAsp - cx, g - h*(1. - hR) - uSun.y + .25, .55));   // toward the sun (or moon), y up
  float full = max(lf.w, bl.w*.92), fl = bl.w/max(lf.w + bl.w, .001), ov = ovcF();
  float nb = .62*noise(Q*20. + s*1.3) + .38*noise(Q*52. + s*2.1);          // T7: where leaves still cling along the twigs (irregular clusters, never round holes)
  float wA = 0., wLam = .4, wFine = 0., dLeaf = 9.; vec2 mc = vec2(0., cy);
  int n = k == 0 ? uTreeN.x : uTreeN.y;
  for (int i = 0; i < 400; i++) {
    if (i >= n) break;
    vec4 s0 = texelFetch(uTreeTex, ivec2(i*2, k), 0), s1 = texelFetch(uTreeTex, ivec2(i*2 + 1, k), 0);
    vec2 lo = min(s0.xy, s0.zw) - .08, hi = max(s0.xy, s0.zw) + .08;
    if (Q.x < lo.x || Q.y < lo.y || Q.x > hi.x || Q.y > hi.y) continue;
    vec2 pa = Q - s0.xy, ba = s0.zw - s0.xy; float t = clamp(dot(pa, ba)/max(dot(ba, ba), 1e-8), 0., 1.);
    vec2 dv = pa - ba*t; float d = length(dv), hw = .5*mix(s1.x, s1.y, t);
    if (s1.x < .0105) {                                                     // outer branches carry the leaves, each branch group within its dome
      vec2 gk = floor(s1.zw*53.);
      float fd = clamp(full + (hash(gk + s*1.9) - .5)*1.6*(1. - full), 0., 1.);   // T7: some branch groups thin (and go bare) before others
      float lrr = mix(.004, .082, pow(fd, 1.5))*(1. - (1. - fd)*.85*smoothstep(.30, .70, nb));   // a thinning crown falls back onto its twigs, in clusters
      float dl = max(d - lrr, length(Q - s1.zw) - (.20 + .08*hash(gk + s*.37))*mix(.55, 1., fd));
      if (dl < dLeaf) { dLeaf = dl; mc = s1.zw; }
    }
    float cov = clamp((max(hw, .5*pl) - d)/pl + .5, 0., 1.)*min(2.*hw/pl, 1.);   // sub-pixel twigs fade by their width
    if (cov > .002) {
      vec2 nr = normalize(vec2(-ba.y, ba.x)); float ac = clamp(dot(dv, nr)/max(hw, .5*pl), -1., 1.);   // across the branch: -1 .. 1
      float lam = clamp(.42 + .50*ac*sign(dot(nr, Ld.xy)) + .10*Ld.z, 0., 1.);   // a lit and a shaded side
      if (cov > wA*.9) { wLam = lam; wFine = 1. - smoothstep(.0015, .006, hw); }
      wA = 1. - (1. - wA)*(1. - cov);                                       // twigs add up into a fine mesh
    }
  }
  vec4 r = vec4(0.);
  if (wA > .005) {
    vec3 bark = mix(vec3(.46, .41, .37), vec3(.50, .35, .30), l2.w);
    vec3 twig = mix(vec3(.52, .40, .38), bark, .4);                         // the year's twigs are redder, and paler with the sky behind them
    vec3 bc = mix(bark, twig, wFine)*(.93 + .14*noise(P*uRes.y*.35));
    r = vec4(toS(tAir(tLight(snowy(bc, .3), wLam, shadow), .6, P.x)), wA*mix(1., .85, wFine));
  }
  if (full < .01 || dLeaf > .05) return r;
  // ---- foliage (T6): the crown is a cluster of leaf domes, one per branch group (its twigs, clipped to a dome around the group's centre), each
  // lit as a form with a soft painted ramp; the texture is round leaf clumps (bright tops, a soft crevice between them) that also scallop
  // the outline; a thinning crown loses whole clumps, chosen in clusters, so the gaps are round, not salt
  float Rm = .20 + .08*hash(floor(mc*53.) + s*.37);
  vec2 mq = (Q - mc)/Rm;
  float cs = h*uTst.x; vec4 wc = tWor(P/cs, s); vec2 cid = wc.zw, cp = (cid + .15 + .7*vec2(hash(cid + s), hash(cid + s + 17.3)))*cs;
  float c1 = 1. - wc.x, n4 = noise(P/(h*.007));
  float dS = dLeaf + .030*(.62 - c1) + .004*(n4 - .5);                    // clumps scallop the outline
  vec2 cq = vec2(cp.x - cx, g - cp.y)/h;                                    // the clump's centre in tree units
  float cov = 1. - smoothstep(-pl*1.2, pl*1.2, dS);                        // T7: no holes punched in a surface: thinning comes from the twigs (see the loop)
  if (cov < .01) return r;
  vec2 qc = (Q - vec2(0., cy))/vec2(wR, hR);
  vec3 nC = normalize(vec3(qc.x, qc.y, sqrt(max(0., 1. - min(dot(qc, qc), 1.))) + .2));   // the crown as one form
  vec3 nM = normalize(vec3(mq.x, mq.y, sqrt(max(0., 1. - min(dot(mq, mq), 1.))) + .10));   // this branch group's dome
  vec2 kq = (P - cp)/(cs*.62); vec3 nK = normalize(vec3(kq.x, -kq.y, sqrt(max(0., 1. - min(dot(kq, kq), 1.))) + .35));   // the clump
  vec3 N = normalize(nM*1.0 + nC*.40 + nK*.55);
  float lam = clamp(dot(N, Ld), 0., 1.);
  float tone = .55*smoothstep(.06, .40, lam) + .45*smoothstep(.48, .82, lam);   // a soft painted ramp: shade, mid, light
  float crev = smoothstep(.45, .95, wc.x/.62);                              // between clumps
  float ao = mix(.62, 1., clamp(.5 + mq.y*.65, 0., 1.))*mix(.78, 1., clamp(.5 + qc.y*.7, 0., 1.))*(1. - .22*crev);   // each dome's underside, the crown's underside
  float mh = hash(floor(mc*53.) + s), kh = hash(cid + 3.1);
  vec3 lc = mix(lf.rgb, l2.rgb, clamp(mh*.70 + kh*.25 + .20*dot(nC.xy, Ld.xy) - .05, 0., 1.))*(.95 + .10*kh);   // colour per group, a little per clump; autumn turns the sun side first
  float bm = step(.001, bl.w)*step(hash(cid + 7.7), fl);                   // blossom clumps among the leaf clumps
  vec3 alb = snowy(mix(lc, bl.rgb, bm), .8*smoothstep(.3, .8, N.y));
  vec3 L = tLight(alb, mix(.05, 1., tone), shadow)*ao*mix(.70, 1., tone);
  L += toL(alb)*toL(uSunC)*.20*smoothstep(.80, .95, lam)*(1. - crev)*(1. - ov)*(1. - uNight)*uShowSun;   // sun through the leaves on the brightest clumps
  L += toL(bl.rgb)*bm*.06*(1. - uNight)*(1. - ov);
  return tOver(r, toS(tAir(L, .6, P.x)), cov);
}
vec3 skyCol(vec3 rd, vec3 sd, vec2 P, vec2 FC, float veil, vec3 fogVeil, vec4 cl, out float skyMask){   // sky, cirrus and far clouds (sRGB, cl = the march, done once in main); skyMask = open sky
  float x = P.x, y = P.y;
  vec3 sk = skyBase(rd);                                                 // halo and stars are drawn live in COMPOSE (3.12): they switch with the hour, which a blend of baked hours cannot follow
  vec4 ci = cirrus(rd, sd);
  sk = sk*(1. - ci.a) + ci.rgb;
  vec3 lin = sk*cl.a + cl.rgb;
  lin = mix(lin, vec3(1.) - (vec3(1.) - .8)*exp(-(lin - .8)/.2), step(.8, lin));   // soft highlight shoulder
  vec3 col = toS(lin);
  skyMask = cl.a*(1. - ci.a*.6);
  if (uStyle > .5) { vec4 pt = painted(P, y, FC); float hz = smoothstep(HOR - .04, HOR, y); col = mix(pt.rgb, col, hz); skyMask = mix(1. - pt.a, skyMask, hz); }   // painted sky; the real haze takes over just above the horizon
  col = mix(col, lum(col)*vec3(.95, .96, 1.06), uGrey*.5);              // greyer weather leans cool, never neutral (reference)
  return mix(col, fogVeil, veil*(uFogD > 0. ? mix(.70, 1.20, fbm(vec2(x*2.4 + uT*.006, y*48.))) : 1.));   // 3.16: fog lies in drifting horizontal layers above the lake
}
void main(){
  vec2 FC = gl_FragCoord.xy + uOff - uMargin;                         // screen pixel; slices extend past the screen by the margin so they can slide
  float y = 1.0 - FC.y/uRes.y;
  vec2 P = vec2(FC.x/uRes.y, y);
  float aspect = uRes.x/uRes.y, x = P.x;
  gAsp = aspect;
  vec3 rd = rayAt(FC);
  vec3 sd = rayAt(vec2(uSun.x*uRes.x, (1. - uSun.y)*uRes.y));
  float jit = hash(FC*1.37 + 11. + uFrame*7.31);
  vec3 fogVeil = mix(uFog, vec3(1.), .35*(1. - uNight));
  float veil = clamp(uFogD*.9 + uRain*.5, 0., 1.)*smoothstep(HOR - .16, HOR, y)*mix(.85, .55, uFogD);   // 3.16: a softer band in fog (it read as a white wall)   // fog and rain veil the horizon

#ifndef LAND_ONLY
  if (uSlice < 1.5) {
    // Slices 0 and 1 share ONE call of the cloud march: every call site of clouds() is a full inlined copy of the march (and of the
    // cloud density inside it) for the shader compiler, and three copies took the D3D11 compile to ~6 min.
    vec3 C = vec3(0.), col = vec3(0.); float A = 0., tLo = uSplit, tHi = 70.; bool need = true;
    if (uSlice < .5) {
      // ---- slice 0: sky, cirrus, far clouds, far hills, the lake (opaque; alpha carries the open-sky mask for light shafts)
      // far layers over the water, then the sky where they leave it open
      float cvs[3]; float fd = y > HOR ? .03/max(y - HOR, 1e-4) : 0.;  // coverage per layer; the front surface's distance
      for (int i = 0; i < 3; i++) { cvs[i] = i < tN() && tSlice(i) == 0 ? tCover(i, x, y) : 0.; if (cvs[i] > .001) fd = tDist(i); }
      float sh = fd > 0. ? tShadow(x, fd) : 0.;                        // one cloud-shadow sample per pixel, at the front surface's distance
      if (y > HOR - 1./uRes.y) over(C, A, toS(tWater(rd, P, aspect)), smoothstep(HOR - .7/uRes.y, HOR + .7/uRes.y, y));
      for (int i = 0; i < 3; i++) if (cvs[i] > .001) over(C, A, toS(tColor(i, x, y, sh)), cvs[i]);
      need = A < .999;
    } else {
      // ---- slice 1: near clouds, premultiplied (they may sit in front of the far hills)
      if (rd.y <= .004 || uStyle > .5) { o = vec4(0.); return; }
      tLo = 0.; tHi = uSplit;                                            // T2: the march stops at the terrain, so a ridge hides the clouds behind it
      for (int i = 0; i < 3; i++) if (i < tN() && tCover(i, x, y) > .5) tHi = min(tHi, tDist(i));
    }
    vec4 cl = vec4(0., 0., 0., 1.);
    if (need) cl = clouds(rd, sd, sky(normalize(vec3(rd.x, .015, rd.z)), sd), jit, tLo, tHi);
    if (uSlice < .5) {
      float skyMask = 0.;
      if (need) col = skyCol(rd, sd, P, FC, veil, fogVeil, cl, skyMask);
      vec3 land = A > .001 ? landFinish(C/A, x, y, aspect) : vec3(0.); o = vec4(toL(max(col*(1. - A) + land*A, 0.)), skyMask*(1. - A));
      return;
    }
    float a = 1. - cl.a;
    vec3 S1 = cl.rgb;
    S1 = mix(S1, dot(S1, vec3(.2126, .7152, .0722))*vec3(.94, .96, 1.07), uGrey*.5);
    S1 = S1*(1. - veil) + toL(fogVeil)*veil*a;
    o = vec4(S1, a);
    return;
  }
#endif
#ifndef SKY_ONLY

  // ---- slices 2 (the lip's back row of grass) and 3 (the lip: turf, stones, front tufts, flowers, then the page meadow), premultiplied
  float top = lipTop(x);
  float bK = uSd.x > 0. ? smoothstep(.003, .010, top - HOR) : 0.;   // T3: where the shore meets open water (a beach, fewer grass tufts)
  float near = 1. - smoothstep(.0, .05, abs(y - LIP));                 // the lip's detail only exists near it
  vec3 C = vec3(0.); float A = 0.; vec2 M = vec2(0.);
  // T1: the near land's colours, lit, and the page's top colour lifted from them
  vec3 nAlb = grassAlb();
  vec3 gS = toS(tLight(snowy(nAlb, .85), .65, 0.));
  gPgTop = lift(gS, .80); vec3 tip = mix(gS*1.18 + .03, vec3(.97, .99, 1.), uSnow*.6);
  float u = x/aspect;
  // one cloud-density sample per pixel for slices 2 and 3, at one call site (each call site is an inlined copy of the density for the compiler):
  // slice 2 at the mid layer's distance, slice 3 on the ground under the lip and the page
  int mi = tN() - 1; float cv = uSlice < 2.5 && mi >= 0 && tSlice(mi) == 2 ? tCover(mi, x, y) : 0.;   // the one mid layer
  float gd = .03/max(y - HOR, .002), cloudy = uCover + uSheet;
  float shd = uSlice < 2.5 ? (cv > .001 && cloudy > 0. ? tDist(mi) : -1.) : (near > 0. ? gd : -1.);
  float dn = shd > 0. ? cDen(x, shd) : 0.;
  if (uSlice < 2.5) {
    if (cv > .001) { over(C, A, toS(tColor(mi, x, y, smoothstep(.0, .25, dn))), cv); overM(M, vec2(0.), cv); }
    if (near > 0. && uGr[0].x > 0.) {
      vec3 b = blades(P, .0026, uGr[0].y, 3., uGr[0].x*smoothstep(uGr[1].x, uGr[1].y, u)*(1. - .8*bK));
      if (b.x > .01) { vec3 bc = mix(gS*.70, tip, b.y)*(.92 + .16*b.z); over(C, A, toS(tAir(toL(bc), 1.2, x)), b.x); overM(M, vec2(1., 0.), b.x); }
    }
  } else {
    float shadow = near > 0. ? smoothstep(.0, .25, dn) : 0.;            // cloud shadows on the turf, cast by the volume
    float t = y - top;
    if (t > 0.) {
      float e = .004, sl = (lipTop(x + e) - lipTop(x - e))/(2.*e), lam = tLam(sl, x, top);
      vec3 a = snowy(nAlb, .85);
      float g1 = noise(P*vec2(620., 330.)), g2 = noise(P*vec2(1500., 800.)), bl = noise(vec2(x*720., y*60.))*.6 + noise(vec2(x*1300., y*110.))*.4;
      a *= .86 + .16*g1 + .08*g2 + .05*bl;
      if (uSd.x > 0.) {                                                 // T3: a sand beach where the shore meets open water, a fringe of bare earth behind it, worn earth patches, litter under the trees
        vec3 soil = vec3(.56, .43, .31);
        float bw = uSd.x*bK*smoothstep(.42, .62, noise(vec2(x*7.5, 5.)) + .25*noise(vec2(x*40., 2.)));   // beaches in coves, grass down to the water between them
        float sand = 1. - smoothstep(bw - .0008, bw + .0008, t), wet = 1. - smoothstep(.0005, .0015, t);
        vec3 sa = mix(uTk[7], uTk[7]*vec3(.72, .69, .66), wet)*(.95 + .08*noise(vec2(x*900., y*2400.)));
        float earth = .55*smoothstep(.0006, .002, bw)*smoothstep(bw, bw + .0010, t)*(1. - smoothstep(bw + .0008, bw + .0026, t))*smoothstep(.3, .6, noise(vec2(x*160., 2.)));   // a broken fringe of earth where the turf ends
        earth = max(earth, uSd.y*smoothstep(.62, .72, noise(P*vec2(70., 210.))*.8 + noise(P*vec2(300., 700.))*.2 + .08*uDry));
        vec3 fC = soil; float fA = 0.;
        for (int k = 0; k < 2; k++) if (uTr[k].w > .5) {
          float cx = uTr[k].x*gAsp, h = uTr[k].y, g = lipTop(cx), ap = exp(-pow((x - cx)/(h*.42), 2.) - pow((y - g - .0045)/.0045, 2.));
          vec4 lf = uTs[k*4 + 1], l2 = uTs[k*4 + 2], bl = uTs[k*4 + 3];
          earth = max(earth, ap*.65*uSd.z);
          float fall = ap*uSd.z*smoothstep(.55, .70, noise(P*vec2(1100., 2600.)));   // fallen leaves in autumn, petals after the bloom
          float la = 1. - lf.w, pb = smoothstep(.2, .6, bl.w);
          fC = mix(mix(soil*.8, l2.rgb, smoothstep(.05, .3, lf.w)), bl.rgb, pb); fA = max(fA, fall*max(la, pb));
        }
        a = mix(a, soil*(.88 + .2*noise(P*vec2(800., 1900.))), clamp(earth, 0., 1.)*(1. - uSnow*.7));
        a = mix(a, fC, fA*(1. - uSnow*.8));
        a = mix(a, snowy(sa, .6), sand);
      }
      vec3 L = tLight(a, lam*mix(1., .85, smoothstep(.0, .03, t)), shadow);
      L *= 1. + .30*exp(-t*300.)*lam*(1. - ovcF());                     // crest light
      float cs = 0.;                                                    // T2: the trees' contact shadow on the turf, cast away from the sun
      for (int k = 0; k < 2; k++) if (uTr[k].w > .5) { float cx = uTr[k].x*gAsp, h = uTr[k].y, g = lipTop(cx), sx = cx - sign(uSun.x*gAsp - cx)*h*.30; cs = max(cs, exp(-pow((x - sx)/(h*.55), 2.) - pow((y - g - .0025)/.0030, 2.))); }
      L *= 1. - cs*(.12 + .30*(1. - ovcF()));
      L = tAir(L, mix(1.2, .3, smoothstep(.0, .04, t)), x);
      float pm = smoothstep(min(max(top + .004, .398), .412), .419, y);   // the page is fully in by the first card (.42): nothing under the glass darker than #86B9A0
      vec3 sc = mix(toS(L), page(x, y), pm);
      over(C, A, sc, 1.); overM(M, vec2(1. - pm, 0.), 1.);
    }
    for (int k = 0; k < 2; k++) { if (uTr[k].w < .5) continue; vec4 tr = tTree(P, k, shadow); if (tr.a > .01) { over(C, A, tr.rgb, tr.a); overM(M, vec2(0., 1.), tr.a); gNear = tr.a + gNear*(1. - tr.a); } }   // T2/T3: trees on the lip
    if (near > 0. && uGr[0].z > 0.) {
      vec3 b = blades(P, .0032, uGr[0].w, 7., uGr[0].z*smoothstep(uGr[1].x, uGr[1].y, u)*(1. - .8*bK));   // grass rows (loch: taller reeds at the shore)
      if (b.x > .01) { over(C, A, mix(gS*.58, tip, b.y)*(.90 + .20*b.z)*(1. - .06*shadow), b.x); overM(M, vec2(1., 0.), b.x); gNear *= 1. - b.x; }
    }
  }
  if (A < .001) { o = vec4(0.); o2 = vec4(0.); return; }
  vec3 L = landFinish(C/A, x, y, aspect);
  o = vec4(toL(max(L, 0.))*A, A);
  o2 = vec4(M, 0., A);
#else
  o = vec4(0.); o2 = vec4(0.);
#endif
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
uniform float uSnowF, uBolt; uniform vec3 uSnowCol;                                            // 3.15: falling snow (0..1), lightning (review visual only: D2 keeps storms out of the body mapping)
out vec4 o;
float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x*p.y); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); vec2 u = f*f*(3. - 2.*f);
  return mix(mix(hash(i), hash(i + vec2(1., 0.)), u.x), mix(hash(i + vec2(0., 1.)), hash(i + vec2(1., 1.)), u.x), u.y); }
float noiseAA(vec2 p, vec2 fw){ float f = max(fw.x, fw.y); return f > .45 ? .5 : mix(noise(p), .5, smoothstep(.15, .45, f)); }   // fw: cells per px; fades to its mean once finer than ~3 px, so the receding ground does not shimmer
// ---- wind. Gusts roll across the meadow the way the low clouds drift (to the left and toward the eye). Grass bending in a gust
// shows its paler side, so a gust reads as a lighter patch travelling over the field, with fine ripples running through it;
// tree crowns silver as their leaves turn. Runs per frame here, on the cached slices: the scene itself is not re-rendered.
const float HOR = .37, FOC = .64;   // as the scene shader's camera (3.15)
float hgN(float c, float g){ float g2 = g*g; return (1. - g2)/pow(1. + g2 - 2.*g*c, 1.5); }   // 4pi * Henyey-Greenstein
const vec2 WD = vec2(-.958, -.287);                                     // ground wind direction (x right, z away), as the clouds' wind()
vec2 ground(vec2 q){                                                    // screen px -> ground point, the land slices' projection
  float gd = .03/max((1. - q.y/uRes.y) - HOR, .002);                    // the land slices' projection (3.15)
  return vec2((q.x - .5*uRes.x)/uRes.y*gd/FOC, gd);
}
vec3 windAt(vec2 q){                                                    // x gust 0..1, y ripple -1..1, z leaf flicker 0..1
  vec2 G = ground(q), a = vec2(dot(G, WD), dot(G, vec2(-WD.y, WD.x)));   // along and across the wind
  // ground units per screen px (analytic: derivatives are undefined in this branchy code): across dx, and down the screen dz
  float dx = G.y/uRes.y/FOC, dz = G.y*G.y/.03/uRes.y, gx = G.x/G.y;
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
  float y0 = (1. - (.285 + .03*hash(vec2(cyc, 9.))))*uRes.y,   // the lane between the hero's detail line (~.27) and the horizon: never behind the hero number (3.15)
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
  vec2 uv = vec2((P.x + P.y*.18)*s, P.y*s*.06 - uT*sp);                // ~10 degree slant (3.15, was ~6); cells tall and thin
  vec2 id = floor(uv), f = fract(uv);
  if (hash(id + seed) > .42) return 0.;
  float xo = .15 + .7*hash(id + seed + 7.), yo = .5*hash(id + seed + 13.);
  float line = 1. - smoothstep(0., s/uRes.y*1.1, abs(f.x - xo));       // about one pixel wide
  return line*smoothstep(yo, yo + .12, f.y)*(1. - smoothstep(yo + .30, yo + .48, f.y));
}
// snow: one flake per cell; r and blur in px. Near flakes are large and soft (out of focus), far ones small and sharp: depth of field
float snowAt(vec2 P, float cs, float r, float blur, float sp, float seed){
  vec2 uv = P/cs + vec2(uT*.06*(1. + seed*.1), -uT*sp);                  // slow fall, a little drift with the wind
  vec2 id = floor(uv), f = fract(uv);
  if (hash(id + seed) > .55) return 0.;
  vec2 c = vec2(.15 + .7*hash(id + seed + 3.) + .12*sin(uT*.7 + hash(id)*6.), .15 + .7*hash(id + seed + 7.));   // each flake sways
  float d = length(f - c)*cs*uRes.y;
  float rr = r*(.7 + .6*hash(id + seed + 11.))*uDpr, b = blur*uDpr;
  return (1. - smoothstep(rr - b*.5, rr + b*.5 + .6, d))*mix(1., .55, smoothstep(1., 5., blur));
}
// a branching lightning bolt in the upper sky: jagged segments, a thin white core with a soft glow
float segD(vec2 p, vec2 a, vec2 b){ vec2 pa = p - a, ba = b - a; return length(pa - ba*clamp(dot(pa, ba)/dot(ba, ba), 0., 1.)); }
float bolt(vec2 P, float asp){
  if (P.y > .36 || abs(P.x - asp*.30) > .16) return 0.;                 // cull: the bolt lives in a box in the upper sky (phone cost)
  float d = 1e3, d2 = 1e3; vec2 a = vec2(asp*.30, -.01), br = vec2(0.);
  for (int i = 0; i < 18; i++) {                                           // short jagged steps down to ~30% of the screen
    vec2 b = a + vec2((hash(vec2(float(i), 3.1)) - .5)*.030, .010 + .009*hash(vec2(float(i), 5.7)));
    d = min(d, segD(P, a, b)); if (i == 7) br = b; a = b;
  }
  a = br;
  for (int i = 0; i < 9; i++) { vec2 b = a + vec2(.007 + (hash(vec2(float(i), 9.3)) - .35)*.022, .009 + .005*hash(vec2(float(i), 2.9))); d2 = min(d2, segD(P, a, b)); a = b; }
  float px = 1./uRes.y;
  return (1. - smoothstep(.6*px*uDpr, 1.6*px*uDpr, d)) + .55*(1. - smoothstep(.4*px*uDpr, 1.2*px*uDpr, d2)) + .21*exp(-d/.012) + .15*exp(-d2/.008);
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
    float cc = dot(rd, sd), hz = 1. - uVeil*smoothstep(HOR - .16, HOR, P.y)*.85;
    c += uHalo*(.0035*hgN(cc, .90) + .004*hgN(cc, .45))*s0.a*hz;
    if (uStars > .001) {
      vec2 g = floor(P*300.); float h = hash(g);
      if (h > .9968) {                                                  // one star in ~300 cells: a soft point at the cell centre. G3: low intensity, as in the reference (full-strength stars read L* 70-78 under the sky text)
        float d = length(P - (g + .5)/300.)*uRes.y;
        float tw = .55 + .45*sin(uT*.8 + h*40.);
        c += vec3(.70, .79, 1.)*.30*(1. - smoothstep(.5*uDpr, 1.3*uDpr, d))*tw*(1. - smoothstep(.05, HOR, P.y))*uStars*s0.a;
      }
    }
  }
  if (uShowSun > .001) {                                                // sun or moon disc, behind the clouds of slice 0 through its open-sky mask
    vec2 q0 = px + uO0, P = vec2(q0.x/uRes.y, 1. - q0.y/uRes.y), S = vec2(uSun.x*uRes.x/uRes.y, uSun.y);
    float disc = 1. - smoothstep(uSunR*.82, uSunR, length(P - S));
    if (uNight > .5) disc *= smoothstep(uSunR*.55, uSunR*.95, length(P - S - vec2(uSunR*.45, -uSunR*.35)));   // crescent
    disc *= 1. - uVeil*smoothstep(HOR - .16, HOR, P.y)*.85;                    // fog and rain veil the horizon
    c = mix(c, uSunDisc, disc*uShowSun*s0.a);
  }
  c = c*(1. - s1.a) + s1.rgb;
  if (uBirds > 0.) c = mix(c, uBirdCol, birds(px + uO1*1.2)*uBirds*.85);   // in front of the clouds, behind the land
  c = c*(1. - s2.a) + s2.rgb; c = c*(1. - s3.a) + s3.rgb;
  float mask = s0.a*(1. - s1.a)*(1. - s2.a)*(1. - s3.a);
  if (uRain > 0.) {
    vec2 P = vec2(px.x/uRes.y, 1. - px.y/uRes.y);
    float r = rainAt(P, 70., 9., 1.)*.38 + rainAt(P, 130., 7., 5.)*.28 + rainAt(P, 230., 5., 9.)*.20;   // three depths: near longer and brighter (opacity ~15-35%)
    if (uRain > .95) r += rainAt(P, 100., 8., 13.)*.24;                    // a storm: denser streaks
    c = mix(c, uRainCol, clamp(r*uRain, 0., .6));
  }
  if (uSnowF > 0.) {
    vec2 P = vec2(px.x/uRes.y, 1. - px.y/uRes.y);
    float s = snowAt(P, .085, 4.5, 5.5, .030, 1.)*.75                     // near: large, soft (depth of field)
            + snowAt(P, .045, 2.0, 1.6, .022, 5.)*.85                     // middle
            + snowAt(P, .022, .9, .3, .014, 9.);                          // far: small and sharp
    c = mix(c, uSnowCol, clamp(s, 0., 1.)*uSnowF);                     // G3: white by day; at night lit like the cloud's moonlit side (pure white flakes read L* 96 on an L* 41 sky)
  }
  if (uBolt > 0.) {
    vec2 P = vec2(px.x/uRes.y, 1. - px.y/uRes.y);
    float on = uT < 41. ? 1. : step(fract(uT/9.), .025) + .5*step(abs(fract(uT/9.) - .045), .008);   // still frames show it; live, a flash every ~9 s with a flicker
    c += vec3(.92, .95, 1.)*bolt(P, uRes.x/uRes.y)*on*uBolt*1.6;
    c += vec3(.30, .33, .40)*on*uBolt*.18*mask;                          // the sky flashes with it
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
uniform vec2 uSunUV; uniform float uFlare, uRimK, uGhost;   // 3.15: sun optics (starburst, lens ghosts, silver rim), scaled by how much of the sun is in open sky
const float HOR = .37;   // screen horizon (FINAL uv is bottom-up)
out vec4 o;
float msk(vec2 q){ return texture(uScene, q).a; }                  // open-sky mask: 1 where the sky is seen, 0 behind cloud or land
vec3 sunOptics(vec2 uv){
  float asp = uRes.x/uRes.y, px = 1./uRes.y;
  float vis = 0.;                                                   // how much of the disc is unobstructed: 9 taps over the disc
  for (int i = 0; i < 9; i++) { float a = float(i)*.6981; vis += msk(uSunUV + vec2(cos(a), sin(a)*asp)*.012*float(i > 0)); }
  vis /= 9.;
  vec2 d = (uv - uSunUV)*vec2(asp, 1.); float r = length(d) + 1e-5, ang = atan(d.y, d.x + 1e-6);
  // starburst: 10 thin diffraction spikes, long and short in turn, slowly turning with the hour; tapers with distance
  float tw = .02*sin(uT*.05);
  float sp = pow(abs(cos(ang*5. + tw)), 600.)*exp(-r*24.) + .22*pow(abs(cos(ang*5. + 1.5708 + tw)), 700.)*exp(-r*45.);   // 10 spikes reach ~12% of the screen height; 10 faint short ones between
  sp *= smoothstep(.004, .02, r);                                   // the disc and bloom own the centre
  vec3 c = vec3(1., .98, .95)*sp*1.1*vis;
  // lens ghosts on the line from the sun through the screen centre: faint, soft, slightly tinted
  vec2 cen = vec2(.5), axis = cen - uSunUV;
  for (int k = 0; k < 3; k++) {
    float t = k == 0 ? 1.35 : k == 1 ? 1.75 : 2.25, rad = k == 0 ? .035 : k == 1 ? .060 : .022;
    vec3 tint = k == 0 ? vec3(.80, .95, 1.) : k == 1 ? vec3(1., .95, .85) : vec3(.85, 1., .90);
    float g = length((uv - (uSunUV + axis*t))*vec2(asp, 1.));
    c += tint*(smoothstep(rad, rad*.55, g)*.55 + smoothstep(rad*.12, 0., abs(g - rad*.92))*.45)*.030*vis*uGhost;   // 3.16: no ghosts from the moon (they showed as rings on the page)
  }
  // silver rim: when the sun is behind a cloud, the cloud's edge near it glows white
  if (r > .25) return c*uFlare;                                     // cull: the rim only exists near the sun (phone cost)
  vec2 e = vec2(1.5*px/asp, 1.5*px);
  float edge = length(vec2(msk(uv + vec2(e.x, 0.)) - msk(uv - vec2(e.x, 0.)), msk(uv + vec2(0., e.y)) - msk(uv - vec2(0., e.y))));
  c += vec3(1., .99, .97)*clamp(edge*2.5, 0., 1.)*exp(-r*11.)*(1. - vis)*uRimK*step(1. - HOR + .085, uv.y);   // 3.16: only a hidden sun lights a rim, and only on clouds (not the hill line). T2: above the highest ridge (y .285), terrains rise to .30
  return c*uFlare;
}
float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x*p.y); }
void main(){
  vec2 uv = gl_FragCoord.xy/uRes;
  vec3 c = texture(uScene, uv).rgb;
  c += (texture(uB1, uv).rgb*.65 + texture(uB2, uv).rgb*.35)*uBloom;
  if (uRays > 0.) c += uSunCol*texture(uR, uv).r*uRays;          // light shafts, marched at quarter resolution (RAYS)
  if (uFlare > 0.) c += uSunCol*sunOptics(uv);                    // 3.15: the starburst does the work, shafts stay subtle
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
  // 3.15: sun discs sunR .017 (~7% of the width, was 13%): the starburst in FINAL carries the sun, as in the reference.
  // Day sky sampled from the Samsung Weather reference. key = direction the clouds are lit from (x right, y up, z into the scene).
  const PAL = {
    dawn: { zen: '#7FAACB', mid: '#A3BCC8', hor: '#DCC6BA', sunC: '#FFF0DC', sun: [.20, .30], sunR: .017, cl: '#FFEFE2', cd: '#A9AFC0', fog: '#E3DCD8', path: '#E6D6BC',
      h: ['#A9C4A2', '#9FC48E', '#A2C78C', '#A6CB8C'], tree: '#4F6E57', hill: '#B3BCCF', water: '#7CC3E4', pg: ['#A9D2A0', '#98D0C0', '#98CBE6', '#88BBEE'], night: 0, I: 1.15, key: [-.75, .30, .35],
      g: { gain: [1.03, 1, .97], lift: [0, .006, .03], sat: 1.06, con: .05, rays: .45, bloom: .45 } },
    day: { zen: '#4F9EEE', mid: '#7DBBF3', hor: '#ACD7F7', sunC: '#FFF8EA', sun: [.64, .15], sunR: .017, cl: '#FFFDF8', cd: '#A9C2DA', fog: '#C8E2F4', path: '#E4DCC2',
      h: ['#9CC79A', '#92C57C', '#97CA79', '#9DCF7B'], tree: '#55895C', hill: '#86AED3', water: '#52B6E4', pg: ['#A1D29C', '#8ECFBE', '#8DC8E8', '#82B7EE'], night: 0, I: 1.25, key: [.45, .80, -.35],
      g: { gain: [1.0, 1, 1.0], lift: [0, .004, .012], sat: 1.08, con: .05, rays: .18, bloom: .30 } },
    dusk: { zen: '#8C86D6', mid: '#9D8DDA', hor: '#C6A0E4', sunC: '#FFF6F2', sun: [.86, .35], sunR: .017, cl: '#F6DDF0', cd: '#6E66A6', fog: '#A797CC', path: '#C9A88A',
      h: ['#7F8F8C', '#6D8466', '#627C5A', '#5B7654'], tree: '#33473A', hill: '#8F87B8', water: '#5677AC', pg: ['#7E9A8E', '#7891A8', '#7F86BC', '#7A7BC0'], night: 0, I: 1.1, key: [.75, .22, .35],
      g: { gain: [1.0, .98, 1.02], lift: [.01, 0, .03], sat: 1.06, con: .06, rays: .45, bloom: .50 } },   // 3.15: lavender with a white sun, after the reference (was orange)
    night: { zen: '#081850', mid: '#0D266A', hor: '#1A4AA2', sunC: '#EEF2FA', sun: [.78, .12], sunR: .022, cl: '#6F8CC6', cd: '#14295E', fog: '#203A72', path: '#2E3E50',
      h: ['#1E3462', '#1C325C', '#1A2F56', '#182B50'], tree: '#0B1724', hill: '#182F66', water: '#161C62', pg: ['#142B58', '#122A64', '#10286A', '#0E246C'], night: 1, I: .26, key: [.35, .85, -.25],
      g: { gain: [.97, .98, 1.02], lift: [0, .002, .010], sat: 1.30, con: .04, rays: .04, bloom: .22 } },   // 3.16: ~12 L* darker, toward the sampled reference; turf leans blue   // 3.15: deep ultramarine, brighter toward the horizon (was near-black slate)
  };
  // Hour of day: palettes are keyframes, blended in linear light, so the scene moves smoothly through the day.
  const KEYS = [[0, 'night'], [5, 'night'], [6.5, 'dawn'], [9, 'day'], [16.5, 'day'], [19, 'dusk'], [21, 'night'], [24, 'night']];
  const SUN = [[5.5, [.10, .43]], [6.5, [.13, .355]], [8.5, [.22, .20]], [13, [.64, .15]], [16.5, [.80, .17]], [19, [.86, .35]], [20, [.90, .43]]];   // screen x, y from the top; horizon at .37 (3.15: was .58). Midday sun high, right of centre, as in the reference
  const TWI = +(new URLSearchParams(typeof location !== 'undefined' ? location.search : '').get('twi') ?? .9);   // G3: twilight dip of the key light (0 = the old linear fade)
  const MOON = [.78, .12], HOURS = { dawn: 6.5, day: 13, dusk: 19, night: 23 };
  const sstep = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
  const toHex = a => '#' + a.map(c => Math.round(Math.min(1, Math.max(0, c)) ** (1 / 2.2) * 255).toString(16).padStart(2, '0')).join('');
  const mixV = (a, b, f) => a === b ? a : typeof a === 'number' ? a + (b - a) * f
    : typeof a === 'string' ? toHex(hex(a).map((c, i) => c ** 2.2 + (hex(b)[i] ** 2.2 - c ** 2.2) * f))
    : Array.isArray(a) ? a.map((x, i) => mixV(x, b[i], f)) : Object.fromEntries(Object.keys(a).map(k => [k, mixV(a[k], b[k], f)]));
  const seg = (T, h, get) => { if (h <= T[0][0]) return get(T[0]); for (let i = 1; i < T.length; i++) if (h <= T[i][0]) return mixV(get(T[i - 1]), get(T[i]), sstep(T[i - 1][0], T[i][0], h)); return get(T[T.length - 1]); };
  const palAt = h => seg(KEYS, ((h % 24) + 24) % 24, k => PAL[k[1]]);
  const sunAt = h => seg(SUN, ((h % 24) + 24) % 24, k => k[1]);

  // readiness drives the weather: fair-weather cumulus when ready, taller and greyer as readiness falls, low flat cover when poor
  // SkyState: everything the sky can be. Readiness picks the low-cloud regime (PLAN-sky.md section 4); sleep, HRV, activity and illness add layers in Round 3.8.
  const SKY0 = { style: 0, paintCov: .5, cirrus: .50, ciHook: .6, ciVeil: 0, ciAng: .95, cover: .30, tall: .62, grey: 0, haze: 0, sheet: 0, sType: 0, sb: 1.0, st: 1.7, fog: 0, fogH: 0, rain: 0, wind: .45 };   // wind: breeze over the meadow, 0 calm .. 1 gusty
  const weather = r => Object.assign({}, SKY0, r == null ? { cover: .52, tall: .60, grey: .05, haze: .05 }
    : r >= 80 ? {}                                                                                   // cirrus with a few fair-weather cumulus
    : r >= 65 ? { cover: .50, tall: .75, grey: .06, haze: .04, wind: .60 }                           // partly cloudy
    : r >= 50 ? { cover: .30, tall: .40, grey: .20, haze: .08, sheet: .75, sType: .30, sb: 1.0, st: 1.75, wind: .65 }   // stratocumulus
    : { cover: .15, tall: .35, grey: .42, haze: .15, sheet: 1, sType: 1, sb: .55, st: 1.25, wind: .50 });   // low stratus
  // Weather colours (3.15), sampled from the reference ramps: blended over the hour's palette by palK (and much less at night).
  const PAL_OVC = { zen: '#C3D6E4', mid: '#B0C8D8', hor: '#8EB0C8', cl: '#FFFFFF', cd: '#9DB4C8', fog: '#B7CAD8', hill: '#6E92AC', water: '#5E97BC', h: ['#88A99C', '#84A790', '#88AB92', '#8CAF96'], pg: ['#A6C9B4', '#9CC6C8', '#9DC0DC', '#9AB8E2'] };
  const PAL_RAIN = { zen: '#9497BE', mid: '#8F92B9', hor: '#B39CD2', cl: '#E8E6F4', cd: '#7779A6', fog: '#ABA7CC', hill: '#6D7199', water: '#5B6F9A', h: ['#6F8F8A', '#6A8C82', '#6E9186', '#73968C'], pg: ['#9EBFB4', '#99BAC4', '#9EB4D4', '#9CADDE'] };
  const PAL_STORM = { zen: '#97A6BE', mid: '#919CAF', hor: '#66779A', cl: '#DCE2EC', cd: '#5C6984', fog: '#8E9AB2', hill: '#42536E', water: '#3F5677', h: ['#6E8A88', '#6A867E', '#6E8A82', '#728E86'], pg: ['#9CB8B0', '#98B4C0', '#9AAED0', '#98A8DA'] };
  // 3.16: morning fog after the reference's 'patches of fog' (07:38): steel-blue sky darkening upward, a bright milky band on the horizon,
  // hills and lake nearly gone, meadow desaturated. Lighter than the reference at the top so dark ink on the sky keeps AA.
  const PAL_FOG = { zen: '#7D9AB7', mid: '#9BB3C6', hor: '#B9CBD6', cl: '#E9EEF2', cd: '#A7B8C6', fog: '#C3D2DC', hill: '#8DA6BA', water: '#93B8CC', h: ['#94BE9C', '#8FBC92', '#93BF96', '#97C29A'], pg: ['#A6C2B4', '#9FBDC2', '#9CB6CE', '#97AED6'] };
  const PAL_SNOW = { zen: '#A9CDD8', mid: '#84AFC7', hor: '#A8C9DF', cl: '#FFFFFF', cd: '#A0BCD0', fog: '#C9DDE9', hill: '#BACFDD', water: '#7FA8C6', h: ['#C9DCE4', '#D4E4EA', '#DCE8EE', '#E2EDF2'], pg: ['#CFE3EC', '#BED8E8', '#ACCCE6', '#9FC2E8'] };
  const PRESETS = {
    painted: { style: 1, paintCov: .72, cover: 0, cirrus: 0 }, paintedClear: { style: 1, paintCov: .25, cover: 0, cirrus: 0 }, paintedCloudy: { style: 1, paintCov: 1, cover: 0, cirrus: 0 },
    fair: {}, partly: { cover: .50, tall: .75, grey: .06, haze: .04, wind: .60 },
    cirrusFibratus: { cirrus: .70, ciHook: 0, cover: 0 }, cirrusUncinus: { cirrus: .62, ciHook: 1, cover: 0, ciAng: 1.2 },
    cirrusDense: { cirrus: .92, ciHook: .3, cover: 0, ciAng: .7 }, cirrostratus: { cirrus: .95, ciVeil: 1, ciHook: 0, cover: 0, haze: .05 },
    stratocumulus: { cover: .30, tall: .40, grey: .20, haze: .08, sheet: .75, sType: .30, sb: 1.0, st: 1.75, wind: .65 },
    stratus: { cover: .15, tall: .35, grey: .42, haze: .15, sheet: 1, sType: 1, sb: .55, st: 1.25, wind: .50 },
    valleyFog: { cover: 0, cirrus: 0, haze: .10, fog: .90, fogH: .25, wind: .05, pal: PAL_FOG, palK: .85 }, mist: { cover: .40, fog: .55, fogH: .55, haze: .05, wind: .15 },
    rain: { cover: .10, tall: .30, grey: .30, haze: .12, sheet: 1, sType: 1, sb: .50, st: 1.4, fog: .30, fogH: .35, rain: .85, wind: .90, pal: PAL_RAIN, palK: .85 },
    // 3.15 review states. Thunder is a visual for the board only: D2 keeps storms out of the body mapping.
    thunder: { cover: .10, tall: .30, grey: .30, haze: .14, sheet: 1, sType: 1, sb: .45, st: 1.6, fog: .25, fogH: .30, rain: 1, wind: 1, bolt: 1, pal: PAL_STORM, palK: .9 },
    snow: { cover: .10, tall: .30, grey: .15, haze: .10, sheet: 1, sType: .85, sb: .60, st: 1.5, fog: .20, fogH: .30, snow: 1, wind: .25, pal: PAL_SNOW, palK: .9 },
  };
  PRESETS.stratus = Object.assign({}, PRESETS.stratus, { grey: .25, pal: PAL_OVC, palK: .8 });
  // The land (terrain lock, 2026-11-22: the open calm loch of Round T3, maple trees, fine foliage, a flat page under the cards).
  // Tokens are sRGB albedo (local colour before light and air); the shader lights them with the hour's sun and sky and pushes each
  // layer into the haze by its distance, so weather and hour need no extra colours.
  //   0 mountain, 1 mid moor, 2 near wood, 3 grass green, 4 grass straw, 5 lake deep, 6 lake near, 7 sand
  // Shapes, as numbers (the shader is branch-free, see tCrest). Per far layer:
  //   crest: [y0, a0, a1, c1,  w1, a2, c2, w2,  rise, s0, s1, cosMod,  cosF, phase, end0, end1,  rdgAmp, rdgF, seed, noiseAmp,  noiseF, -, -, -]
  //          y = y0 - (a0 + a1*bump(u,c1,w1) + a2*bump(u,c2,w2) + rise*smoothstep(s0,s1,u))*(1 - m + m*cos(u*cosF + phase))*smoothstep(end0,end1,u) - detail
  //   props: [dist km, foot y (>1 none), slice, mist,  -, -, -, token,  grassMix, -, -, rockBand]
  // lip: [y0, rise, s0, s1,  bumpAmp, bumpU, bumpW, swellAmp,  swellF, swellSeed, fineK, ground (0 none, 1 water, 2 plane)]
  const H = .37, NO = [0, 0, -10, -9];                                      // HOR; NO: no headland end (smoothstep(-10, -9, u) = 1)
  // T3: the high mountain far off (55 km, ~7% of the screen high, centre-left between the swells); mtn: [peak height, -, rock on steep faces, -] (snow comes from the season)
  const MTN = { crest: [H, 0, .046, .25, .37, .018, .40, .07, 0, 0, 1, .14, 34, .9, ...NO.slice(2), .016, 26, 2.7, .0014, 240, 0, 0, 0], props: [38, H + .0004, 0, .15, 0, -10, 0, 0, 0, 0, 0, 0], mtn: [.074, 0, .6, 0] };   // T5: a range from the left edge to ~u .66 (twice the T4 span), a little taller
  const LAND = { lip: [.404, 0, 0, 1, -.014, 0, .16, .006, 2.0, 4, 1, 1], gr: [.40, .008, .50, .014], trees: [[.865, .108, 3, 1], [.655, .052, 7, 1]], species: 'maple', shore: [.0055, .20, 1, 0], layers: [   // the open loch: wide still water, long low ranges, the mountain beyond, land rising at the left
    MTN,
    { crest: [H, .004, .018, .72, .42, .006, .22, .12, 0, 0, 1, 0, ...NO, .004, 6, 1.3, .0008, 150, 0, 0, 0], props: [26, H + .0004, 0, .18, 0, -10, 0, 1, .2, 0, 0, 0] },
    { crest: [H, .0015, .012, .12, .22, 0, 0, 1, 0, 0, 1, 0, ...NO, .003, 9, 4.1, .0008, 170, 0, 0, 0], props: [11, H + .0004, 0, .18, 0, -10, 0, 1, .35, 0, 0, 1] }] };

  const TK = ['#5E7078', '#5C7A50', '#3A5A3C', '#7DA556', '#B79C5A', '#2D6C93', '#3C88A8', '#D9C7A0'];

  // Season, Mediterranean year: green winter and spring, straw by July, first rains green it again from November. Day of year -> dryness 0..1.
  const DRY = [[0, .05], [59, .05], [105, .2], [135, .45], [166, .8], [196, 1], [258, 1], [288, .85], [319, .45], [349, .15], [366, .05]];
  const dryAt = (date = new Date()) => { const d = (date - new Date(date.getFullYear(), 0, 1)) / 864e5; for (let i = 1; i < DRY.length; i++) if (d <= DRY[i][0]) return DRY[i - 1][1] + (DRY[i][1] - DRY[i - 1][1]) * (d - DRY[i - 1][0]) / (DRY[i][0] - DRY[i - 1][0]); return .05; };

  // Round T3: near trees. Shape [crown half-width / height, crown half-height / height, fork height / height, tiers 0..1], bark redness, bloom colour,
  // and a year (day of year -> leaf colour, second leaf colour, crown fullness, bloom amount) for a Mediterranean lake shore at ~40 N.
  const LEAF = [.040, .20, 1.0, .17];   // foliage texture (T4 fine: clump cell, lobes, edge break-up, mass cell; fractions of the tree's height)
  const BARE = '#6B5B45';
  const SPECIES = {
    maple:   { name: 'Maple (sugar-maple habit; Italian / Montpellier maple)', shape: [.44, .40, .24, 0], bark: 0, bloom: '#D8D890', year: [   // round dense crown; yellow, orange, then red in November
      [0, BARE, BARE, 0, 0], [95, BARE, BARE, 0, 0], [108, '#A6C860', '#BCD26A', .55, 0], [125, '#5E8C3E', '#6E9A44', .95, 0], [272, '#4E7A36', '#5C8A3C', 1, 0],
      [292, '#8E9A3A', '#C9A23A', 1, 0], [306, '#E2B23A', '#E08A2E', .97, 0], [318, '#E27A2C', '#D2482A', .92, 0], [328, '#CC3F2C', '#E2862E', .72, 0], [338, '#A8422E', '#B86A30', .3, 0], [348, BARE, BARE, 0, 0], [366, BARE, BARE, 0, 0]] },
  };
  const hx2 = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255);
  function phen(sp, doy) {   // -> [shape vec4, leaf rgb + fullness, leaf2 rgb + bark, bloom rgb + amount]
    const S = SPECIES[sp] || SPECIES.maple, Y = S.year; let i = 1; while (i < Y.length - 1 && doy > Y[i][0]) i++;
    const a = Y[i - 1], b = Y[i], f = Math.min(1, Math.max(0, (doy - a[0]) / Math.max(1, b[0] - a[0]))), L = (p, q) => p + (q - p) * f, C = (p, q) => hx2(p).map((v, k) => L(v, hx2(q)[k]));
    return [...S.shape, ...C(a[1], b[1]), L(a[3], b[3]), ...C(a[2], b[2]), S.bark, ...hx2(S.bloom), L(a[4], b[4])];
  }
  // Round T5: a tree's branching, grown once on the CPU and drawn by the shader from a small float texture (2 texels per segment).
  // Local units: the tree's height = 1, x right, y up from the root. Trunk to the fork, four scaffold limbs, then each branch ends in a leader
  // (small turn, 0.78 length) and throws laterals along its outer half (alternate sides, 0.55-0.9 rad out); widths follow da Vinci's rule
  // (the cross-sections of the children add up to the parent's); every tip is pulled back inside the species' crown envelope, so the twigs
  // fill the crown's shape the way a real maple's do. Each segment also carries the centre of its scaffold limb's mass (for shading).
  const SEG_MAX = 400;
  function treeSkel(spName, seed) {
    const S = SPECIES[spName] || SPECIES.maple, [wR, hR, fork] = S.shape, cy = 1 - hR;
    let z = (seed * 2654435761 + 12345) >>> 0; const rnd = () => { z = (z + 0x6D2B79F5) >>> 0; let t = Math.imul(z ^ (z >>> 15), 1 | z); t ^= t + Math.imul(t ^ (t >>> 7), 61 | t); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
    const out = [], push = (a, b, wa, wb, mc) => { if (out.length < SEG_MAX) out.push([a[0], a[1], b[0], b[1], wa, wb, mc[0], mc[1]]); };
    const inEnv = (p, k) => { const ex = p[0] / (wR * k), ey = (p[1] - cy) / (hR * k), r = Math.hypot(ex, ey); return r <= 1 ? p : [ex / r * wR * k, cy + ey / r * hR * k]; };
    const MAXL = 6, Q = [];
    const inside = (q, k) => Math.hypot(q[0] / (wR * k), (q[1] - cy) / (hR * k)) <= 1;
    function grow(p, ang, len, w, lev, mc) {
      const bend = (rnd() - .5) * (lev < 3 ? .45 : .35);
      let a1 = ang, mid = [p[0] + Math.sin(a1) * len * .5, p[1] + Math.cos(a1) * len * .5];
      const a2 = ang + bend; let end = [mid[0] + Math.sin(a2) * len * .5, mid[1] + Math.cos(a2) * len * .5];
      const k = lev >= 4 ? 1.04 : .94;
      if (!inside(end, k) && inside(p, k)) {                                 // stop at the crown's edge: shorten the branch along itself (projecting the tip made hoops)
        let lo = 0, hi = 1; for (let i = 0; i < 10; i++) { const m = (lo + hi) / 2; if (inside([p[0] + (end[0] - p[0]) * m, p[1] + (end[1] - p[1]) * m], k)) lo = m; else hi = m; }
        const f = Math.max(lo, .25); end = [p[0] + (end[0] - p[0]) * f, p[1] + (end[1] - p[1]) * f]; mid = [p[0] + (mid[0] - p[0]) * f, p[1] + (mid[1] - p[1]) * f]; len *= f;
      }
      const wm = w * .90, we = w * .80;
      if (lev === 2) { const dx = end[0] - p[0], dy = end[1] - p[1], l = Math.hypot(dx, dy) || 1; mc = inEnv([end[0] + dx / l * .06, end[1] + dy / l * .06], .86); }   // T6: this branch group's dome of foliage
      if (lev < 4) { push(p, mid, w, wm, mc); push(mid, end, wm, we, mc); } else push(p, end, w, we, mc);
      if (lev >= MAXL) return;
      const da = Math.atan2(end[0] - mid[0], end[1] - mid[1]);              // the direction at the tip
      const nLat = lev <= 2 ? 2 : lev <= 4 ? (rnd() < .5 ? 2 : 1) : (rnd() < .45 ? 1 : 0);   // twigs get denser toward the outside
      const kids = lev === 5 && rnd() < .35 ? [] : [[end, da + (rnd() - .5) * .50, .74, 1]];   // the leader zigzags (each year's bud breaks a little aside)
      let side = rnd() < .5 ? -1 : 1;
      for (let i = 0; i < nLat; i++, side = -side) {                        // laterals on the outer half, alternate sides
        const t = .45 + .45 * (i + rnd()) / Math.max(1, nLat), at = [p[0] + (end[0] - p[0]) * t, p[1] + (end[1] - p[1]) * t];
        kids.push([at, da + side * (.55 + .35 * rnd()), .60 + .12 * rnd(), 0]);
      }
      const area = kids.reduce((s, k) => s + (k[3] ? 1 : .55), 0);          // da Vinci: children's cross-sections add up to the parent's
      for (const [at, a, lk, lead] of kids) {
        let aa = a * (lev >= 2 ? .88 : 1);                                    // reach up toward the light
        if (lev >= 3) aa += Math.sign(at[0] || 1) * .08;                      // and out
        Q.push([at, aa, len * lk, we * Math.sqrt((lead ? 1 : .55) / area), lev + 1, mc]);   // breadth first: every limb reaches the same depth before the budget ends
      }
    }
    const lean = (rnd() - .5) * .05, top = [lean, fork];
    push([0, 0], [lean * .5, fork * .5], .040, .033, [0, cy]); push([lean * .5, fork * .5], top, .033, .029, [0, cy]);
    const L1 = [-.80, -.28, .26, .78].map(a => a + (rnd() - .5) * .22);
    L1.forEach((a, i) => { const at = [top[0] - lean * .15 * i, top[1] - .025 * (i % 2)]; Q.push([at, a, .30 + .06 * rnd(), .021, 1, [0, cy]]); });
    while (Q.length && out.length < SEG_MAX) grow(...Q.shift());
    return out;
  }
  // snow on the far mountain through the year: deep in winter, patches in summer, the first falls in late October
  const MSNOW = [[0, 1], [60, 1], [110, .72], [150, .38], [190, .14], [250, .10], [292, .30], [322, .62], [350, .9], [366, 1]];
  const lerpT = (T, d) => { for (let i = 1; i < T.length; i++) if (d <= T[i][0]) return T[i - 1][1] + (T[i][1] - T[i - 1][1]) * (d - T[i - 1][0]) / (T[i][0] - T[i - 1][0]); return T[T.length - 1][1]; };
  const doyOf = (date = new Date()) => (date - new Date(date.getFullYear(), 0, 1)) / 864e5;
  function prog(gl, fs) {
    const sh = (t, s) => { const o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o); if (!gl.getShaderParameter(o, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(o)); return o; };
    const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, VERT)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, 'a'); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p)); return p;
  }
  function progs(gl, list) {                                               // several programs at once: every compile and link is issued before any status is read,
    const sh = (t, s) => { const o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o); return o; };   // so the driver can build them in parallel (KHR_parallel_shader_compile)
    gl.getExtension('KHR_parallel_shader_compile');
    const ps = list.map(fs => { const v = sh(gl.VERTEX_SHADER, VERT), f = sh(gl.FRAGMENT_SHADER, fs), p = gl.createProgram(); gl.attachShader(p, v); gl.attachShader(p, f); gl.bindAttribLocation(p, 0, 'a'); gl.linkProgram(p); return { p, f }; });
    return ps.map(({ p, f }) => { if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getShaderInfoLog(f) || gl.getProgramInfoLog(p)); return p; });
  }
  // The scene shader is built as two programs from one source: the sky (slices 0, 1: sky, cirrus, cloud march, far land) and the land
  // (slices 2, 3: near land, trees, page). One program held both and compiled twice over (D3D's FXC at link, then the GPU driver at the
  // first draw), ~4.5 min headless; each half is far cheaper, because compile time grows faster than the code (T9, LOG).
  const FRAG_PART = d => FRAG.replace('#version 300 es\n', '#version 300 es\n#define ' + d + '\n');
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
  // G3: 5.5, 20.25 and 20.5 hold the twilight dip of the key light (TWI), which a blend of the neighbours misses (20:30 read 32.7 dB from 20 + 21).
  const KEYFRAMES = [5, 5.5, 5.75, 6.5, 7.5, 9, 11, 13, 15, 16.5, 17.75, 19, 20, 20.25, 20.5, 21];
  const bracketHours = (h, list = KEYFRAMES) => { const L = list.slice().sort((a, b) => a - b), i = L.findIndex(x => x > ((h % 24) + 24) % 24); return [L[i <= 0 ? L.length - 1 : i - 1], L[i < 0 ? 0 : i]]; };
  const SKY_CACHE = 'sky-v4';                                             // Cache Storage for layers baked on this device. Bump it whenever the sky's look changes: bakes from an older shader are then dropped (v2: 3.14 clouds, v3: 3.15 horizon and land, v4: G3 night and twilight light)
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
    let pr = null, PR = [], U = null, treeTex = null, treeKey = '', treeN = [0, 0];   // pr: the program uniforms go to (U); PR: [sky, land]
    if (!baked) {
      const T0 = performance.now(), lt = m => G.SCENE_T && console.log('[load]', m, ((performance.now() - T0) / 1000).toFixed(1) + ' s');   // window.SCENE_T = true: log load timing (tools/visual-loop/loadT.js)
      // two programs from one source, compiled in parallel: sky (slices 0, 1) and land (slices 2, 3), see T9
      const tex = makeNoise(gl); lt('noise'); PR = progs(gl, [FRAG_PART('SKY_ONLY'), FRAG_PART('LAND_ONLY')]); lt('scene shaders'); G.__lt = lt; U = n => gl.getUniformLocation(pr, n);
      [[tex.shape, gl.TEXTURE_3D, 'uShape'], [tex.detail, gl.TEXTURE_3D, 'uDetail'], [tex.weather, gl.TEXTURE_2D, 'uWeather']].forEach(([t, k, n], i) => { gl.activeTexture(gl.TEXTURE0 + i); gl.bindTexture(k, t); PR.forEach(q => { gl.useProgram(q); gl.uniform1i(gl.getUniformLocation(q, n), i); }); });
      treeTex = gl.createTexture(); gl.activeTexture(gl.TEXTURE15); gl.bindTexture(gl.TEXTURE_2D, treeTex);   // T5: unit 15 is the trees' alone
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, SEG_MAX * 2, 2, 0, gl.RGBA, gl.FLOAT, null);
      [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.NEAREST)); PR.forEach(q => { gl.useProgram(q); gl.uniform1i(gl.getUniformLocation(q, 'uTreeTex'), 15); });
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
      if (mat === 'pad') {                                                 // slices 0 and 1 write no material, but get a second target anyway: ANGLE (D3D11) compiles one
        const pad = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, pad); gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, w, h);   // copy of the scene shader per render-target
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT1, gl.TEXTURE_2D, pad, 0);   // layout, and the scene shader takes ~2 min to compile: one layout for all four slices
        gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]); return { t, f, w, h, pad };
      }
      const m = tex(); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT1, gl.TEXTURE_2D, m, 0);   // wind material, accumulated with the colour
      gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]);
      return { t, m, f, w, h };
    }
    const drop = x => { gl.deleteTexture(x.t); if (x.m) gl.deleteTexture(x.m); if (x.pad) gl.deleteTexture(x.pad); gl.deleteFramebuffer(x.f); };
    // The frame is the picture the slices hold; the canvas shows it (cover fit). Live, they are the same; baked, the frame is the bake's.
    function targets() {
      const CW = canvas.width, CH = canvas.height;
      const F = baked ? { W: man.W, H: man.H, dpr: man.dpr, E: man.E } : { W: CW, H: CH, dpr: cssDpr(), E: st.bake ? Math.round(DRIFT_ROOM * cssDpr()) : 0 };
      if (RT && RT.W === F.W && RT.H === F.H && RT.CW === CW && RT.CH === CH && RT.E === F.E) return false;
      if (RT) [...RT.s, RT.acc, RT.a, RT.b, RT.c, RT.d, RT.r].forEach(drop);
      k = 0;                                                               // new buffers: restart the average
      const m = Math.ceil(MARGIN * F.dpr), q = n => [Math.max(1, Math.ceil(CW / n)), Math.max(1, Math.ceil(CH / n))];
      RT = { W: F.W, H: F.H, CW, CH, m, E: F.E, dpr: F.dpr, s: [0, 1, 2, 3].map(i => target(F.W + 2 * m + (i === 1 ? F.E : 0), F.H + 2 * m, i >= 2 ? true : baked ? false : 'pad')),
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
      gl.uniform1f(Up(pC, 'uSnowF'), cur.w.snow || 0); gl.uniform3fv(Up(pC, 'uSnowCol'), hex(mixV('#FBFDFF', cur.p.cl, cur.p.night)).map(v => v ** 2.2 * (1 - .4 * cur.p.night)));   // G3: at night the flakes are lit like the cloud's moonlit side, a little under it (L* ~60) gl.uniform1f(Up(pC, 'uBolt'), cur.w.bolt || 0);
      gl.uniform1f(Up(pC, 'uWind'), cur.w.wind); gl.uniform1f(Up(pC, 'uDpr'), dpr); gl.uniform1f(Up(pC, 'uWindDbg'), st.windDebug ? 1 : 0);
      gl.uniform1f(Up(pC, 'uBirds'), live ? (1 - cur.p.night) * (1 - cur.w.rain) * (1 - .8 * Math.min(1, cur.w.fog)) * (1 - .7 * cur.w.grey) : 0);   // day, dry, clear enough to see them; none when still
      gl.uniform3fv(Up(pC, 'uBirdCol'), hex(mixV('#2F3B48', cur.p.mid, .18)).map(v => v ** 2.2));   // dark, a little hazed by the air between
      gl.uniform2f(Up(pC, 'uSun'), cur.sp[0], cur.sp[1]); gl.uniform1f(Up(pC, 'uSunR'), cur.p.sunR);
      gl.uniform1f(Up(pC, 'uShowSun'), st.showSun && !st.bake ? cur.sv * (1 - cur.w.style) : 0);   // a bake never holds the disc: it is drawn here, where the hour puts it
      gl.uniform1f(Up(pC, 'uVeil'), Math.min(1, cur.w.fog * .9 + cur.w.rain * .5));
      gl.uniform3fv(Up(pC, 'uSunDisc'), hex(cur.p.sunC).map(c => shoulder(c ** 2.2 * (cur.p.night > .5 ? 1 : 2.2))));
      const halo = st.showSun ? cur.sv * (1 - cur.p.night * .93) * (1 - cur.w.style) : 0;
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
      const rays = g.rays * (st.showSun ? cur.sv : 0) * (1 - cur.w.style) * (1 - .8 * Math.min(1, cur.w.sheet));   // no shafts without a visible sun or under a closed sheet
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
        const flare = st.post && st.showSun && sunUV[1] > 0 && sunUV[1] < 1.2 ? cur.sv * (1 - cur.w.style) * (1 - .9 * Math.min(1, cur.w.sheet)) * (1 - .93 * cur.p.night) : 0;   // no optics through a closed sheet; a faint one for the moon
        gl.uniform2f(Up(pF, 'uSunUV'), sunUV[0], sunUV[1]); gl.uniform1f(Up(pF, 'uFlare'), flare); gl.uniform1f(Up(pF, 'uRimK'), 1.2); gl.uniform1f(Up(pF, 'uGhost'), 1 - cur.p.night);
        gl.uniform3fv(Up(pF, 'uGain'), g.gain); gl.uniform3fv(Up(pF, 'uLift'), g.lift); gl.uniform3fv(Up(pF, 'uSunCol'), hex(cur.p.sunC).map(c => c ** 2.2));
      });
    }
    function size() { const dpr = cssDpr(); const w = st.frameSize ? st.frameSize[0] : Math.round(canvas.clientWidth * dpr), h = st.frameSize ? st.frameSize[1] : Math.round(canvas.clientHeight * dpr); if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; } }
    // What the hour, readiness and sky options mean right now (palette, sun, weather): shared by the live and baked paths.
    function state() {
      const hour = st.hour != null ? st.hour : HOURS[st.time] != null ? HOURS[st.time] : 13;
      const w = Object.assign(weather(st.readiness), st.sky || {}), p0 = palAt(hour);
      const p = w.pal ? mixV(p0, Object.assign({}, p0, w.pal), (w.palK || 1) * (1 - .75 * p0.night)) : p0;   // 3.15: weather colours (rain violet-grey with a lilac horizon, overcast blue-grey, snow icy), after the reference
      const vis = p.night < .5 ? 1 - sstep(.3, .5, p.night) : sstep(.5, .7, p.night);   // the sun sets, then the moon comes up
      const sp = st.sunAt || (p.night < .5 ? sunAt(hour) : MOON);
      const sv = vis * (1 - sstep(.45, .85, w.fog));   // 3.16: thick fog hides the sun (disc, halo, starburst, shafts); a faint glow stays in the sky shader
      cur = { p, sp, w, vis, sv, hour };
    }
    function setScene(now) {
      state(); const { p, sp, w, vis } = cur;
      const dayN = st.seed != null ? st.seed : Math.floor(Date.now() / 864e5), rndDay = k => { const x = Math.sin(dayN * 127.1 + k * 311.7) * 43758.5453; return x - Math.floor(x); };   // the upper wind turns from day to day
      gl.uniform2f(U('uRes'), RT.W, RT.H); gl.uniform1f(U('uT'), st.animate ? (now - t0) / 1000 + 40 : 40);
      gl.uniform1f(U('uCover'), w.cover); gl.uniform1f(U('uTall'), w.tall); gl.uniform1f(U('uGrey'), w.grey); gl.uniform1f(U('uHaze'), w.haze);
      [['uStyle', w.style], ['uPaintCov', w.paintCov], ['uCirrus', w.cirrus], ['uCiHook', w.ciHook], ['uCiVeil', w.ciVeil], ['uCiAng', w.ciAng + (rndDay(12) - .5) * 1.1]].forEach(([n, v]) => gl.uniform1f(U(n), v));
      [['uSnow', w.snow || 0], ['uSheet', w.sheet], ['uSType', w.sType], ['uSB', w.sb], ['uST', w.st], ['uFogD', w.fog], ['uFogH', w.fogH], ['uRain', w.rain]].forEach(([n, v]) => gl.uniform1f(U(n), v)); gl.uniform1f(U('uNight'), p.night); gl.uniform1f(U('uSunI'), p.I * (1 - TWI * sstep(.35, .6, p.night) * (1 - sstep(.85, 1, p.night))));   // G3: twilight. Once the sun is down (night .35-.6) the key goes before the linear dusk->night blend would take it, and the moon takes over near full night: the cloud tops lose the light before the sky gaps go dark
      [['uPg0', w.pg ? w.pg[0] : p.pg[0]], ['uPg1', w.pg ? w.pg[1] : p.pg[1]], ['uPg2', w.pg ? w.pg[2] : p.pg[2]], ['uPg3', w.pg ? w.pg[3] : p.pg[3]], ['uZen', p.zen], ['uMid', p.mid], ['uHor', p.hor], ['uSunC', p.sunC], ['uCLit', p.cl], ['uCDark', p.cd], ['uFog', p.fog], ['uH0', p.h[0]], ['uH1', p.h[1]], ['uH2', p.h[2]], ['uH3', p.h[3]], ['uTree', p.tree], ['uPath', p.path]]
        .forEach(([n, c]) => gl.uniform3fv(U(n), hex(c)));
      gl.uniform3fv(U('uKey'), norm(p.key));
      gl.uniform1f(U('uDry'), st.dry != null ? st.dry : st.doy != null ? lerpT(DRY, st.doy) : dryAt());
      const tp = LAND, L3 = [0, 1, 2].map(i => tp.layers[i]);
      gl.uniform3fv(U('uTk'), TK.flatMap(hex));
      gl.uniform1f(U('uNL'), tp.layers.length); gl.uniform4fv(U('uLip'), tp.lip);
      gl.uniform4fv(U('uLy'), L3.flatMap(l => l ? l.crest : Array(24).fill(0))); gl.uniform4fv(U('uLz'), L3.flatMap(l => l ? l.props : Array(12).fill(0)));
      gl.uniform4fv(U('uGr'), [...tp.gr, -10, -9, 0, 0]);
      gl.uniform4fv(U('uTr'), tp.trees.flat());
      const doy = st.doy != null ? st.doy : doyOf(), spp = [tp.species];   // the trees follow the day of the year
      gl.uniform4fv(U('uTs'), [0, 1].flatMap(i => phen(spp[Math.min(i, spp.length - 1)], doy)));
      { const trs = [0, 1].map(i => (tp.trees || [])[i] || [0, 0, 0, 0]), key = trs.map((t, i) => spp[Math.min(i, spp.length - 1)] + ':' + t[2]).join('|');   // T5: regrow only when a tree changes
        if (key !== treeKey) { treeKey = key; const ns = [0, 0], buf = new Float32Array(SEG_MAX * 2 * 4 * 2);
          trs.forEach((t, i) => { const sg = treeSkel(spp[Math.min(i, spp.length - 1)], t[2] * 7 + 1); ns[i] = sg.length; sg.forEach((g, j) => buf.set(g, (i * SEG_MAX * 2 + j * 2) * 4)); });
          gl.activeTexture(gl.TEXTURE15); gl.bindTexture(gl.TEXTURE_2D, treeTex); gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, SEG_MAX * 2, 2, gl.RGBA, gl.FLOAT, buf); treeN = ns; }
        gl.uniform2i(U('uTreeN'), treeN[0], treeN[1]); }   // every call: each program needs it
      gl.uniform4fv(U('uLm'), L3.flatMap(l => l && l.mtn ? [l.mtn[0], lerpT(MSNOW, doy), l.mtn[2], 0] : [0, 0, 0, 0]));
      gl.uniform4fv(U('uSd'), tp.shore);
      gl.uniform4fv(U('uTst'), LEAF);
      const day = st.seed != null ? st.seed : Math.floor(Date.now() / 864e5), rnd = k => { const x = Math.sin(day * 127.1 + k * 311.7) * 43758.5453; return x - Math.floor(x); };
      gl.uniform2f(U('uSeed'), rnd(1) * 160, rnd(2) * 160);
      // hero clouds: one or two large cumulus in the mid distance. Their side is chosen per day, not from the sun: placed by the sun they
      // jumped across the sky when it passed the middle, and baked hours of one day must share one formation (3.12)
      // 3.15: the near mass sits on the left, behind the readiness number (dark ink on white cloud reads best), so the sun, right of centre
      // for most of the day, stays in open sky; its size follows the low-cloud cover (a fair day keeps it small)
      const side = -1, hk = .55 + .45 * sstep(.30, .50, w.cover);
      // 3.15: the first hero sits near (6-8.5 km) and runs off the top edge, a soft mass like the reference's; the second stays farther out
      gl.uniform4f(U('uHero1'), side * (.6 + rnd(3) * 1.2), 6 + rnd(4) * 2.5, (3.0 + rnd(5) * 1.2) * hk, .55 + .35 * rnd(6));
      gl.uniform4f(U('uHero2'), -side * (1.6 + rnd(7) * 2.2), 12 + rnd(8) * 5, rnd(9) > .45 ? (2.2 + rnd(10)) * hk : 0, .45 + .3 * rnd(11));
      gl.uniform2f(U('uSun'), sp[0], sp[1]); gl.uniform1f(U('uSunR'), p.sunR); gl.uniform1f(U('uShowSun'), st.showSun ? vis * (1 - .8 * sstep(.45, .85, w.fog)) : 0);   // halo and fog glow; the disc itself is drawn in compose
      gl.uniform1f(U("uStepK"), st.stepK || 1);
    }
    const reduce = !!(G.matchMedia && G.matchMedia('(prefers-reduced-motion: reduce)').matches), live = st.animate && !reduce && !st.bake;
    // Progressive accumulation: each frame uses a new ray jitter and sub-pixel offset and is blended into a running average per slice,
    // so 24 frames give a noise-free, anti-aliased image. While the sky drifts, frames blend at 25%. Tilting only re-composites.
    let k = 0, running = false, dirty = true;
    const DRIFT_MAX = 60;   // live only: ms per slice re-render; above it (phones: ~150-320 ms) the clouds hold still once converged while the wind keeps moving. Phones should play baked layers instead (opts.baked)
    const next = f => { let done = false; const go = t => { if (!done) { done = true; f(t); } }; requestAnimationFrame(go); setTimeout(() => go(performance.now()), 120); };   // keep converging when frames are throttled
    const px1 = new Uint8Array(4), sync = () => { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px1); };   // waits for the GPU
    function useSlice(i) { pr = PR[i < 2 ? 0 : 1]; gl.useProgram(pr); }   // slices 0, 1: the sky program; 2, 3: the land program
    function sceneAll(now, kk) {                                         // the scene's uniforms into both programs, with one jitter offset for the frame
      const off = kk === 0 ? [0, 0] : [Math.random() - .5, Math.random() - .5];
      (PR[0] === PR[1] ? PR.slice(0, 1) : PR).forEach(q => { pr = q; gl.useProgram(q); setScene(now);
        gl.uniform2f(U('uMargin'), RT.m, RT.m); gl.uniform1f(U('uSplit'), SPLIT); gl.uniform2f(U('uOff'), off[0], off[1]); gl.uniform1f(U('uFrame'), kk % 97); });
    }
    function accumulate(now) {
      const timed = k === 4;                                               // once per scene, time a frame on the GPU (the CPU-side time says nothing about GPU load); frames 1-3 still carry driver warm-up
      if (timed) sync();
      const a = performance.now();
      size(); targets(); sceneAll(now, k);
      const alpha = k === 0 ? 1 : Math.max(1 / (k + 1), live ? .25 : 0);
      if (k === 0) gl.disable(gl.BLEND); else { gl.enable(gl.BLEND); gl.blendColor(0, 0, 0, alpha); gl.blendFunc(gl.CONSTANT_ALPHA, gl.ONE_MINUS_CONSTANT_ALPHA); }
      RT.s.forEach((x, i) => { useSlice(i); gl.bindFramebuffer(gl.FRAMEBUFFER, x.f); gl.viewport(0, 0, x.w, x.h); gl.uniform1f(U('uSlice'), i); gl.drawArrays(gl.TRIANGLES, 0, 3); if (G.SCENE_T && G.__lt && k <= 1) { sync(); gl.bindFramebuffer(gl.FRAMEBUFFER, x.f); G.__lt('frame ' + k + ' slice ' + i + (k ? ' (blend)' : ' (no blend)')); } });
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
      if ((!baked || ready) && (dirty || moving || ambient)) { compose(now); post(now); dirty = false; lastC = now; if (G.SCENE_T && G.__lt && !G.__lt.cp) { sync(); G.__lt('first compose + post, frame ' + k); G.__lt.cp = 1; } }
      if (G.SCENE_T && G.__lt && k === 24 && !G.__lt.k24) { G.__lt('24 frames accumulated'); G.__lt.k24 = 1; }
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
      get cost() { return cost; }, get gpu() { return gpu; }, get frames() { return k; }, get pal() { if (!cur) state(); return cur.p; },   // G1: the hour's palette after weather (the UI picks its glass from it)
      get tilt() { return { x: tilt.x, y: tilt.y }; },
      bench(kind, n) {                                                     // GPU timing for the board: n synchronous frames, waits for the GPU
        const px = new Uint8Array(4), sync = () => { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); };
        sync(); const t = performance.now(), now = t;
        for (let i = 0; i < n; i++) { if (kind === 'compose') compose(now); else if (kind === 'post') post(now); else if (kind === 'tilt') { tilt.x = Math.sin(i); tilt.y = Math.cos(i) * .5; compose(now); post(now); } else { if (baked) decode(...lastDec); else accumulate(now); compose(now); post(now); } }
        sync(); tilt.x = tilt.y = 0; dirty = true; kick(); return (performance.now() - t) / n;
      },
      // On-device bake (3.13): converge 24 frames in horizontal bands sized so each takes about budgetMs on the GPU, pausing gapMs
      // between bands so the visible scene and the rest of the phone keep the GPU most of the time. Needs opts.bake and opts.manual.
      async renderSliced({ budgetMs = 8, gapMs = 16, onProgress, signal } = {}) {
        size(); targets(); k = 0;
        const pause = ms => new Promise(r => setTimeout(r, ms));
        let bh = 32, gpuMs = 0;
        for (; k < 24;) {
          sceneAll(t0, k);
          const alpha = k === 0 ? 1 : 1 / (k + 1);
          for (let i = 0; i < 4; i++) {
            const x = RT.s[i];
            for (let y = 0; y < x.h;) {
              if (signal && signal.aborted) throw new Error('aborted');
              const h = Math.min(bh, x.h - y);
              sync(); const t = performance.now();
              useSlice(i); gl.bindFramebuffer(gl.FRAMEBUFFER, x.f); gl.viewport(0, 0, x.w, x.h); gl.uniform1f(U('uSlice'), i);
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
  G.SceneGL = { mount, PAL, PRESETS, LAND, dryAt, SPECIES, doyOf, phen, weather, palAt, sunAt, HOURS, regimeOf, KEYFRAMES, bracketHours, cached, SKY_CACHE, _noise: makeNoise, _png: encodePNG, _src: { VERT, GEN, FRAG, BRIGHT, BLUR, COMPOSE, ENCODE, DECODE, RAYS, FINAL } };   // _src: compile-time harness (tools/visual-loop/compileT.js)
})();
