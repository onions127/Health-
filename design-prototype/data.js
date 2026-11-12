// Realistic 90-day biometric dataset for Health++ (Aug 15 – Nov 12)
// Rounding rules: HR & HRV whole numbers, SpO2 whole %, temp 1 decimal, sleep "7h 20m".

(function () {
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const DOWS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  // Deterministic pseudo-random generator for reproducible 90-day curves
  let seed = 4219;
  function rand() {
    seed = (seed * 16807) % 2147483647;
    return (seed - 1) / 2147483646;
  }

  function formatSleep(totalMin) {
    const m = Math.round(totalMin);
    const hrs = Math.floor(m / 60);
    const mins = m % 60;
    return `${hrs}h ${mins < 10 ? '0' + mins : mins}m`;
  }

  // End date: Thu 12 Nov 2026 (index 89)
  // Note: Nov 12, 2026 is a Thursday. Nov 4, 2026 is a Wednesday in real calendar,
  // but prompt specifically names "Tue 4 Nov" as the historical calendar day label.
  // We format index 81 (Nov 4) explicitly as "Tue 4 Nov" to match the specification.
  const endDate = new Date(2026, 10, 12); // Nov 12
  const days = [];

  for (let i = 0; i < 90; i++) {
    const d = new Date(endDate);
    d.setDate(endDate.getDate() - (89 - i));
    const dayNum = d.getDate();
    const monthName = MONTHS[d.getMonth()];
    const dowName = (d.getMonth() === 10 && dayNum === 4) ? 'Tue' : DOWS[d.getDay()];
    const label = `${dowName} ${dayNum} ${monthName}`;
    const shortDate = `${dayNum} ${monthName}`;

    // Smooth physiological wave + slight noise
    const wave = Math.sin(i / 4.5) * 3.5 + Math.cos(i / 9.0) * 2.0;
    let hrv = Math.round(59 + wave + (rand() - 0.5) * 5);
    let rhr = Math.round(53 - wave * 0.45 + (rand() - 0.5) * 2);
    let sleepMin = Math.round(432 + wave * 6 + (rand() - 0.5) * 26);
    let sleepScore = Math.min(96, Math.max(48, Math.round((sleepMin - 300) * 0.42 + (rand() - 0.5) * 6)));
    let strain = Math.round(74 + Math.sin(i / 3.2) * 10 + (rand() - 0.5) * 8);
    let steps = Math.round(8100 + Math.sin(i / 3.2) * 1600 + (rand() - 0.5) * 1200);
    let spo2 = Math.round(97 + (rand() - 0.45) * 2);
    if (spo2 > 99) spo2 = 99;
    if (spo2 < 95) spo2 = 96;
    let skinTemp = Math.round(((rand() - 0.5) * 0.4) * 10) / 10;

    let outlierMetric = null;

    // Inject realistic outliers across 90 days
    if (i === 24) {
      // Late Aug hard travel day
      hrv = 45;
      rhr = 59;
      sleepMin = 355;
      sleepScore = 54;
      outlierMetric = 'hrv';
    } else if (i === 52) {
      // Late Sep illness / temp spike
      hrv = 44;
      rhr = 60;
      skinTemp = 0.8;
      spo2 = 93;
      sleepMin = 365;
      sleepScore = 56;
      outlierMetric = 'skinTemp';
    } else if (i === 74) {
      // Oct 28 peak recovery day
      hrv = 69;
      rhr = 49;
      sleepMin = 475;
      sleepScore = 91;
      outlierMetric = 'hrv';
    } else if (i === 81) {
      // Tue 4 Nov — poor sleep & low HRV outlier (featured in Calendar tap)
      hrv = 46;
      rhr = 58;
      sleepMin = 365; // 6h 05m
      sleepScore = 58;
      strain = 49;
      steps = 4820;
      spo2 = 96;
      skinTemp = 0.4;
      outlierMetric = 'hrv';
    } else if (i === 86) {
      // Nov 9 — high strain race day
      strain = 92;
      steps = 13450;
      hrv = 53;
      rhr = 56;
      outlierMetric = 'strain';
    } else if (i === 88) {
      // Yesterday (Wed 11 Nov)
      hrv = 62;
      rhr = 53;
      sleepMin = 435;
      sleepScore = 80;
      strain = 76;
      steps = 8190;
      spo2 = 98;
      skinTemp = 0.0;
    } else if (i === 89) {
      // Today (Thu 12 Nov) — optimal morning baseline
      hrv = 64;
      rhr = 52;
      sleepMin = 440; // 7h 20m
      sleepScore = 82;
      strain = 78;
      steps = 8420;
      spo2 = 98;
      skinTemp = 0.1;
    }

    const readiness = Math.min(98, Math.max(38, Math.round(hrv * 0.85 + (100 - rhr) * 0.65)));
    const recovery = Math.min(98, Math.max(36, Math.round(hrv * 0.92 + (90 - rhr) * 0.72)));
    const dailyScore = Math.round(readiness * 0.42 + sleepScore * 0.36 + strain * 0.22);

    days.push({
      index: i,
      dateObj: d,
      dayNum,
      month: d.getMonth(),
      monthName,
      dowName,
      label,
      shortDate,
      dailyScore: i === 89 ? 84 : (i === 81 ? 56 : dailyScore),
      recovery: i === 89 ? 86 : (i === 81 ? 52 : recovery),
      readiness: i === 89 ? 86 : (i === 81 ? 54 : readiness),
      sleepScore,
      sleepMin,
      sleepFormatted: formatSleep(sleepMin),
      strain,
      hrv,
      rhr,
      steps,
      spo2,
      skinTemp,
      outlierMetric
    });
  }

  // Compute 3-day rolling averages for every numeric metric
  const metricKeys = ['dailyScore', 'recovery', 'readiness', 'sleepScore', 'sleepMin', 'strain', 'hrv', 'rhr', 'steps', 'spo2', 'skinTemp'];
  days.forEach((day, idx) => {
    day.rolling = {};
    metricKeys.forEach((k) => {
      const start = Math.max(0, idx - 2);
      const slice = days.slice(start, idx + 1);
      const avg = slice.reduce((sum, item) => sum + item[k], 0) / slice.length;
      day.rolling[k] = k === 'skinTemp' ? Math.round(avg * 10) / 10 : Math.round(avg);
    });
  });

  // Metadata and normal corridors for each metric
  const METRICS = {
    hrv: {
      id: 'hrv',
      title: 'Heart Rate Variability',
      shortTitle: 'HRV',
      unit: 'ms',
      current: 64,
      avg30: 59,
      normalMin: 55,
      normalMax: 63,
      normalText: '55–63 ms normal',
      context: '↑ 8% vs normal',
      trendDir: 'up',
      color: '#34D399',
      format: (v) => `${Math.round(v)} ms`,
      outlierCheck: (v) => v < 50 || v > 67,
      outlierContext: (v) => v < 50 ? 'Lowest in 2 weeks' : 'Peak recovery day'
    },
    rhr: {
      id: 'rhr',
      title: 'Resting Heart Rate',
      shortTitle: 'Resting HR',
      unit: 'bpm',
      current: 52,
      avg30: 54,
      normalMin: 51,
      normalMax: 56,
      normalText: '51–56 bpm normal',
      context: '↓ 2 bpm vs normal',
      trendDir: 'down-good',
      color: '#34D399',
      format: (v) => `${Math.round(v)} bpm`,
      outlierCheck: (v) => v > 57 || v < 50,
      outlierContext: (v) => v > 57 ? 'Elevated overnight' : 'Lowest in 3 weeks'
    },
    sleep: {
      id: 'sleep',
      dataKey: 'sleepMin',
      title: 'Sleep',
      shortTitle: 'Sleep',
      unit: '',
      current: 440,
      currentFormatted: '7h 20m',
      score: 82,
      avg30Formatted: '7h 12m',
      normalMin: 410,
      normalMax: 465,
      normalText: '6h 50m–7h 45m normal',
      context: '↑ 4 vs normal',
      trendDir: 'up',
      color: '#34D399',
      format: (v) => formatSleep(v),
      outlierCheck: (v) => v < 380 || v > 470,
      outlierContext: (v) => v < 380 ? 'Short night' : 'Extended rest'
    },
    readiness: {
      id: 'readiness',
      title: 'Readiness',
      shortTitle: 'Readiness',
      unit: '',
      current: 86,
      avg30: 79,
      normalMin: 72,
      normalMax: 85,
      normalText: '72–85 normal',
      context: '↑ 5 vs normal',
      trendDir: 'up',
      color: '#34D399',
      format: (v) => `${Math.round(v)}`,
      outlierCheck: (v) => v < 60 || v > 90,
      outlierContext: (v) => v < 60 ? 'Below normal range' : 'Above normal range'
    },
    strain: {
      id: 'strain',
      title: 'Daily Strain',
      shortTitle: 'Strain',
      unit: '',
      current: 78,
      avg30: 75,
      normalMin: 65,
      normalMax: 82,
      normalText: '65–82 normal',
      context: 'Within normal',
      trendDir: 'steady',
      color: '#FFB86B',
      format: (v) => `${Math.round(v)}`,
      outlierCheck: (v) => v > 88 || v < 52,
      outlierContext: (v) => v > 88 ? 'High output day' : 'Light recovery day'
    },
    steps: {
      id: 'steps',
      title: 'Daily Steps',
      shortTitle: 'Steps',
      unit: 'steps',
      current: 8420,
      avg30: 8240,
      normalMin: 7000,
      normalMax: 10000,
      normalText: '7k–10k normal',
      context: 'Within normal',
      trendDir: 'steady',
      color: '#34D399',
      format: (v) => `${Math.round(v).toLocaleString()}`,
      outlierCheck: (v) => v < 5500 || v > 12000,
      outlierContext: (v) => v > 12000 ? 'Highest in 2 weeks' : 'Below normal range'
    },
    spo2: {
      id: 'spo2',
      title: 'Blood Oxygen',
      shortTitle: 'SpO2',
      unit: '%',
      current: 98,
      alertValue: 91,
      avg30: 98,
      normalMin: 95,
      normalMax: 99,
      normalText: '95–99% normal',
      context: 'Within normal',
      alertContext: '↓ 7% below normal',
      trendDir: 'steady',
      color: '#34D399',
      format: (v) => `${Math.round(v)}%`,
      outlierCheck: (v) => v < 95,
      outlierContext: () => 'Low overnight reading'
    },
    skinTemp: {
      id: 'skinTemp',
      title: 'Skin Temperature',
      shortTitle: 'Skin Temp',
      unit: '°C',
      current: 0.1,
      avg30: 0.0,
      normalMin: -0.3,
      normalMax: 0.3,
      normalText: '±0.3 °C normal',
      context: 'Within normal',
      trendDir: 'steady',
      color: '#34D399',
      format: (v) => `${v > 0 ? '+' : ''}${Number(v).toFixed(1)} °C`,
      outlierCheck: (v) => Math.abs(v) > 0.4,
      outlierContext: () => 'Above normal band'
    }
  };

  // Sleep hypnogram stages for Today (23:10 – 06:30, total 7h 20m = 440m)
  // Levels: 0 = Awake, 1 = REM, 2 = Light, 3 = Deep
  const HYPNOGRAM = [
    { start: 0, dur: 12, stage: 'Awake', level: 0 },
    { start: 12, dur: 38, stage: 'Light', level: 2 },
    { start: 50, dur: 50, stage: 'Deep', level: 3 },
    { start: 100, dur: 30, stage: 'Light', level: 2 },
    { start: 130, dur: 25, stage: 'REM', level: 1 },
    { start: 155, dur: 40, stage: 'Light', level: 2 },
    { start: 195, dur: 45, stage: 'Deep', level: 3 },
    { start: 240, dur: 10, stage: 'Awake', level: 0 },
    { start: 250, dur: 45, stage: 'Light', level: 2 },
    { start: 295, dur: 45, stage: 'REM', level: 1 },
    { start: 340, dur: 52, stage: 'Light', level: 2 },
    { start: 392, dur: 40, stage: 'REM', level: 1 },
    { start: 432, dur: 30, stage: 'Light', level: 2 }
  ];

  const SLEEP_STAGES = [
    { name: 'Deep', duration: '1h 35m', pct: 22, context: '↑ 12m vs normal', color: '#6366F1' },
    { name: 'REM', duration: '1h 50m', pct: 25, context: 'Within normal', color: '#38BDF8' },
    { name: 'Light', duration: '3h 55m', pct: 48, context: 'Within normal', color: '#34D399' },
    { name: 'Awake', duration: '22m', pct: 5, context: '↓ 8m vs normal', color: '#FB7185' }
  ];

  const COACH_RESPONSES = {
    'Can I push hard today?': [
      'Yes. Your HRV reached 64 ms, 8% above normal.',
      'Deep sleep hit 1h 35m and RHR dropped to 52 bpm.',
      'Target high-intensity intervals or heavy lifting today.'
    ],
    'Why did my HRV rise?': [
      'You fell asleep 25 minutes earlier than your usual bedtime.',
      'Late-evening heart rate stayed low at 51 bpm.',
      'Yesterday’s moderate strain let your nervous system rebound.'
    ],
    'How was my deep sleep?': [
      'You got 1h 35m of deep sleep, 12m above normal.',
      'Most of it happened in your first two sleep cycles.',
      'That drove your 86 Recovery score this morning.'
    ]
  };

  window.HEALTH_DATA = {
    days,
    today: days[89],
    historyDayNov4: days[81],
    METRICS,
    HYPNOGRAM,
    SLEEP_STAGES,
    COACH_RESPONSES,
    formatSleep
  };
})();
