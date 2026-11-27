#version 300 es
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
}