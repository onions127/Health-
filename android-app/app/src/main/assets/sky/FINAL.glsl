#version 300 es
precision highp float;
uniform sampler2D uScene, uB1, uB2, uR; uniform vec2 uRes; uniform float uBloom, uRays, uSat, uCon, uT; uniform vec3 uGain, uLift, uSunCol;
uniform vec2 uSunUV; uniform float uFlare, uRimK, uGhost, uSpk, uSpkS;   // L1: uSpk = spike strength (0 at night: the moon has none), uSpkS = style (1 soft glare, 2 the 3.15 starburst)   // 3.15: sun optics (starburst, lens ghosts, silver rim), scaled by how much of the sun is in open sky
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
  float burst = pow(abs(cos(ang*5. + tw)), 600.)*exp(-r*24.) + .22*pow(abs(cos(ang*5. + 1.5708 + tw)), 700.)*exp(-r*45.);   // 3.15: 10 hairline spikes reach ~12% of the screen height; 10 faint short ones between
  float soft = (.55*pow(abs(cos(ang*3. + .4 + tw)), 18.) + .45)*exp(-r*38.)*.30;   // L1: an eye's glare, not a lens: a soft round glow with six broad, short petals (~4% of the height), no lines
  float sp = mix(soft, burst, step(1.5, uSpkS))*uSpk;
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
}