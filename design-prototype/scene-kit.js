/* Health++ scene kit (exploration only).
   SCENE(subject, time, readiness) -> SVG string, viewBox 412x915.
   subject: 'hills' | 'lake'; time: 'dawn' | 'day' | 'dusk' | 'night'; readiness: number or null (first run).
   Readiness sets the weather: >=80 clear, 65-79 a few clouds, <65 overcast with haze. Time sets light and palette.
   Rule from Round 3.1: by day, every colour under the glass cards is at least as light as #86B9A0. */
(function () {
  let N = 0;
  const rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
  const hex = a => '#' + a.map(v => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
  const mix = (a, b, t) => { const x = rgb(a), y = rgb(b); return hex(x.map((v, i) => v + (y[i] - v) * t)); };
  const lift = (c, t) => mix(c, '#FFFFFF', t), sink = (c, t) => mix(c, '#0B1A2A', t);
  const smooth = p => { let d = `M ${p[0][0]} ${p[0][1]}`; for (let i = 1; i < p.length - 1; i++) { const mx = (p[i][0] + p[i + 1][0]) / 2, my = (p[i][1] + p[i + 1][1]) / 2; d += ` Q ${p[i][0]} ${p[i][1]} ${mx.toFixed(1)} ${my.toFixed(1)}`; } const l = p[p.length - 1]; return d + ` L ${l[0]} ${l[1]}`; };
  const ridgeY = (p, x) => { for (let i = 0; i < p.length - 1; i++) if (x >= p[i][0] && x <= p[i + 1][0]) { const t = (x - p[i][0]) / (p[i + 1][0] - p[i][0]); return p[i][1] + (p[i + 1][1] - p[i][1]) * t; } return p[p.length - 1][1]; };
  const rnd = seed => () => (seed = (seed * 16807) % 2147483647) / 2147483647;

  const T = {
    dawn: { sky: ['#86AEDA', '#CFE0EE', '#F6D3B2'], sun: [86, 372, 24, '#FFE6BC', '#FFD49A'], cloud: ['#FFF6EC', '#EAD9CB'], rim: '#FFE9CF',
      hills: ['#CCD9E0', '#B7D0BC', '#A5C6AB', '#9CBFA4'], lake: { far: '#BAC8D6', mtn: '#A3B6C9', snow: '#FBEFE2', shade: '#C4CBD4', water: ['#F1DFCC', '#CBDAE3', '#AEC9D8'] } },
    day: { sky: ['#5FA8E6', '#A9D3F2', '#E6F3FB'], sun: [318, 112, 28, '#FFF6D6', '#FFE7A8'], cloud: ['#FFFFFF', '#D9E6F0'], rim: '#FFFFFF',
      hills: ['#C4DFEA', '#A8D1BC', '#93C5A9', '#86B9A0'], lake: { far: '#BCD5E6', mtn: '#ADC7DC', snow: '#F7FAFC', shade: '#C6D6E4', water: ['#D8ECF6', '#B6D8EA', '#9CC6DC'] } },
    dusk: { sky: ['#264B7D', '#8EA8C4', '#F5B386'], sun: [330, 392, 26, '#FFD7A8', '#FFB37A'], cloud: ['#FFE2C8', '#D3A58E'], rim: '#FFC9A0',
      hills: ['#7D93AE', '#5E7A8C', '#4D6878', '#3F5866'], lake: { far: '#8C9FB8', mtn: '#6B829F', snow: '#F4D6C1', shade: '#8995A8', water: ['#F0C8A9', '#7F9BB7', '#5E7C99'] } },
    night: { sky: ['#081427', '#13284A', '#24426A'], moon: [318, 104], cloud: ['#3A5274', '#25395A'], rim: '#6F8AAE',
      hills: ['#203852', '#1B3346', '#16293A', '#112131'], lake: { far: '#1E3555', mtn: '#22395A', snow: '#AEBFD2', shade: '#30496A', water: ['#26406A', '#1A2F4E', '#10223A'] } },
  };
  const weather = r => r == null ? { clouds: [[92, 214, .9], [292, 168, .8], [214, 262, .6]], haze: .12, grey: .05, label: 'neutral' }
    : r >= 80 ? { clouds: [[34, 128, .5], [318, 136, .4]], haze: 0, grey: 0, label: 'clear' }
    : r >= 65 ? { clouds: [[80, 200, .95], [262, 160, .85], [350, 236, .7]], haze: .1, grey: .12, label: 'some cloud' }
    : { clouds: [[40, 230, 1.25], [180, 196, 1.4], [330, 216, 1.3], [110, 288, 1.05], [280, 300, 1.1]], haze: .32, grey: .38, label: 'overcast' };

  function cloud(x, y, s, id) {
    const puffs = [[0, 0, 20], [22, -12, 26], [50, -4, 22], [72, 6, 16], [-20, 8, 15], [34, 10, 22]];
    return `<g transform="translate(${x} ${y}) scale(${s})" filter="url(#cs${id})"><ellipse cx="26" cy="20" rx="54" ry="10" fill="url(#cb${id})" opacity=".55"/>${puffs.map(([px, py, r]) => `<circle cx="${px}" cy="${py}" r="${r}" fill="url(#cg${id})"/>`).join('')}</g>`;
  }

  function sky(t, w, id, o) {
    const g = c => mix(c, '#A9B6C2', w.grey);
    const P = T[t];
    let s = `<linearGradient id="sk${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${g(P.sky[0])}"/><stop offset=".3" stop-color="${g(P.sky[1])}"/><stop offset=".5" stop-color="${g(P.sky[2])}"/></linearGradient>
      <linearGradient id="cg${id}" x1="0" y1="0" x2="0" y2="1"><stop offset=".2" stop-color="${P.cloud[0]}"/><stop offset="1" stop-color="${P.cloud[1]}"/></linearGradient>
      <radialGradient id="cb${id}"><stop offset="0" stop-color="${P.cloud[1]}"/><stop offset="1" stop-color="${P.cloud[1]}" stop-opacity="0"/></radialGradient>
      <filter id="cs${id}" x="-20%" y="-40%" width="140%" height="180%"><feGaussianBlur stdDeviation=".7"/></filter>
      <radialGradient id="gl${id}"><stop offset="0" stop-color="${P.sun ? P.sun[4] : '#9FB6D6'}" stop-opacity=".75"/><stop offset=".35" stop-color="${P.sun ? P.sun[4] : '#9FB6D6'}" stop-opacity=".25"/><stop offset="1" stop-color="${P.sun ? P.sun[4] : '#9FB6D6'}" stop-opacity="0"/></radialGradient>
      <linearGradient id="hz${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0"/><stop offset="1" stop-color="${t === 'night' ? '#4A6488' : '#FFFFFF'}" stop-opacity="${w.haze}"/></linearGradient>
      <filter id="gr${id}"><feTurbulence type="fractalNoise" baseFrequency=".85" numOctaves="2" seed="3"/><feColorMatrix values="0 0 0 0 .5  0 0 0 0 .5  0 0 0 0 .5  0 0 0 .07 0"/></filter>`;
    let body = `<rect width="412" height="915" fill="url(#sk${id})"/>`;
    if (P.sun && !o.noSun) body += `<circle cx="${P.sun[0]}" cy="${P.sun[1]}" r="${P.sun[2] * 4.2}" fill="url(#gl${id})" opacity="${1 - w.grey}"/><circle cx="${P.sun[0]}" cy="${P.sun[1]}" r="${P.sun[2]}" fill="${P.sun[3]}" opacity="${1 - w.grey * 1.6}"/>`;
    if (P.moon) { const r = rnd(9); for (let i = 0; i < 60; i++) body += `<circle cx="${(r() * 412).toFixed(0)}" cy="${(r() * 380).toFixed(0)}" r="${(.5 + r() * 1.1).toFixed(1)}" fill="#fff" opacity="${(.3 + r() * .6 - w.grey).toFixed(2)}"/>`;
      if (!o.noSun) body += `<circle cx="${P.moon[0]}" cy="${P.moon[1]}" r="80" fill="url(#gl${id})" opacity=".5"/><circle cx="${P.moon[0]}" cy="${P.moon[1]}" r="20" fill="#F1EBD9"/><circle cx="${P.moon[0] + 9}" cy="${P.moon[1] - 7}" r="17.5" fill="${mix(P.sky[0], P.sky[1], .25)}"/>`; }
    return { defs: s, body, clouds: w.clouds.map(c => cloud(c[0], c[1], c[2], id)).join('') };
  }

  function hills(t, w, id) {
    const P = T[t], L = P.hills.map(c => mix(c, '#A9B6C2', w.grey * .5)), sunSide = (P.sun || P.moon || [300])[0] > 206 ? 1 : -1;
    const R = [
      [[0, 392], [70, 370], [150, 384], [230, 360], [320, 378], [412, 356]],
      [[0, 448], [100, 414], [200, 438], [300, 418], [412, 442]],
      [[0, 562], [120, 522], [240, 546], [340, 528], [412, 550]],
      [[0, 692], [150, 654], [290, 678], [412, 662]],
    ];
    let defs = '', body = '';
    R.forEach((p, i) => {
      defs += `<linearGradient id="h${i}${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${lift(L[i], t === 'night' ? .06 : .14)}"/><stop offset=".35" stop-color="${L[i]}"/></linearGradient>`;
      body += `<path d="${smooth(p)} V915 H0Z" fill="url(#h${i}${id})"/><path d="${smooth(p)}" fill="none" stroke="${P.rim}" stroke-opacity="${t === 'night' ? .25 : .55}" stroke-width="1.4"/>`;
      if (i === 0 && w.haze) body += `<rect y="300" width="412" height="200" fill="url(#hz${id})"/>`;
      if (i === 1) { // tree line along the second ridge
        const r = rnd(5), tc = sink(L[1], t === 'night' ? .25 : .32);
        [[34, 1], [48, .8], [62, .9], [246, .85], [262, 1.1], [278, .8], [352, 1], [366, .75]].forEach(([x, s]) => {
          const y = ridgeY(p, x) + 2, cr = 7 * s;
          body += `<rect x="${x - .8}" y="${y - 4}" width="1.6" height="6" fill="${tc}"/><circle cx="${x}" cy="${y - 4 - cr}" r="${cr}" fill="${tc}"/><circle cx="${x + sunSide * cr * .35}" cy="${y - 4 - cr * 1.25}" r="${cr * .55}" fill="${lift(tc, t === 'night' ? .08 : .22)}"/>`;
        });
      }
      if (i === 3) { // a path winding into the near meadow
        body += `<path d="M 238 915 C 250 820 330 790 300 690 L 306 690 C 344 790 274 830 282 915 Z" fill="${lift(L[3], t === 'night' ? .05 : .18)}"/>`;
      }
    });
    return { defs, body };
  }

  function lake(t, w, id) {
    const P = T[t], K = P.lake, g = c => mix(c, '#A9B6C2', w.grey * .5), shore = 322;
    const far = [[0, 296], [40, 280], [86, 292], [140, 268], [190, 288], [246, 274], [300, 294], [352, 278], [412, 290]];
    const main = [[0, 312], [36, 290], [70, 262], [96, 276], [132, 226], [168, 270], [206, 248], [246, 196], [292, 252], [330, 236], [372, 280], [412, 268]];
    const peaks = [[70, 262], [132, 226], [206, 248], [246, 196], [330, 236]];
    const poly = p => p.map((q, i) => `${i ? 'L' : 'M'} ${q[0]} ${q[1]}`).join(' ');
    let defs = `<linearGradient id="m${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${lift(g(K.mtn), .12)}"/><stop offset="1" stop-color="${g(K.mtn)}"/></linearGradient>
      <linearGradient id="sn${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${K.snow}"/><stop offset="1" stop-color="${K.shade}"/></linearGradient>
      <linearGradient id="w${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${g(K.water[0])}"/><stop offset=".25" stop-color="${g(K.water[1])}"/><stop offset="1" stop-color="${g(K.water[2])}"/></linearGradient>
      <clipPath id="sc${id}"><path d="M0 0 H412 V258 L392 262 L368 252 L340 266 L318 258 L290 270 L262 236 L240 230 L222 270 L196 264 L170 252 L150 262 L124 248 L96 268 L76 278 L52 270 L0 276 Z"/></clipPath>
      <clipPath id="mc${id}"><path d="${poly(main)} V${shore} H0Z"/></clipPath>
      <filter id="rb${id}"><feGaussianBlur stdDeviation="1.6"/></filter>`;
    const range = `<path d="${poly(far)} V${shore} H0Z" fill="${mix(g(K.far), g(T[t].sky[2]), .25)}"/><path d="${poly(main)} V${shore} H0Z" fill="url(#m${id})"/>
      <g clip-path="url(#mc${id})"><path d="${poly(main)} V${shore} H0Z" fill="url(#sn${id})" clip-path="url(#sc${id})"/>${peaks.map(([x, y]) => `<path d="M ${x} ${y} L ${x + 46} ${shore} L ${x + 8} ${shore} Z" fill="${sink(g(K.mtn), .12)}" opacity=".35"/>`).join('')}</g>`;
    let body = range;
    if (w.haze) body += `<rect y="220" width="412" height="${shore - 220}" fill="url(#hz${id})"/>`;
    body += `<rect y="${shore}" width="412" height="${915 - shore}" fill="url(#w${id})"/>
      <g transform="translate(0 ${shore * 2}) scale(1 -1)" opacity="${t === 'night' ? .3 : .22}" filter="url(#rb${id})">${range}</g>`;
    const r = rnd(4);
    for (let i = 0; i < 16; i++) { const y = shore + 30 + r() * 520, x = r() * 380, l = 18 + r() * 52; body += `<rect x="${x.toFixed(0)}" y="${y.toFixed(0)}" width="${l.toFixed(0)}" height="1.4" rx=".7" fill="#FFFFFF" opacity="${t === 'night' ? .12 : .4}"/>`; }
    if (T[t].sun && (t === 'dusk' || t === 'dawn')) { const sx = T[t].sun[0]; for (let i = 0; i < 9; i++) body += `<rect x="${sx - 14 + r() * 10}" y="${shore + 8 + i * 14}" width="${18 + r() * 14}" height="2" rx="1" fill="${T[t].sun[3]}" opacity="${.7 - i * .06}"/>`; }
    // shoreline pines, kept to the edges so text never sits on them
    const pc = sink(g(K.mtn), t === 'night' ? .35 : .45);
    [[6, .9], [16, .7], [26, 1.05], [38, .8], [368, .85], [380, 1.1], [392, .75], [404, .9]].forEach(([x, s]) => { body += `<path d="M ${x} ${shore - 22 * s} L ${x + 6 * s} ${shore} L ${x - 6 * s} ${shore} Z" fill="${pc}"/>`; });
    body += `<rect y="${shore - 1}" width="412" height="2" fill="${lift(g(K.water[0]), .3)}" opacity=".7"/>`;
    return { defs, body };
  }

  window.SCENE = function (subject, time, readiness, o = {}) {
    const id = 'x' + (++N), w = weather(readiness), s = sky(time, w, id, o), land = (subject === 'lake' ? lake : hills)(time, w, id);
    return `<svg class="scene" viewBox="0 0 412 915" aria-hidden="true" preserveAspectRatio="xMidYMid slice"><defs>${s.defs}${land.defs}</defs>${s.body}${s.clouds}${land.body}<rect width="412" height="915" filter="url(#gr${id})" opacity=".9"/></svg>`;
  };
  window.SCENE_WEATHER = r => weather(r).label;
})();
