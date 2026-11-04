<template>
  <section class="page">
    <header class="page-header">
      <h2>告警中心</h2>
      <div class="actions">
        <el-button :loading="activeTab === 'records' ? loading : rulesLoading" @click="refresh">
          刷新
        </el-button>
        <el-button v-if="activeTab === 'rules'" type="primary" @click="openCreateRule">
          新建告警规则
        </el-button>
      </div>
    </header>
    <p class="page-hint">
      告警按规则指纹与冷却时间去重；生命周期为 OPEN → ACKED → CLOSED，Webhook 失败只记录日志，不回滚告警。
    </p>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="告警记录" name="records">
        <div class="filter-row">
          <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 150px" @change="loadAlerts">
            <el-option v-for="item in STATUSES" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="severityFilter" clearable placeholder="全部级别" style="width: 150px" @change="loadAlerts">
            <el-option v-for="item in SEVERITIES" :key="item" :label="item" :value="item" />
          </el-select>
        </div>

        <el-table v-loading="loading" :data="alerts" border stripe empty-text="暂无告警记录">
          <el-table-column prop="id" label="ID" width="64" />
          <el-table-column label="级别" width="86">
            <template #default="{ row }">
              <el-tag :type="severityType(row.severity)" disable-transitions>{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" disable-transitions>{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="产生时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="认领时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.ack_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small"
                :disabled="row.status !== 'OPEN'"
                @click="ackAlert(row)"
              >
                认领
              </el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="row.status === 'CLOSED'"
                @click="closeAlert(row)"
              >
                关闭
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="告警规则" name="rules">
        <el-table v-loading="rulesLoading" :data="rules" border stripe empty-text="暂无告警规则">
          <el-table-column prop="id" label="ID" width="64" />
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column label="指标" min-width="130">
            <template #default="{ row }">{{ metricLabel(row.metric_type) }}</template>
          </el-table-column>
          <el-table-column label="条件" width="130">
            <template #default="{ row }">
              <span class="mono">{{ row.operator }} {{ row.threshold }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="window_minutes" label="窗口(分)" width="100" />
          <el-table-column prop="consecutive_periods" label="连续周期" width="100" />
          <el-table-column label="级别" width="86">
            <template #default="{ row }">
              <el-tag :type="severityType(row.severity)" disable-transitions>{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="channel" label="渠道" width="100" />
          <el-table-column prop="cooldown_minutes" label="冷却(分)" width="100" />
          <el-table-column label="启用" width="86">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" disable-transitions>
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="danger" plain @click="removeRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="createVisible" title="新建告警规则" width="580px">
      <el-form :model="ruleForm" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="ruleForm.name" placeholder="链路延迟告警" />
        </el-form-item>
        <el-form-item label="指标" required>
          <el-select v-model="ruleForm.metric_type" style="width: 100%">
            <el-option
              v-for="item in METRIC_TYPES"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="操作符">
          <el-select v-model="ruleForm.operator" style="width: 100%">
            <el-option v-for="item in OPERATORS" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值" required>
          <el-input-number v-model="ruleForm.threshold" controls-position="right" />
        </el-form-item>
        <el-form-item label="窗口(分钟)">
          <el-input-number v-model="ruleForm.window_minutes" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="连续周期">
          <el-input-number v-model="ruleForm.consecutive_periods" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="严重级别">
          <el-select v-model="ruleForm.severity" style="width: 100%">
            <el-option v-for="item in SEVERITIES" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="ruleForm.channel" style="width: 100%">
            <el-option v-for="item in CHANNELS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="冷却(分钟)">
          <el-input-number v-model="ruleForm.cooldown_minutes" :min="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRule">提交</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

import { deleteJson, getJson, postJson } from '../api/client.js';
import { formatDateTime } from '../utils/chartOptions.js';

const STATUSES = ['OPEN', 'ACKED', 'CLOSED'];
const SEVERITIES = ['P0', 'P1', 'P2'];
const OPERATORS = ['>', '>=', '<', '<=', '=='];
const METRIC_TYPES = [
  { value: 'PIPELINE_LAG', label: '链路延迟' },
  { value: 'DATA_FRESHNESS', label: '数据时效' },
  { value: 'QUALITY_SCORE', label: '质量分' },
  { value: 'VOLUME_ANOMALY', label: '量级异常' },
  { value: 'KAFKA_LAG', label: 'Kafka 积压' },
];
const CHANNELS = [
  { value: 'webhook', label: 'Webhook + 站内' },
  { value: 'inapp', label: '仅站内' },
];

const activeTab = ref('records');
const alerts = ref([]);
const rules = ref([]);
const loading = ref(false);
const rulesLoading = ref(false);
const submitting = ref(false);
const error = ref('');
const statusFilter = ref('');
const severityFilter = ref('');
const createVisible = ref(false);
const ruleForm = reactive({
  name: '',
  metric_type: 'PIPELINE_LAG',
  operator: '>',
  threshold: 300,
  window_minutes: 5,
  consecutive_periods: 1,
  severity: 'P1',
  channel: 'webhook',
  cooldown_minutes: 30,
  enabled: true,
});

function metricLabel(value) {
  return METRIC_TYPES.find((item) => item.value === value)?.label || value;
}

function severityType(severity) {
  if (severity === 'P0') return 'danger';
  if (severity === 'P1') return 'warning';
  return 'info';
}

function statusTagType(status) {
  if (status === 'OPEN') return 'danger';
  if (status === 'ACKED') return 'warning';
  return 'info';
}

async function loadAlerts() {
  loading.value = true;
  error.value = '';
  const query = new URLSearchParams();
  if (statusFilter.value) query.set('status', statusFilter.value);
  if (severityFilter.value) query.set('severity', severityFilter.value);
  const suffix = query.toString();
  try {
    alerts.value = (await getJson(`/alerts${suffix ? `?${suffix}` : ''}`)) || [];
  } catch (err) {
    error.value = `加载告警失败：${err.message}`;
  } finally {
    loading.value = false;
  }
}

async function loadRules() {
  rulesLoading.value = true;
  error.value = '';
  try {
    rules.value = (await getJson('/alerts/rules')) || [];
  } catch (err) {
    error.value = `加载告警规则失败：${err.message}`;
  } finally {
    rulesLoading.value = false;
  }
}

function refresh() {
  return activeTab.value === 'rules' ? loadRules() : loadAlerts();
}

function onTabChange(name) {
  if (name === 'rules') {
    loadRules();
  } else {
    loadAlerts();
  }
}

async function ackAlert(row) {
  try {
    await postJson(`/alerts/${row.id}/ack`);
    ElMessage.success(`告警 #${row.id} 已认领`);
    await loadAlerts();
  } catch (err) {
    ElMessage.error(`认领失败：${err.message}`);
  }
}

async function closeAlert(row) {
  try {
    await ElMessageBox.confirm(`确认关闭告警「${row.title}」？`, '关闭确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await postJson(`/alerts/${row.id}/close`);
    ElMessage.success(`告警 #${row.id} 已关闭`);
    await loadAlerts();
  } catch (err) {
    ElMessage.error(`关闭失败：${err.message}`);
  }
}

function openCreateRule() {
  Object.assign(ruleForm, {
    name: '',
    metric_type: 'PIPELINE_LAG',
    operator: '>',
    threshold: 300,
    window_minutes: 5,
    consecutive_periods: 1,
    severity: 'P1',
    channel: 'webhook',
    cooldown_minutes: 30,
    enabled: true,
  });
  createVisible.value = true;
}

async function submitRule() {
  if (!ruleForm.name) {
    ElMessage.warning('请填写规则名称');
    return;
  }
  submitting.value = true;
  try {
    await postJson('/alerts/rules', { ...ruleForm });
    ElMessage.success('告警规则已创建');
    createVisible.value = false;
    await loadRules();
  } catch (err) {
    ElMessage.error(`创建失败：${err.message}`);
  } finally {
    submitting.value = false;
  }
}

async function removeRule(row) {
  try {
    await ElMessageBox.confirm(`确认删除告警规则「${row.name}」？`, '删除确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await deleteJson(`/alerts/rules/${row.id}`);
    ElMessage.success('已删除');
    await loadRules();
  } catch (err) {
    ElMessage.error(`删除失败：${err.message}`);
  }
}

onMounted(() => {
  loadAlerts();
  loadRules();
});
</script>

<style scoped>
.filter-row {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}
</style>
