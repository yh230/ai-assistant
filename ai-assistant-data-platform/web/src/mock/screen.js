export const overview = {
  kpis: [
    { key: 'reg_count', label: '今日挂号量', value: 128 },
    { key: 'ai_sessions', label: '今日 AI 会话', value: 56 },
    { key: 'quality_score', label: '数据质量分', value: 98.6 },
    { key: 'lag_seconds', label: '链路延迟(秒)', value: 12 },
    { key: 'open_alerts', label: '质量异常告警', value: 1 },
    { key: 'cancel_rate', label: '今日取消率', value: 0.0432 },
  ],
  raw: {},
};

export const trend = [
  { date: '2026-11-03', value: 96 },
  { date: '2026-11-04', value: 128 },
];

export const ranking = {
  hospitals: [{ hospital_id: 1, name: '第一医院', value: 128 }],
  departments: [{ department_id: 2, name: '内科', value: 64 }],
};

export const quality = {
  health: { quality_score: 0.986, lag_seconds: 12, open_alerts: 1 },
  alerts: [{ severity: 'P0', title: '链路延迟', checked_at: '2026-11-04 12:00:00' }],
};
