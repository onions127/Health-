#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst, uSunUV, uRes; out vec4 o;
float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x*p.y); }
void main(){ vec2 uv = gl_FragCoord.xy/uDst;
  vec2 d = (uv - uSunUV)/48.*.95, q = uv - d*hash(gl_FragCoord.xy);
  float acc = 0., w = 1., asp = uRes.x/uRes.y;
  for (int i = 0; i < 48; i++) { q -= d; vec2 r = (q - uSunUV)*vec2(asp, 1.); acc += texture(uSrc, q).a*exp(-dot(r, r)*18.)*w; w *= .968; }
  o = vec4(vec3(acc/48.), 1.); }