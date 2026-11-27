#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst, uSrcTexel; uniform float uTh; out vec4 o;
void main(){ vec2 uv = gl_FragCoord.xy/uDst, d = uSrcTexel;
  vec3 c = (texture(uSrc, uv + d*vec2(-1., -1.)).rgb + texture(uSrc, uv + d*vec2(1., -1.)).rgb + texture(uSrc, uv + d*vec2(-1., 1.)).rgb + texture(uSrc, uv + d*vec2(1., 1.)).rgb)*.25;
  float l = dot(c, vec3(.2126, .7152, .0722));
  o = vec4(c*smoothstep(uTh, uTh + .35, l), 1.); }