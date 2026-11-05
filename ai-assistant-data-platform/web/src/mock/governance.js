// Local fixtures for VITE_USE_MOCK=true previews of the governance pages.
// They only satisfy the UI shape; they are not evidence of data correctness.

export const datasources = [
  {
    id: 1,
    name: 'registration_db',
    host: '127.0.0.1',
    database_name: 'registration_db',
    status: 1,
  },
  {
    id: 2,
    name: 'chat_db',
    host: '127.0.0.1',
    database_name: 'chat_db',
    status: 1,
  },
];

export const syncTasks = [
  {
    id: 1,
    datasource_id: 1,
    source_schema: 'registration_db',
    source_table: 'registration',
    target_table: 'ods_registration',
    primary_key: 'id',
    watermark_column: 'update_time',
    watermark_type: 'datetime',
    batch_size: 500,
    overlap_seconds: 60,
    cron: '*/10 * * * *',
    enabled: true,
    full_load_done: true,
    last_watermark: '2026-11-04T12:00:00+08:00',
    last_watermark_num: null,
    last_run_at: '2026-11-04T12:01:00+08:00',
    running: false,
    locked_at: null,
  },
];

export const syncRuns = [
  {
    id: 1,
    task_id: 1,
    run_type: 'INCREMENTAL',
    status: 'SUCCESS',
    started_at: '2026-11-04T12:00:00+08:00',
    finished_at: '2026-11-04T12:01:00+08:00',
    rows_read: 128,
    rows_written: 128,
    error_msg: null,
  },
];

export const qualityRules = [
  {
    id: 1,
    name: '挂号号源非空',
    target_table: 'ods_registration',
    rule_type: 'NOT_NULL',
    column_name: 'registration_no',
    params: {},
    threshold: 0.99,
    severity: 'P1',
    cron: '0 * * * *',
    enabled: true,
    create_time: '2026-11-04T10:00:00+08:00',
  },
];

export const qualityResults = [
  {
    id: 1,
    rule_id: 1,
    total_rows: 128,
    bad_rows: 0,
    pass_rate: 1,
    status: 'PASS',
    checked_at: '2026-11-04T12:00:00+08:00',
  },
];

export const alertRules = [
  {
    id: 1,
    name: '链路延迟告警',
    metric_type: 'PIPELINE_LAG',
    operator: '>',
    threshold: 300,
    window_minutes: 5,
    consecutive_periods: 2,
    severity: 'P0',
    channel: 'webhook',
    cooldown_minutes: 30,
    enabled: true,
  },
];

export const alertRecords = [
  {
    id: 1,
    rule_id: 1,
    fingerprint: 'pipeline_lag:all',
    severity: 'P0',
    title: '链路延迟超过阈值',
    content: 'lag_seconds=420 > 300',
    status: 'OPEN',
    created_at: '2026-11-04T12:00:00+08:00',
    ack_at: null,
    closed_at: null,
  },
];

let nextId = 100;

function now() {
  return new Date().toISOString();
}

function findById(rows, id) {
  return rows.find((row) => row.id === Number(id));
}

function searchOf(path) {
  const index = path.indexOf('?');
  return new URLSearchParams(index >= 0 ? path.slice(index + 1) : '');
}

function requireRow(rows, id, label) {
  const row = findById(rows, id);
  if (!row) {
    throw new Error(`mock 数据中不存在 ${label} #${id}`);
  }
  return row;
}

function filterAlerts(search) {
  const status = search.get('status');
  const severity = search.get('severity');
  return alertRecords.filter(
    (row) =>
      (!status || row.status === status) && (!severity || row.severity === severity),
  );
}

/**
 * Answer a governance API call from the in-browser fixtures.
 *
 * Paths mirror the FastAPI routes (``/datasources``, ``/sync-tasks``,
 * ``/quality``, ``/alerts``) so the views exercise the same request shapes in
 * mock and real mode. Mutations update the module-level arrays, which makes
 * create/run/ack flows demonstrable without a backend; nothing is persisted.
 */
export function requestMock(method, path, body = {}) {
  const route = path.split('?')[0];
  const search = searchOf(path);
  const verb = String(method || 'get').toLowerCase();
  let match;

  if (verb === 'get') {
    if (route === '/datasources') return datasources;
    if (route === '/sync-tasks') return syncTasks;
    if (route === '/sync-runs') {
      const taskId = search.get('task_id');
      return taskId ? syncRuns.filter((row) => row.task_id === Number(taskId)) : syncRuns;
    }
    if (route === '/quality/rules') return qualityRules;
    if (route === '/quality/results') {
      const ruleId = search.get('rule_id');
      return ruleId
        ? qualityResults.filter((row) => row.rule_id === Number(ruleId))
        : qualityResults;
    }
    if (route === '/alerts/rules') return alertRules;
    if (route === '/alerts') return filterAlerts(search);
  }

  if (verb === 'post') {
    if (route === '/datasources') {
      // Mirror the real list payload: the password is accepted but never kept
      // or echoed back.
      const row = {
        id: nextId++,
        status: 1,
        name: body.name,
        host: body.host,
        port: body.port,
        database_name: body.database_name,
      };
      datasources.unshift(row);
      return { id: row.id, name: row.name };
    }
    if ((match = /^\/datasources\/(\d+)\/test$/.exec(route))) {
      requireRow(datasources, match[1], 'datasource');
      return { connected: true };
    }
    if (route === '/sync-tasks') {
      const row = {
        id: nextId++,
        full_load_done: false,
        last_watermark: null,
        last_watermark_num: null,
        last_run_at: null,
        running: false,
        locked_at: null,
        ...body,
      };
      syncTasks.unshift(row);
      return { id: row.id };
    }
    if ((match = /^\/sync-tasks\/(\d+)\/run$/.exec(route))) {
      const task = requireRow(syncTasks, match[1], 'sync task');
      const run = {
        id: nextId++,
        task_id: task.id,
        run_type: search.get('mode') || 'INCREMENTAL',
        status: 'SUCCESS',
        started_at: now(),
        finished_at: now(),
        rows_read: 0,
        rows_written: 0,
        error_msg: null,
      };
      syncRuns.unshift(run);
      task.last_run_at = run.finished_at;
      return {
        rows_read: run.rows_read,
        rows_written: run.rows_written,
        last_watermark: task.last_watermark,
        last_watermark_num: task.last_watermark_num,
      };
    }
    if (route === '/quality/rules') {
      const row = {
        id: nextId++,
        create_time: now(),
        ...body,
      };
      qualityRules.unshift(row);
      return { id: row.id };
    }
    if ((match = /^\/quality\/rules\/(\d+)\/run$/.exec(route))) {
      const rule = requireRow(qualityRules, match[1], 'quality rule');
      const result = {
        id: nextId++,
        rule_id: rule.id,
        total_rows: 0,
        bad_rows: 0,
        pass_rate: 1,
        status: 'PASS',
        checked_at: now(),
      };
      qualityResults.unshift(result);
      return { ...result, severity: rule.severity, olap_written: false, store_kind: 'mock' };
    }
    if (route === '/alerts/rules') {
      const row = { id: nextId++, ...body };
      alertRules.unshift(row);
      return row;
    }
    if ((match = /^\/alerts\/(\d+)\/ack$/.exec(route))) {
      const row = requireRow(alertRecords, match[1], 'alert');
      if (row.status === 'CLOSED') {
        throw new Error('alert is already closed');
      }
      row.status = 'ACKED';
      row.ack_at = now();
      return row;
    }
    if ((match = /^\/alerts\/(\d+)\/close$/.exec(route))) {
      const row = requireRow(alertRecords, match[1], 'alert');
      row.status = 'CLOSED';
      row.closed_at = now();
      return row;
    }
  }

  if (verb === 'delete') {
    if ((match = /^\/datasources\/(\d+)$/.exec(route))) {
      removeRow(datasources, match[1]);
      return { deleted: Number(match[1]) };
    }
    if ((match = /^\/sync-tasks\/(\d+)$/.exec(route))) {
      removeRow(syncTasks, match[1]);
      return { deleted: Number(match[1]) };
    }
    if ((match = /^\/quality\/rules\/(\d+)$/.exec(route))) {
      removeRow(qualityRules, match[1]);
      return { deleted: Number(match[1]) };
    }
    if ((match = /^\/alerts\/rules\/(\d+)$/.exec(route))) {
      removeRow(alertRules, match[1]);
      return { deleted: Number(match[1]) };
    }
  }

  throw new Error(`mock 模式未实现该接口：${verb.toUpperCase()} ${route}`);
}

function removeRow(rows, id) {
  const index = rows.findIndex((row) => row.id === Number(id));
  if (index < 0) {
    throw new Error(`mock 数据中不存在 #${id}`);
  }
  rows.splice(index, 1);
}
