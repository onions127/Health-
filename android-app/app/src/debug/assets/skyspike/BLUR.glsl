#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst, uDir; out vec4 o;
void main(){ vec2 uv = gl_FragCoord.xy/uDst;                     // 9-tap Gaussian folded into 5 linear taps
  vec3 c = texture(uSrc, uv).rgb*.227027 + (texture(uSrc, uv + uDir*1.3846).rgb + texture(uSrc, uv - uDir*1.3846).rgb)*.316216
         + (texture(uSrc, uv + uDir*3.2308).rgb + texture(uSrc, uv - uDir*3.2308).rgb)*.070270;
  o = vec4(c, 1.); }