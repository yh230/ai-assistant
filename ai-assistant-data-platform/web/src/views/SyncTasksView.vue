<template>
  <section class="page">
    <header class="page-header">
      <h2>同步任务</h2>
      <div class="actions">
        <el-select v-model="mode" style="width: 130px">
          <el-option label="增量运行" value="INCREMENTAL" />
          <el-option label="全量运行" value="FULL" />
        </el-select>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建任务</el-button>
      </div>
    </header>
    <p class="page-hint">
      任务运行由 ETL Extractor 按水位续跑；手工运行会立即执行一次并按结果推进水位。
      当后端 EVENT_BUS=memory 时事件不会进入 Kafka，接口会返回 published=false 且写入行数为 0。
    </p>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-table v-loading="loading" :data="tasks" border stripe empty-text="暂无同步任务">
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="datasource_id" label="数据源" width="80" />
      <el-table-column label="源表" min-width="170">
        <template #default="{ row }">{{ row.source_schema }}.{{ row.source_table }}</template>
      </el-table-column>
      <el-table-column prop="target_table" label="目标表" min-width="150" />
      <el-table-column label="水位" min-width="170">
        <template #default="{ row }">
          <span class="mono">{{ row.watermark_column || '-' }} / {{ row.watermark_type }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="batch_size" label="批大小" width="86" />
      <el-table-column prop="cron" label="Cron" width="120" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag v-if="!row.enabled" type="info" disable-transitions>已停用</el-tag>
          <el-tag v-else-if="row.running" type="warning" disable-transitions>运行中</el-tag>
          <el-tag v-else type="success" disable-transitions>就绪</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近运行" min-width="160">
        <template #default="{ row }">{{ formatDateTime(row.last_run_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="runTask(row)">运行</el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h3 class="section-title section-gap">最近运行记录</h3>
    <el-table v-loading="runsLoading" :data="runs" border size="small" empty-text="暂无运行记录">
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="task_id" label="任务" width="80" />
      <el-table-column prop="run_type" label="类型" width="110" />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" disable-transitions>
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="开始" min-width="160">
        <template #default="{ row }">{{ formatDateTime(row.started_at) }}</template>
      </el-table-column>
      <el-table-column label="结束" min-width="160">
        <template #default="{ row }">{{ formatDateTime(row.finished_at) }}</template>
      </el-table-column>
      <el-table-column prop="rows_read" label="读取行数" width="100" />
      <el-table-column prop="rows_written" label="写入行数" width="100" />
      <el-table-column prop="error_msg" label="错误摘要" min-width="180">
        <template #default="{ row }">{{ row.error_msg || '-' }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建同步任务" width="620px">
      <el-form :model="form" label-width="112px">
        <el-form-item label="数据源" required>
          <el-select v-model="form.datasource_id" placeholder="选择数据源" style="width: 100%">
            <el-option
              v-for="item in datasources"
              :key="item.id"
              :label="`#${item.id} ${item.name}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="源 Schema" required>
          <el-input v-model="form.source_schema" placeholder="registration_db" />
        </el-form-item>
        <el-form-item label="源表" required>
          <el-input v-model="form.source_table" placeholder="registration" />
        </el-form-item>
        <el-form-item label="目标表" required>
          <el-input v-model="form.target_table" placeholder="ods_registration" />
        </el-form-item>
        <el-form-item label="主键">
          <el-input v-model="form.primary_key" placeholder="id" />
        </el-form-item>
        <el-form-item label="水位字段">
          <el-input v-model="form.watermark_column" placeholder="update_time" />
        </el-form-item>
        <el-form-item label="水位类型">
          <el-select v-model="form.watermark_type" style="width: 100%">
            <el-option label="datetime" value="datetime" />
            <el-option label="numeric" value="numeric" />
          </el-select>
        </el-form-item>
        <el-form-item label="批大小">
          <el-input-number v-model="form.batch_size" :min="1" :max="10000" controls-position="right" />
        </el-form-item>
        <el-form-item label="重叠秒数">
          <el-input-number v-model="form.overlap_seconds" :min="0" :max="86400" controls-position="right" />
        </el-form-item>
        <el-form-item label="Cron">
          <el-input v-model="form.cron" placeholder="*/10 * * * *" />
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

const tasks = ref([]);
const runs = ref([]);
const datasources = ref([]);
const loading = ref(false);
const runsLoading = ref(false);
const submitting = ref(false);
const error = ref('');
const mode = ref('INCREMENTAL');
const createVisible = ref(false);
const form = reactive({
  datasource_id: null,
  source_schema: '',
  source_table: '',
  target_table: '',
  primary_key: 'id',
  watermark_column: 'update_time',
  watermark_type: 'datetime',
  batch_size: 500,
  overlap_seconds: 60,
  cron: '*/10 * * * *',
  enabled: true,
});

async function loadTasks() {
  loading.value = true;
  try {
    tasks.value = (await getJson('/sync-tasks')) || [];
  } catch (err) {
    error.value = `加载同步任务失败：${err.message}`;
  } finally {
    loading.value = false;
  }
}

async function loadRuns() {
  runsLoading.value = true;
  try {
    runs.value = (await getJson('/sync-runs?limit=50')) || [];
  } catch (err) {
    error.value = `加载运行记录失败：${err.message}`;
  } finally {
    runsLoading.value = false;
  }
}

async function loadDatasources() {
  try {
    datasources.value = (await getJson('/datasources')) || [];
  } catch (err) {
    error.value = `加载数据源失败：${err.message}`;
  }
}

async function loadAll() {
  error.value = '';
  await Promise.all([loadTasks(), loadRuns(), loadDatasources()]);
}

function openCreate() {
  Object.assign(form, {
    datasource_id: datasources.value[0]?.id ?? null,
    source_schema: '',
    source_table: '',
    target_table: '',
    primary_key: 'id',
    watermark_column: 'update_time',
    watermark_type: 'datetime',
    batch_size: 500,
    overlap_seconds: 60,
    cron: '*/10 * * * *',
    enabled: true,
  });
  if (!datasources.value.length) {
    ElMessage.warning('请先创建数据源');
    return;
  }
  createVisible.value = true;
}

async function submit() {
  if (!form.datasource_id || !form.source_schema || !form.source_table || !form.target_table) {
    ElMessage.warning('请填写数据源、源 Schema、源表与目标表');
    return;
  }
  submitting.value = true;
  try {
    await postJson('/sync-tasks', { ...form });
    ElMessage.success('同步任务已创建');
    createVisible.value = false;
    await loadAll();
  } catch (err) {
    ElMessage.error(`创建失败：${err.message}`);
  } finally {
    submitting.value = false;
  }
}

async function runTask(row) {
  try {
    const data = await postJson(
      `/sync-tasks/${row.id}/run?mode=${encodeURIComponent(mode.value)}`,
    );
    if (data?.published === false) {
      // A memory event bus accepts the events and drops them: the API reports
      // published=false / rows_written=0 and this warning must be visible
      // instead of a false success toast.
      ElMessage.warning(
        `任务 #${row.id} 已读取 ${formatMetric(data?.rows_read)} 行，但${data?.warning || '事件未发布'}，写入行数按 0 计`,
      );
    } else {
      ElMessage.success(
        `任务 #${row.id} 执行完成：读取 ${formatMetric(data?.rows_read)} 行，写入 ${formatMetric(data?.rows_written)} 行`,
      );
    }
    await loadAll();
  } catch (err) {
    ElMessage.error(`运行失败：${err.message}`);
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除同步任务 #${row.id}？`, '删除确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await deleteJson(`/sync-tasks/${row.id}`);
    ElMessage.success('已删除');
    await loadAll();
  } catch (err) {
    ElMessage.error(`删除失败：${err.message}`);
  }
}

onMounted(loadAll);
</script>
