// Round NT1: any screen board opened with ?night=1 draws its screen at 23:10 on night glass (G2 rule).
// Values measured with tools/visual-loop/g2.js into nt1/measure.json (fair, 23:10, all screens' glass areas);
// sky text must stay left of the moon (x <= 262): with that, light ink and no veil.
window.NIGHT = /[?&]night=1/.test(location.search);
window.NIGHT_G = { mode: 'dark', tint: '#152256', a: .22, navA: .3, ink: { primary: '#FFFFFF', secondary: '#C9D7EA', sky: '#F2F6FB' }, skyInk: { primary: '#FFFFFF', secondary: '#C9D7EA', sky: '#F2F6FB' },
  cardP: 10.16, cardS: 6.96, navS: 9.82, skyMode: 'dark', scrimT: '#152256', scrimA: 0, sky: 4.11, hero: 4.11, btn: { fill: '#FFFFFF', ink: '#0B3A66' }, btnC: 8.62 };
window.NIGHT_IMG = 'nt1/night.png';
window.CLOCK = window.NIGHT ? '23:10' : '15:10';
window.SKY_RIGHT = r => window.NIGHT ? 150 : r;   // right inset for sky text: 412 - 150 = 262
// the glass's filter and edge: day = white frost and soft shadow; night = the land's hue, no extra saturation, a faint light edge
window.glassFx = g => g.mode === 'dark' ? '-webkit-backdrop-filter:blur(22px) saturate(1);backdrop-filter:blur(22px) saturate(1);box-shadow:inset 0 0 0 1px rgba(255,255,255,.10), inset 0 1px 0 rgba(255,255,255,.08), 0 10px 28px rgba(0,0,0,.12)'
  : '-webkit-backdrop-filter:blur(22px) saturate(1.6);backdrop-filter:blur(22px) saturate(1.6);box-shadow:0 1px 1px rgba(14,42,67,.05), 0 10px 28px rgba(14,42,67,.10)';
window.navEdge = g => g.mode === 'dark' ? 'rgba(255,255,255,.14)' : 'rgba(255,255,255,.6)';
