<template>
  <section class="page">
    <header class="page-header">
      <h2>数据源</h2>
      <div class="actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建数据源</el-button>
      </div>
    </header>
    <p class="page-hint">
      密码由后端使用 DP_SECRET_KEY 加密存储，列表不会返回明文。测试连接会尝试打开一次只读连接。
    </p>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-table v-loading="loading" :data="rows" border stripe empty-text="暂无数据源">
      <el-table-column prop="id" label="ID" width="72" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="host" label="主机" min-width="160" />
      <el-table-column prop="database_name" label="数据库" min-width="150" />
      <el-table-column prop="status" label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" disable-transitions>
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="testConnection(row)">测试连接</el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建数据源" width="520px">
      <el-form :model="form" label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="registration_db" />
        </el-form-item>
        <el-form-item label="主机" required>
          <el-input v-model="form.host" placeholder="127.0.0.1" />
        </el-form-item>
        <el-form-item label="端口" required>
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
        </el-form-item>
        <el-form-item label="数据库" required>
          <el-input v-model="form.database_name" placeholder="registration_db" />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" placeholder="readonly" autocomplete="off" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="仅提交，不会回显"
          />
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

const rows = ref([]);
const loading = ref(false);
const submitting = ref(false);
const error = ref('');
const createVisible = ref(false);
const form = reactive({
  name: '',
  host: '127.0.0.1',
  port: 3306,
  database_name: '',
  username: '',
  password: '',
});

// The metadata table stores the datasource status as a small integer
// (1 = normal); accept the string forms too so fixtures and future enum
// labels keep rendering.
function statusLabel(status) {
  if (status === 1 || status === '1' || status === 'CONNECTED' || status === 'OK') return '正常';
  if (status === 0 || status === '0' || status === 'ERROR' || status === 'FAILED') return '异常';
  return status || 'UNKNOWN';
}

function statusType(status) {
  const label = statusLabel(status);
  if (label === '正常') return 'success';
  if (label === '异常') return 'danger';
  return 'info';
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    rows.value = (await getJson('/datasources')) || [];
  } catch (err) {
    error.value = `加载数据源失败：${err.message}`;
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  Object.assign(form, {
    name: '',
    host: '127.0.0.1',
    port: 3306,
    database_name: '',
    username: '',
    password: '',
  });
  createVisible.value = true;
}

async function submit() {
  if (!form.name || !form.host || !form.database_name || !form.username || !form.password) {
    ElMessage.warning('请填写名称、主机、数据库、用户名与密码');
    return;
  }
  submitting.value = true;
  try {
    await postJson('/datasources', { ...form });
    ElMessage.success('数据源已创建');
    createVisible.value = false;
    await load();
  } catch (err) {
    ElMessage.error(`创建失败：${err.message}`);
  } finally {
    submitting.value = false;
  }
}

async function testConnection(row) {
  try {
    const data = await postJson(`/datasources/${row.id}/test`);
    if (data?.demo) {
      // Demo mode never dials a real database; show the backend's explanation
      // instead of a misleading connection success or failure.
      ElMessage.info(data.message || '演示模式不连接真实数据源');
      await load();
      return;
    }
    if (data?.connected) {
      ElMessage.success(`${row.name} 连接成功`);
    } else {
      ElMessage.warning(`${row.name} 连接失败`);
    }
    await load();
  } catch (err) {
    ElMessage.error(`连接测试失败：${err.message}`);
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除数据源「${row.name}」？`, '删除确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await deleteJson(`/datasources/${row.id}`);
    ElMessage.success('已删除');
    await load();
  } catch (err) {
    ElMessage.error(`删除失败：${err.message}`);
  }
}

onMounted(load);
</script>
