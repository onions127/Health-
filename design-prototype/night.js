// Round NT1: any screen board opened with ?night=1 draws its screen at 23:10 on night glass (G2 rule).
// Values measured with tools/visual-loop/g2.js into nt1/measure.json (fair, 23:10, all screens' glass areas);
// sky text must stay left of the moon (x <= 262): with that, light ink and no veil.
// Round DD1: ?t=dawn (06:30) or ?t=dusk (19:45) draws the screen at that hour; both keep the white day glass and dark ink
// (dd1/measure.json: g2.js MODE=light over Settings', Log weight's and the metric pages' glass), dusk at 34% with white buttons and darker green/coral.
window.TIME = (location.search.match(/[?&]t=(dawn|dusk|night)/) || [])[1] || (/[?&]night=1/.test(location.search) ? 'night' : 'day');
window.NIGHT = window.TIME === 'night';
window.ALT = window.TIME !== 'day';
window.NIGHT_G = { mode: 'dark', tint: '#152256', a: .22, navA: .3, ink: { primary: '#FFFFFF', secondary: '#C9D7EA', sky: '#F2F6FB' }, skyInk: { primary: '#FFFFFF', secondary: '#C9D7EA', sky: '#F2F6FB' },
  cardP: 10.16, cardS: 6.96, navS: 9.82, skyMode: 'dark', scrimT: '#152256', scrimA: 0, sky: 4.11, hero: 4.11, btn: { fill: '#FFFFFF', ink: '#0B3A66' }, btnC: 8.62 };
window.NIGHT_IMG = 'nt1/night.png';
window.CLOCK = { day: '15:10', dawn: '06:30', dusk: '19:45', night: '23:10' }[window.TIME];
window.SYNC = { day: '15:02 · next sync 15:32', dawn: '06:22 · next 06:52', dusk: '19:37 · next 20:07', night: '23:02 · next 23:32' }[window.TIME];
window.DD_G = { dawn: {"mode":"light","tint":"#FFFFFF","a":0.22,"navA":0.3,"ink":{"primary":"#0E2A43","secondary":"#2E4A62","sky":"#0E2A43"},"skyInk":{"primary":"#0E2A43","secondary":"#2E4A62","sky":"#0E2A43"},"cardP":5.54,"cardS":3.49,"navS":5.78,"skyMode":"light","scrimT":"#FFFFFF","scrimA":0,"sky":7.46,"hero":7.59,"btn":{"fill":"#0B5FA5","ink":"#FFFFFF"},"btnC":3.23}, dusk: {"mode":"light","tint":"#FFFFFF","a":0.34,"navA":0.3,"ink":{"primary":"#0E2A43","secondary":"#2E4A62","sky":"#0E2A43"},"skyInk":{"primary":"#0E2A43","secondary":"#2E4A62","sky":"#0E2A43"},"cardP":5.06,"cardS":3.18,"navS":3.29,"skyMode":"light","scrimT":"#FFFFFF","scrimA":0,"sky":3.88,"hero":3.33,"btn":{"fill":"#FFFFFF","ink":"#0B3A66"},"btnC":3.14} };
window.ALT_G = window.NIGHT ? window.NIGHT_G : window.DD_G[window.TIME];
window.ALT_IMG = window.NIGHT ? window.NIGHT_IMG : 'dd1/' + { dawn: 'dawn0630', dusk: 'dusk1945' }[window.TIME] + '.png';
// good/bad words: day colours hold at dawn (3.74 / 3.61); at dusk they go darker to stay at 3.0 (3.19 / 3.30), TR2's band method
window.TONE = { day: ['#17703C', '#B23A2E'], dawn: ['#17703C', '#B23A2E'], dusk: ['#0E5A2C', '#8A2A20'], night: ['#7BE3A4', '#FFA095'] }[window.TIME];
window.SKY_RIGHT = r => window.NIGHT ? 150 : r;   // right inset for sky text: 412 - 150 = 262
// the glass's filter and edge: day = white frost and soft shadow; night = the land's hue, no extra saturation, a faint light edge
window.glassFx = g => g.mode === 'dark' ? '-webkit-backdrop-filter:blur(22px) saturate(1);backdrop-filter:blur(22px) saturate(1);box-shadow:inset 0 0 0 1px rgba(255,255,255,.10), inset 0 1px 0 rgba(255,255,255,.08), 0 10px 28px rgba(0,0,0,.12)'
  : '-webkit-backdrop-filter:blur(22px) saturate(1.6);backdrop-filter:blur(22px) saturate(1.6);box-shadow:0 1px 1px rgba(14,42,67,.05), 0 10px 28px rgba(14,42,67,.10)';
window.navEdge = g => g.mode === 'dark' ? 'rgba(255,255,255,.14)' : 'rgba(255,255,255,.6)';
// Phone check (PH1): ?solo=N shows only the Nth phone of a board, scaled to the device's width, for a real-screen look.
(() => { const m = location.search.match(/[?&]solo=(\d+)/); if (!m) return;
  const go = () => { const ph = document.querySelectorAll('.phone')[+m[1]]; if (!ph) return setTimeout(go, 150);
    const c = ph.cloneNode(true); document.body.innerHTML = ''; document.body.style.cssText = 'margin:0;padding:0;background:#000;height:300vh';   // tall, so a swipe hides Chrome's address bar
    const fit = () => { const z = Math.min(innerWidth / 412, innerHeight / 915); c.style.cssText += `;position:fixed;top:0;left:${(innerWidth - 412 * z) / 2 / z}px;zoom:${z};border-radius:0;box-shadow:none`; };
    document.body.appendChild(c); fit(); addEventListener('resize', fit); document.title = 'solo ready'; };
  addEventListener('load', () => setTimeout(go, 500)); })();
// Metric pages (MD1-MD4): the cards under the range control stack at the standard 12 px gap (Samsung proportions, 3.16),
// whatever their content's height; a stand-in for the app's Column(spacedBy(12.dp)).
window.stackCards = (from = 440, gap = 12) => document.querySelectorAll('.phone').forEach(ph => {
  const cs = [...ph.querySelectorAll(':scope > .glass')].filter(g => g.offsetTop >= from).sort((a, b) => a.offsetTop - b.offsetTop);
  cs.forEach((g, i) => { if (i) g.style.top = cs[i - 1].offsetTop + cs[i - 1].offsetHeight + gap + 'px'; }); });
