/* Round X1: the data syncs the transitions are shown on (x1-board.html, tools/visual-loop/x1.js). Each is a before and an after
   B1 day at one hour; the sky comes from BodySky, as in the app. */
window.X1_CASES = [
  { id: 'morning', label: 'Morning sync: short sleep arrives', hour: 7.5, from: 'firstRun', to: 'shortSleep', note: '07:30. Before the band syncs, the sky is a plain fair sky. Then last night’s short sleep arrives: valley mist and a partly cloudy sky.' },
  { id: 'ill', label: 'Signs of illness appear', hour: 13, from: 'good', to: 'ill', note: '13:00. A good day, then skin temperature and resting HR both come in high: light rain.' },
  { id: 'recover', label: 'Readiness recalculated: 61 → 86', hour: 13, from: 'veryLowHrv', to: 'good', note: '13:00. Broken cloud under a milky veil clears to a fair sky.' },
];
window.X1_state = (c, which) => {
  const d = B1_DAYS.find(x => x.id === c[which]), noData = !d.body.readiness, m = BodySky.bodySky(d.body, c.hour, SceneGL);
  return { r: noData ? null : d.body.readiness, set: { sky: m.sky, hour: c.hour, readiness: noData ? null : d.body.readiness } };
};
