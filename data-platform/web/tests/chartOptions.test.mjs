import test from 'node:test';
import assert from 'node:assert/strict';

import { buildRankingOption, buildTrendOption, formatDateTime, formatMetric } from '../src/utils/chartOptions.js';

test('buildTrendOption maps rows into categories and values', () => {
  const option = buildTrendOption([{ date: '2026-11-03', value: 8 }, { date: '2026-11-04', value: 12 }]);
  assert.deepEqual(option.xAxis.data, ['2026-11-03', '2026-11-04']);
  assert.deepEqual(option.series[0].data, [8, 12]);
});

test('buildRankingOption sorts and maps names', () => {
  const option = buildRankingOption([{ name: '内科', value: 12 }, { name: '外科', value: 8 }]);
  assert.deepEqual(option.yAxis.data, ['内科', '外科']);
  assert.deepEqual(option.series[0].data, [12, 8]);
});

test('formatMetric applies digits and fallback', () => {
  assert.equal(formatMetric(1234.5, 1), '1234.5');
  assert.equal(formatMetric(null), '0');
});

test('buildTrendOption tolerates an empty window', () => {
  const option = buildTrendOption([]);
  assert.deepEqual(option.xAxis.data, []);
  assert.deepEqual(option.series[0].data, []);
});

test('buildRankingOption leaves the input rows untouched', () => {
  const rows = [{ name: '外科', value: 8 }, { name: '内科', value: 12 }];
  const option = buildRankingOption(rows);
  assert.deepEqual(option.yAxis.data, ['内科', '外科']);
  assert.deepEqual(option.series[0].data, [12, 8]);
  assert.deepEqual(rows.map((row) => row.name), ['外科', '内科']);
});

test('formatMetric handles digits, strings and non-numeric input', () => {
  assert.equal(formatMetric(98.645, 2), '98.64');
  assert.equal(formatMetric('12'), '12');
  assert.equal(formatMetric('n/a'), '0');
  assert.equal(formatMetric(undefined, 3), '0');
});

test('formatDateTime renders the platform wall clock without a timezone shift', () => {
  // Metadata timestamps are already Asia/Shanghai wall-clock values; they must
  // not be re-interpreted by the browser timezone.
  assert.equal(formatDateTime('2026-11-04T12:00:00'), '2026-11-04 12:00:00');
  assert.equal(formatDateTime('2026-11-04T12:00:00Z'), '2026-11-04 12:00:00');
  assert.equal(formatDateTime('2026-11-04T12:00:00+08:00'), '2026-11-04 12:00:00');
  assert.equal(formatDateTime('2026-11-04 12:00:00'), '2026-11-04 12:00:00');
  assert.equal(formatDateTime(null), '-');
});
