#version 300 es
precision highp float; uniform sampler2D uC, uM; uniform float uMode, uHasM; uniform vec2 uDst; out vec4 o;
const float R = 2.8284271;
void main(){ vec2 uv = gl_FragCoord.xy/uDst; vec4 c = texture(uC, uv);
  if (uMode < .5) { vec3 g = sqrt(max(c.rgb, 0.)); float m = clamp(max(max(g.r, g.g), max(g.b, 1e-6))/R, 1./255., 1.); m = ceil(m*255.)/255.; o = vec4(g/(m*R), m); }
  else { vec2 mt = uHasM > .5 ? texture(uM, uv).rg : vec2(0.); o = vec4(clamp(c.a, 0., 1.), clamp(mt, 0., 1.), 1.); } }