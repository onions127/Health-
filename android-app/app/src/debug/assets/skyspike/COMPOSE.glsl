#version 300 es
precision highp float;
uniform sampler2D uS0, uS1, uS2, uS3, uM2, uM3; uniform vec2 uRes, uMargin, uSliceRes, uSliceRes1; uniform vec2 uO0, uO1, uO2, uO3;
uniform vec4 uXf;                                                       // canvas px -> frame px (scale, offset): a baked frame covers any screen aspect
uniform vec2 uSun; uniform float uSunR, uShowSun, uVeil, uStars; uniform vec3 uSunDisc, uHalo;
uniform float uT, uRain, uNight, uWind, uBirds, uDpr, uWindDbg; uniform vec3 uRainCol, uBirdCol;
uniform float uScrK, uLift, uScrLod;                                     // SC1: scroll progress 0..1, land lift (frame px), the blur's top mip level
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
  if (P.y > .34 || P.y < .04 || abs(P.x - asp*.74) > .16) return 0.;    // cull (the glow has faded to < 1% at the box's edge): the bolt lives in a box in the upper sky (phone cost)
  float d = 1e3, d2 = 1e3; vec2 a = vec2(asp*.74, .10), br = vec2(0.);    // ST1: right of the hero text (was x .30, through the readiness number) and below the top-right button (was from the top edge)
  for (int i = 0; i < 12; i++) {                                           // short jagged steps from the cloud base down to ~27% of the screen, above the maple
    vec2 b = a + vec2((hash(vec2(float(i), 3.1)) - .5)*.030, .010 + .009*hash(vec2(float(i), 5.7)));
    d = min(d, segD(P, a, b)); if (i == 5) br = b; a = b;
  }
  a = br;
  for (int i = 0; i < 7; i++) { vec2 b = a + vec2(.003 + (hash(vec2(float(i), 9.3)) - .35)*.016, .009 + .005*hash(vec2(float(i), 2.9))); d2 = min(d2, segD(P, a, b)); a = b; }
  float px = 1./uRes.y;
  return (1. - smoothstep(.6*px*uDpr, 1.6*px*uDpr, d)) + .55*(1. - smoothstep(.4*px*uDpr, 1.2*px*uDpr, d2)) + .21*exp(-d/.012) + .15*exp(-d2/.008);
}
vec4 tap(sampler2D t, vec2 px){ return textureLod(t, (px + uMargin)/uSliceRes, 0.); }
vec4 tap1(vec2 px){ return textureLod(uS1, (px + uMargin)/uSliceRes1, 0.); }     // the near-cloud slice is wider when baked: room to drift
// SC1: a slice seen through the scroll blur: five taps on its mip chain (lod 0 = the slice itself)
vec4 tapB(sampler2D t, vec2 px, float lod){
  vec2 uv = (px + uMargin)/uSliceRes;
  if (lod < .02) return textureLod(t, uv, 0.);
  vec2 d = exp2(lod)*.7/uSliceRes;
  return (2.*textureLod(t, uv, lod) + textureLod(t, uv + d, lod) + textureLod(t, uv - d, lod) + textureLod(t, uv + vec2(d.x, -d.y), lod) + textureLod(t, uv + vec2(-d.x, d.y), lod))/6.;
}
void main(){
  vec2 px = gl_FragCoord.xy*uXf.xy + uXf.zw;
  // SC1: scrolling the page lifts the near land a little and blurs the land away; the sky, the sun and the page under the cards stay put.
  // uScrK: progress over the first 200 CSS px of scroll; the blur eases out (half way at ~30%), as the reference's land does
  float yS = 1. - px.y/uRes.y, f = 1. - (1. - uScrK)*(1. - uScrK);
  float band = 1. - smoothstep(.40, .60, yS);                            // the land and the lip; the page under the cards is not moved
  float lodL = uScrLod*f*band, lod0 = uScrLod*f*smoothstep(HOR - .07, HOR - .01, yS)*band;   // slice 0: only its far hills and lake, never the sky
  vec2 lift = vec2(0., uLift*band);
  vec4 s0 = tapB(uS0, px + uO0, lod0), s1 = tap1(px + uO1), s2 = tapB(uS2, px + uO2 - lift, lodL), s3 = tapB(uS3, px + uO3 - lift, lodL);
  s2 = windLand(s2, tapB(uM2, px + uO2 - lift, lodL), px + uO2 - lift); s3 = windLand(s3, tapB(uM3, px + uO3 - lift, lodL), px + uO3 - lift);
  if (uScrK > 0.) {                                                      // and the near land fades into the page's own top colour (the reference's land goes to its gradient)
    // above the lip the trees and tufts fade out to the sky; from the lip down the turf goes to the page's colour (the page itself is unchanged)
    float below = smoothstep(.392, .408, yS);
    vec4 pg = tap(uS3, vec2(px.x, (1. - .445)*uRes.y) + uO3);
    s3 = mix(s3*(1. - f), mix(s3, pg, f*(1. - smoothstep(.41, .445, yS))), below); s2 *= 1. - f;
  }
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
}