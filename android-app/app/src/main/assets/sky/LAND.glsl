#version 300 es
#define LAND_ONLY
precision highp float; precision highp sampler3D;
uniform sampler3D uShape, uDetail; uniform sampler2D uWeather;
uniform vec2 uRes; uniform float uT, uCover, uTall, uGrey, uNight, uHaze, uSunI;
uniform float uSheet, uSType, uSB, uST, uFogD, uFogH, uRain;
uniform float uCirrus, uCiHook, uCiVeil, uCiAng;
uniform float uStyle, uPaintCov;   // 0 = volumetric sky, 1 = painted sky (soft airbrushed layer, after the weather-app reference)   // cirrus cover, hooked heads (mares' tails), milky veil with halo, streak direction   // sheet cloud amount, type (0 stratocumulus .. 1 stratus), base/top km; fog density, fog height; rain
uniform vec3 uZen, uMid, uHor, uSunC, uCLit, uCDark, uFog, uH0, uH1, uH2, uH3, uTree, uPath, uKey;
uniform vec3 uPg0, uPg1, uPg2, uPg3; uniform float uSnow;   // 3.15: far hills, lake, the page meadow under the cards (top .. bottom), snow on the ground
uniform float uDry; uniform vec3 uTk[8]; uniform vec4 uLy[18], uLz[9], uLip[3]; uniform float uNL; uniform vec4 uGr[2], uTr[2]; uniform vec4 uLm[3], uPk[5], uTs[8], uSd, uTst; uniform highp sampler2D uTreeTex; uniform ivec2 uTreeN;   // T5: the trees' grown branching (2 texels per segment, one row per tree)   // T3: per-layer mountain (peak height, snow 0..1, rock, -); per-tree species and season (shape; leaf rgb + fullness; leaf2 rgb + bark red; bloom rgb + amount); shore (beach width, earth patches, litter, -)   // T2: per-layer woodland patches; grass rows (back dens, height, front dens, height; mask u0, u1, comb); two small trees (u, height, seed, on) and their colour
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
  // SH1: the sheet's own large-scale order from the weather map (34 km): rows bend and patches run thicker and thinner;
  // the cells are turned off the texture axes, so the 4.5 km tile never repeats in lines toward the horizon
  vec4 w = textureLod(uWeather, (q.xz + uSeed*1.31 + vec2(17., 5.))/34., 0.);
  float wr = clamp((w.r - .51)*3.3 + .5, 0., 1.);
  vec2 xz = q.xz + (w.gr - .51)*3.;
  vec2 xa = mat2(.8, .6, -.6, .8)*xz;
  vec4 n = textureLod(uShape, vec3(xa.x/4.5, q.y/1.6, xa.y/4.5), 0.);   // cells flattened into a layer
  float pw = clamp((n.r - .62)/.26, 0., 1.), wf = clamp(((n.g*.40 + n.b*.35 + n.a*.25) - .30)/.36, 0., 1.), cov = uSheet;
  float und = (pw - .5)*.22;                                            // base and top undulate
  float hf = (p.y - uSB - und)/(uST - uSB);
  float prof = smoothstep(0., .2, hf)*(1. - smoothstep(.5, 1., hf));
  if (prof <= 0.) return 0.;
  float cells = remap((wf*.65 + pw*.35)*prof, 1. - cov*.9, 1., 0., 1.);
  float sheet = (.45 + .55*pw)*prof*uSheet*mix(.85, 1.05, wr);
  float b = mix(cells, sheet, smoothstep(.45, 1., uSType));
  float far = smoothstep(6., 22., p.z);                                  // SH1: seen edge-on the gaps close up: far off the sheet merges into one soft band (the 4.5 km cells glittered there in rows)
  b = mix(b, prof*uSheet*(.62 + .2*pw), far);
  if (full && b > 0.) { float dn = textureLod(uDetail, q/1.6, 0.).r; b = remap(b, (1. - dn)*.30*(1. - uSType*.7)*(1. - far), 1., 0., 1.); }
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
  float env = (a.y + a.z*bump(u, a.w, b.x) + b.y*bump(u, b.z, b.w) + c.x*smoothstep(c.y, c.z, u))*(1. - c.w + c.w*cos(u*d.x + d.y))*smoothstep(d.z, d.w, u);
  if (uLm[i].x > 0.) {                                                      // M6: the mountain is a few designed peaks (photo reference): straight flanks, V notches,
    float h = .55*env;                                                      // joined by a ridge at half the envelope, so it reads as one range
    for (int p = 0; p < 5; p++) { vec4 q = uPk[p]; h = max(h, q.y - (u < q.x ? q.z : q.w)*abs(u - q.x)); }   // q: u, height, left slope, right slope
    return h;
  }
  return env;
}
float tCrest(int i, float x){
  int k = i*6; float u = x/gAsp;
  vec4 a = uLy[k], e = uLy[k + 4], f = uLy[k + 5];
  float h = tMass(i, x);
  float pk = uLm[i].x;
  if (pk > 0.) {                                                            // M6: the designed peaks plus fine rocky teeth along the crest (the photo's jagged edge), none at the foot
    float j0 = 1. - abs(2.*noise(vec2(x*e.y*.33, e.z + 2.)) - 1.), j1 = 1. - abs(2.*noise(vec2(x*e.y, e.z)) - 1.), j2 = 1. - abs(2.*noise(vec2(x*e.y*2.3, e.z + 4.)) - 1.);
    return a.x - h - smoothstep(.0, .012, h)*(e.x*(j1*j1 - .4) + e.x*.5*(j2*j2 - .4)) - h*.24*(j0 - .5);   // teeth on the crest; shoulders (j0) break the straight flanks
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
float tCoverC(int i, float c, float y){                                     // M1: cover from a crest the caller already has (the crest is
  float b = tBase(i), px = .8/uRes.y;                                       // computed once per layer per pixel: every call is an inlined copy, T9)
  return smoothstep(c - px, c + px, y)*(1. - smoothstep(b - px, b + px, y))*smoothstep(.0004, .0025, b - c);
}
vec3 tColor(int i, float x, float y, float shadow, float c){                         // a far layer's colour (linear) at (x, y); shadow: cloud shadow, sampled once per pixel by the caller
  vec4 z0 = uLz[i*3], z1 = uLz[i*3 + 1], z2 = uLz[i*3 + 2];
  float d = z0.x, b = z0.y;
  float e = .004, sl = (tMass(i, x - e) - tMass(i, x + e))/(2.*e), t = max(y - c, 0.);   // slope from the smooth massing (y = y0 - h)
  float lam = tLam(sl, x, y);
  vec3 a = mix(uTk[int(z1.w + .5)], grassAlb(), z2.x);                    // local colour: a token, part grass (the season)
  float rock = z2.w*step(b - .0045 - .0015*noise(vec2(x*120., 2.)), y);    // limestone at the waterline
  a = mix(a, mix(uTk[7], vec3(.55, .52, .48), .35), rock);
  vec4 mt = uLm[i];                                                         // T3: a high mountain far off: rock faces, snow above a line that follows the season, gullies carry it down
  if (mt.x > 0.) {
    float gq = noise(vec2(x*210., y*55.))*.7 + noise(vec2(x*640., y*110.))*.3, gl2 = noise(vec2(x*40., 1.3));
    a = mix(a, vec3(.50, .50, .52), mt.z*smoothstep(.2, 1.4, abs(sl)));      // steep faces: bare rock
    // M1: an altitude snow line (the season moves it). The peaks now differ in height, so the tall ones wear caps and the low ones
    // stay bare; the line wanders along the range and runs down the gullies, rock ribs stay dark
    float hg = b - y, line = mt.x*(1. - .40*mt.y) + .020*(gl2 - .5) - .016*(gq - .45)*mt.y;
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
  int ri = -1; float rcv = 0., ry = 0., rc = 0.;
  for (int i = 2; i >= 0; i--) {                                            // the far shore mirrored about its foot, nearest layer first
    if (ri >= 0 || i >= tN()) continue;
    float b = tBase(i); if (b > 1. || P.y <= b) continue;
    float c = tCrest(i, mx), cv = tCoverC(i, c, 2.*b - P.y + n*.004);
    if (cv > .01) { ri = i; rcv = cv; ry = 2.*b - P.y + n*.004; rc = c; }
  }
  if (ri >= 0) refl = mix(refl, tColor(ri, mx, ry, 0., rc)*.86, rcv*.85);
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
    float crs[3], cva[3];                                                   // M1: each layer's crest and cover, once per pixel for both slices
    for (int i = 0; i < 3; i++) { crs[i] = i < tN() ? tCrest(i, x) : 0.; cva[i] = i < tN() ? tCoverC(i, crs[i], y) : 0.; }
    if (uSlice < .5) {
      // ---- slice 0: sky, cirrus, far clouds, far hills, the lake (opaque; alpha carries the open-sky mask for light shafts)
      // far layers over the water, then the sky where they leave it open
      float cvs[3]; float fd = y > HOR ? .03/max(y - HOR, 1e-4) : 0.;  // coverage per layer; the front surface's distance
      for (int i = 0; i < 3; i++) { cvs[i] = tSlice(i) == 0 ? cva[i] : 0.; if (cvs[i] > .001) fd = tDist(i); }
      float sh = fd > 0. ? tShadow(x, fd) : 0.;                        // one cloud-shadow sample per pixel, at the front surface's distance
      if (y > HOR - 1./uRes.y) over(C, A, toS(tWater(rd, P, aspect)), smoothstep(HOR - .7/uRes.y, HOR + .7/uRes.y, y));
      for (int i = 0; i < 3; i++) if (cvs[i] > .001) over(C, A, toS(tColor(i, x, y, sh, crs[i])), cvs[i]);
      need = A < .999;
    } else {
      // ---- slice 1: near clouds, premultiplied (they may sit in front of the far hills)
      if (rd.y <= .004 || uStyle > .5) { o = vec4(0.); return; }
      tLo = 0.; tHi = uSplit;                                            // T2: the march stops at the terrain, so a ridge hides the clouds behind it
      for (int i = 0; i < 3; i++) if (cva[i] > .5) tHi = min(tHi, tDist(i));
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
  int mi = tN() - 1; float mc = mi >= 0 ? tCrest(mi, x) : 0., cv = uSlice < 2.5 && mi >= 0 && tSlice(mi) == 2 ? tCoverC(mi, mc, y) : 0.;   // the one mid layer
  float gd = .03/max(y - HOR, .002), cloudy = uCover + uSheet;
  float shd = uSlice < 2.5 ? (cv > .001 && cloudy > 0. ? tDist(mi) : -1.) : (near > 0. ? gd : -1.);
  float dn = shd > 0. ? cDen(x, shd) : 0.;
  if (uSlice < 2.5) {
    if (cv > .001) { over(C, A, toS(tColor(mi, x, y, smoothstep(.0, .25, dn), mc)), cv); overM(M, vec2(0.), cv); }
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
}