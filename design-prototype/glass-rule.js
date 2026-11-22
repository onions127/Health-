/* Round G1: how the glass and the text follow the scene through dusk, night and dawn (shared by the board and the measuring tool).
   Cards: the page under them decides. pageL = L* of the page gradient under the cards (from the palette, or sampled).
     pageL >= FLIP: day glass (white tint, dark ink), strength from DAY; below: night glass (the option's dark tint, light ink), strength from NIGHT.
     Both tables are the measured smallest strength for AA (4.6) on the weakest text, plus .03, with the design's own floor (.22 by day, .40 at night).
   Sky text (header, hero): the sky behind it decides (the hero cloud is lit by day and by the moon). The UI takes the ink that needs the
   weaker scrim; the scrim is a soft veil behind the text in the glass's tint, as strong as measured + .03.
   G1 values (cards, OPTIONS) are kept for the r5a board only; the picked rule is G2 below. */
(function (G) {
  const lerpT = (T, x) => { if (x <= T[0][0]) return T[0][1]; for (let i = 1; i < T.length; i++) if (x <= T[i][0]) { const [a, va] = T[i - 1], [b, vb] = T[i]; return va + (vb - va) * (x - a) / (b - a); } return T[T.length - 1][1]; };
  const FLIP = 56;
  const DAY = [[47, .58], [55, .53], [59, .49], [63, .45], [67, .38], [70, .30], [72, .25], [74, .22]];
  const NIGHT = [[31, .40], [40, .40], [47, .45], [55, .55], [57, .57]];
  const OPTIONS = {
    A: { name: 'A · Navy glass', thesis: 'A deep navy tint, like the night cards in the reference. Calm and constant: the same blue at every hour and in every weather.', tint: () => '#0A1E4A' },
    B: { name: 'B · Smoke glass', thesis: 'A neutral smoked glass: almost no hue of its own, so the scene’s colour (violet dusk, slate rain, navy night) shows through it.', tint: () => '#0E1116' },
    C: { name: 'C · Sky glass', thesis: 'The glass takes the hue of the sky overhead at the navy’s darkness: navy on a clear night, violet-slate at dusk, blue-grey in rain. It always belongs to the sky above it.', tint: pal => skyTint(pal) },
  };
  const INK = { day: { primary: '#0E2A43', secondary: '#2E4A62', sky: '#0E2A43' }, night: { primary: '#FFFFFF', secondary: '#C9D7EA', sky: '#F2F6FB' } };
  const lin = v => (v /= 255) <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4, gam = v => Math.round(255 * (v <= .0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - .055));
  const hx = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16)), toHex = a => '#' + a.map(v => Math.max(0, Math.min(255, v)).toString(16).padStart(2, '0')).join('').toUpperCase();
  function skyTint(pal) { const z = hx(pal.zen).map(lin), Y = .2126 * z[0] + .7152 * z[1] + .0722 * z[2]; return toHex(z.map(v => gam(v * .013 / Math.max(Y, 1e-4)))); }   // the zenith's hue at Y .013
  function cards(pageL, opt, pal) {
    const night = pageL < FLIP;
    return night ? { mode: 'night', tint: OPTIONS[opt].tint(pal), a: lerpT(NIGHT, pageL), ink: INK.night, nav: { a: .72 } }
                 : { mode: 'day', tint: '#FFFFFF', a: Math.max(.22, lerpT(DAY, pageL)), ink: INK.day, nav: { a: .55 } };
  }
  /* Round G2 (picked 2026-11-22): scene glass, contrast floor 3.0 for all text (AA large-text level), measured by tools/visual-loop/g2.js.
     The glass is only as strong as the floor needs. Day glass: white, >= 22%, saturate 1.6, dark ink. Night glass: the land's own hue
     under the cards at Y .02 (chroma .8), 22% (the day glass's strength), saturate 1, faint light edge, light ink. Mode = the ink that needs
     less glass (from the measurements: day down to page L* 55, night from 46; FLIP2 sits between). Sky text: the ink that needs the weaker
     veil, veil in the same tint at the measured need + .03 (G3 scene: 0 in every measured state; 5-14% only around 20:00, when the ink swaps). Nav >= 30%.
     Log weight button: blue #0B5FA5, white pill #FFFFFF (ink #0B3A66) or navy #082F5A, whichever reaches 3:1 against the sky behind it (G3: the pale pill #DCE9F7 failed once the veil went). */
  const FLOOR = 3.0, FLIP2 = 50;
  const DAY2 = [[50, .31], [55, .27], [60, .24], [65, .22]], NIGHT2_A = .22, SAT = { day: 1.6, night: 1 };
  const EDGE = 'inset 0 0 0 1px rgba(255,255,255,.10), inset 0 1px 0 rgba(255,255,255,.08), 0 10px 28px rgba(0,0,0,.12)';
  function landTint(pgLin) { const Y = .2126 * pgLin[0] + .7152 * pgLin[1] + .0722 * pgLin[2]; return toHex(pgLin.map(v => gam(Math.min(1, .02 * (.8 * v / Math.max(Y, 1e-5) + .2))))); }
  function cards2(pageL, pgLin) {
    return pageL >= FLIP2 ? { mode: 'day', tint: '#FFFFFF', a: Math.max(.22, lerpT(DAY2, pageL)), sat: SAT.day, ink: INK.day, nav: { a: .55 } }
                          : { mode: 'night', tint: landTint(pgLin), a: NIGHT2_A, sat: SAT.night, edge: EDGE, ink: INK.night, nav: { a: .30 } };
  }
  G.GlassRule = { FLIP, DAY, NIGHT, OPTIONS, INK, cards, skyTint, lerpT, G2: { FLOOR, FLIP: FLIP2, DAY: DAY2, NIGHT_A: NIGHT2_A, SAT, EDGE, landTint, cards: cards2 } };
})(typeof window !== 'undefined' ? window : globalThis);
