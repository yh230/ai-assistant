<template>
  <section class="page">
    <header class="page-header">
      <h2>数据质量</h2>
      <div class="actions">
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建规则</el-button>
      </div>
    </header>
    <p class="page-hint">
      规则在 ClickHouse 上生成只读 SQL 执行；通过率低于阈值即为失败，并按 P0/P1/P2 严重级别参与质量分加权。
    </p>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-table v-loading="loading" :data="rules" border stripe empty-text="暂无质量规则">
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="target_table" label="目标表" min-width="150" />
      <el-table-column prop="rule_type" label="规则类型" width="120" />
      <el-table-column label="字段" width="140">
        <template #default="{ row }">{{ row.column_name || '-' }}</template>
      </el-table-column>
      <el-table-column prop="threshold" label="阈值" width="90" />
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag :type="severityType(row.severity)" disable-transitions>{{ row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="cron" label="Cron" width="120" />
      <el-table-column label="启用" width="86">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" disable-transitions>
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="runRule(row)">执行</el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h3 class="section-title section-gap">最近质量结果</h3>
    <el-table v-loading="resultsLoading" :data="results" border size="small" empty-text="暂无质量结果">
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="rule_id" label="规则" width="80" />
      <el-table-column prop="total_rows" label="总行数" width="100" />
      <el-table-column prop="bad_rows" label="异常行数" width="100" />
      <el-table-column label="通过率" width="110">
        <template #default="{ row }">{{ percentOf(row.pass_rate) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PASS' ? 'success' : 'danger'" disable-transitions>
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="检查时间" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.checked_at) }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建质量规则" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="挂号号源非空" />
        </el-form-item>
        <el-form-item label="目标表" required>
          <el-input v-model="form.target_table" placeholder="ods_registration" />
        </el-form-item>
        <el-form-item label="规则类型" required>
          <el-select v-model="form.rule_type" style="width: 100%">
            <el-option v-for="item in RULE_TYPES" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="字段">
          <el-input v-model="form.column_name" placeholder="registration_no（FRESHNESS/UNIQUE 等必填）" />
        </el-form-item>
        <el-form-item label="阈值" required>
          <el-input-number v-model="form.threshold" :min="0" :step="0.01" controls-position="right" />
        </el-form-item>
        <el-form-item label="严重级别">
          <el-select v-model="form.severity" style="width: 100%">
            <el-option v-for="item in SEVERITIES" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="Cron">
          <el-input v-model="form.cron" placeholder="0 * * * *" />
        </el-form-item>
        <el-form-item label="参数 JSON">
          <el-input
            v-model="form.paramsText"
            type="textarea"
            :rows="3"
            placeholder='{"min": 0, "max": 100} / {"values": ["1", "2"]} / {"sql": "SELECT ..."}'
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

import { deleteJson, getJson, postJson } from '../api/client.js';
import { formatDateTime, formatMetric } from '../utils/chartOptions.js';

const RULE_TYPES = ['NOT_NULL', 'UNIQUE', 'RANGE', 'ENUM', 'FRESHNESS', 'VOLUME', 'CUSTOM_SQL'];
const SEVERITIES = ['P0', 'P1', 'P2'];

const rules = ref([]);
const results = ref([]);
const loading = ref(false);
const resultsLoading = ref(false);
const submitting = ref(false);
const error = ref('');
const createVisible = ref(false);
const form = reactive({
  name: '',
  target_table: '',
  rule_type: 'NOT_NULL',
  column_name: '',
  threshold: 0.99,
  severity: 'P1',
  cron: '0 * * * *',
  paramsText: '{}',
  enabled: true,
});

function severityType(severity) {
  if (severity === 'P0') return 'danger';
  if (severity === 'P1') return 'warning';
  return 'info';
}

function percentOf(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '0%';
  }
  return `${(number * 100).toFixed(2)}%`;
}

async function loadRules() {
  loading.value = true;
  try {
    rules.value = (await getJson('/quality/rules')) || [];
  } catch (err) {
    error.value = `加载质量规则失败：${err.message}`;
  } finally {
    loading.value = false;
  }
}

async function loadResults() {
  resultsLoading.value = true;
  try {
    results.value = (await getJson('/quality/results?limit=50')) || [];
  } catch (err) {
    error.value = `加载质量结果失败：${err.message}`;
  } finally {
    resultsLoading.value = false;
  }
}

async function loadAll() {
  error.value = '';
  await Promise.all([loadRules(), loadResults()]);
}

function openCreate() {
  Object.assign(form, {
    name: '',
    target_table: '',
    rule_type: 'NOT_NULL',
    column_name: '',
    threshold: 0.99,
    severity: 'P1',
    cron: '0 * * * *',
    paramsText: '{}',
    enabled: true,
  });
  createVisible.value = true;
}

async function submit() {
  if (!form.name || !form.target_table) {
    ElMessage.warning('请填写规则名称与目标表');
    return;
  }
  let params = {};
  if (form.paramsText.trim()) {
    try {
      params = JSON.parse(form.paramsText);
    } catch {
      ElMessage.error('参数必须是合法 JSON');
      return;
    }
  }
  submitting.value = true;
  try {
    await postJson('/quality/rules', {
      name: form.name,
      target_table: form.target_table,
      rule_type: form.rule_type,
      column_name: form.column_name || null,
      params,
      threshold: Number(form.threshold),
      severity: form.severity,
      cron: form.cron,
      enabled: form.enabled,
    });
    ElMessage.success('质量规则已创建');
    createVisible.value = false;
    await loadAll();
  } catch (err) {
    ElMessage.error(`创建失败：${err.message}`);
  } finally {
    submitting.value = false;
  }
}

async function runRule(row) {
  try {
    const data = await postJson(`/quality/rules/${row.id}/run`);
    const summary = `规则「${row.name}」${data?.status ?? 'UNKNOWN'}：通过率 ${percentOf(
      data?.pass_rate,
    )}，异常 ${formatMetric(data?.bad_rows)} / ${formatMetric(data?.total_rows)} 行`;
    if (data?.status === 'PASS') {
      ElMessage.success(summary);
    } else {
      ElMessage.warning(summary);
    }
    if (data?.warning) {
      ElMessage.warning(data.warning);
    }
    await loadAll();
  } catch (err) {
    ElMessage.error(`执行失败：${err.message}`);
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除质量规则「${row.name}」？`, '删除确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await deleteJson(`/quality/rules/${row.id}`);
    ElMessage.success('已删除');
    await loadAll();
  } catch (err) {
    ElMessage.error(`删除失败：${err.message}`);
  }
}

onMounted(loadAll);
</script>
