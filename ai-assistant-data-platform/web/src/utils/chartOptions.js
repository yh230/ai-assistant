export function buildTrendOption(rows) {
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: rows.map((row) => row.date) },
    yAxis: { type: 'value' },
    series: [{ type: 'line', smooth: true, areaStyle: {}, data: rows.map((row) => row.value) }],
  };
}

export function buildRankingOption(rows) {
  const sorted = [...rows].sort((a, b) => b.value - a.value);
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 20, top: 20, bottom: 20 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: sorted.map((row) => row.name) },
    series: [{ type: 'bar', data: sorted.map((row) => row.value) }],
  };
}

export function formatMetric(value, digits = 0) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '0';
  }
  return Number(value).toFixed(digits);
}

/**
 * Render an API timestamp (ISO or "YYYY-MM-DD HH:MM:SS") for a table cell.
 *
 * The governance APIs serialize datetimes as ISO strings; trimming the
 * timezone suffix keeps the column readable without re-interpreting the value
 * in the browser time zone.
 */
export function formatDateTime(value) {
  if (!value) {
    return '-';
  }
  return String(value)
    .replace('T', ' ')
    .replace(/\.\d+/, '')
    .replace(/(?:Z|[+-]\d{2}:?\d{2})$/, '')
    .trim();
}
