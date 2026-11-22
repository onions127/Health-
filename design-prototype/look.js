// Cloud look-dev helper (3.14): load phone.html from bake-server (8768), then  await LOOK.shot('name', { hour, ...weather })
// renders that state to convergence and saves the full-resolution canvas to baked/_look/name.png.
window.LOOK = {
  async shot(name, o = {}) {
    const S = PHONE.S; S.set(o);
    await new Promise(r => { const t = setInterval(() => { if (S.ready) { clearInterval(t); r(); } }, 100); });
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    const b = await new Promise(r => S.canvas.toBlob(r, 'image/png'));
    await fetch(`baked/_look/${name}.png`, { method: 'PUT', body: b });
    return `${name}: ${S.canvas.width}x${S.canvas.height}`;
  },
  // the standard look set: partly cloudy midday (two close-ups), fair evening, stratocumulus
  async run(pre) {
    await this.shot(pre + '-partly13', { hour: 13, readiness: 70 });
    await this.crop(pre + '-partly13-a', 0, 520, 560, 460); await this.crop(pre + '-partly13-b', 420, 330, 404, 480);
    await this.shot(pre + '-fair1745', { hour: 17.75, readiness: 86 });
    await this.shot(pre + '-strato13', { hour: 13, readiness: 55 });
    return pre;
  },
  // save a region (device px) of the current canvas, for looking at cloud detail at full resolution
  async crop(name, x, y, w, h) {
    const c = Object.assign(document.createElement('canvas'), { width: w, height: h });
    c.getContext('2d').drawImage(PHONE.S.canvas, x, y, w, h, 0, 0, w, h);
    await fetch(`baked/_look/${name}.png`, { method: 'PUT', body: await new Promise(r => c.toBlob(r, 'image/png')) });
    return name;
  },
};
