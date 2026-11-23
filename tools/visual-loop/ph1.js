// Round PH1: the new screens on the Galaxy S21 (Chrome over adb), one per view with ?solo=N, into out/ph1-<name>.png.
// Needs: adb reverse tcp:8765 tcp:8765; design-board on :8765; phone unlocked.   node ph1.js [name-filter]
const { execSync } = require('child_process'); const path = require('path'), fs = require('fs');
const SHOTS = [
  ['coach-0715', 'co2-board.html', 0, ''], ['coach-1945', 'co2-board.html', 2, ''], ['coach-2310', 'co2-board.html', 3, ''],
  ['settings-day', 'se1-board.html', 0, ''], ['settings-dusk', 'se1-board.html', 0, 't=dusk'], ['settings-night', 'se1-board.html', 0, 't=night'],
  ['weight-day', 'wt1-board.html', 2, ''], ['weight-dawn', 'wt1-board.html', 2, 't=dawn'],
  ['readiness-day', 'md3-board.html', 0, ''], ['steps-day', 'md4-board.html', 3, ''], ['steps-dusk', 'md4-board.html', 3, 't=dusk'], ['spo2-night', 'md4-board.html', 5, 't=night'],
];
const sleep = ms => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms), f = process.argv[2] || '';
execSync('adb shell input keyevent KEYCODE_WAKEUP');
for (const [name, board, i, q] of SHOTS.filter(s => s[0].includes(f))) {
  const url = `http://localhost:8765/${board}?solo=${i}${q ? '&' + q : ''}&v=${Date.now()}`;
  execSync(`adb shell am start -a android.intent.action.VIEW -d "'${url}'" com.android.chrome`); sleep(6000);
  execSync('adb shell input swipe 540 1700 540 1000 300'); sleep(1500);   // hides the address bar; the page refits
  fs.writeFileSync(path.join(__dirname, 'out', `ph1-${name}.png`), execSync('adb exec-out screencap -p', { maxBuffer: 64 << 20 }));
  console.log(name);
}
