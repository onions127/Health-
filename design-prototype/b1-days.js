/* Round B1: example days for the sky lab (shot by tools/visual-loop/b1.js, shown by b1-board.html). Usual values follow data.js. */
window.B1_DAYS = (() => {
  const U = { readinessUsual: 79, sleepUsual: 440, hrvMin: 55, hrvMax: 63, stepsUsual: 8240, strainUsual: 11, rhrUsual: 52 };
  const ok = { readiness: 86, sleepScore: 84, sleepMin: 450, hrv: 60, steps: 3400, strain: 7, skinTemp: 0, rhr: 52 };
  const d = (id, label, hour, b) => ({ id, label, hour, body: b ? Object.assign({}, U, ok, b) : {} });
  return [
    d('good', 'A good day', 13, {}),
    d('shortSleep', 'Short sleep, morning', 7.5, { readiness: 72, sleepScore: 52, sleepMin: 340, steps: 900 }),
    d('shortSleepNoon', 'Same day, noon', 12, { readiness: 72, sleepScore: 52, sleepMin: 340, steps: 2600 }),
    d('lowHrv', 'HRV under normal', 13, { readiness: 68, hrv: 52 }),
    d('veryLowHrv', 'HRV well under normal', 13, { readiness: 61, hrv: 44 }),
    d('training', 'Heavy training, afternoon', 16, { readiness: 84, steps: 14200, strain: 16 }),
    d('load', 'Body under load', 13, { readiness: 70, skinTemp: .3, rhr: 55 }),
    d('ill', 'Likely illness', 10, { readiness: 52, sleepScore: 60, sleepMin: 400, skinTemp: .6, rhr: 59 }),
    d('storm', 'Likely illness, readiness 30', 18, { readiness: 30, sleepScore: 48, sleepMin: 330, hrv: 41, skinTemp: .8, rhr: 61 }),
    d('firstRun', 'First run, no data', 13, null),
  ];
})();
