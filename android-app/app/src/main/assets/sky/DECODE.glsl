#version 300 es
precision highp float; uniform sampler2D uAc, uAa, uBc, uBa; uniform float uW; uniform vec2 uDst;
layout(location = 0) out vec4 o; layout(location = 1) out vec4 o2;
const float R = 2.8284271;
vec3 dec(vec4 e){ vec3 g = e.rgb*e.a*R; return g*g; }
void main(){ vec2 uv = gl_FragCoord.xy/uDst;                          // the hour between two baked keyframes: blend in linear light
  vec3 c = mix(dec(texture(uAc, uv)), dec(texture(uBc, uv)), uW); vec4 x = mix(texture(uAa, uv), texture(uBa, uv), uW);
  o = vec4(c, x.r); o2 = vec4(x.g, x.b, 0., x.r); }