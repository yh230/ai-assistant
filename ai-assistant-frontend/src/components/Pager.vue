<template>
  <div class="pager" v-if="total > 0">
    <el-pagination
      layout="prev, pager, next, total"
      :total="total"
      :current-page="pageNum"
      :page-size="pageSize"
      @current-change="handleChange"
    />
  </div>
</template>

<script setup>
defineProps({
  pageNum: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  total: { type: Number, default: 0 }
})

const emit = defineEmits(['update:pageNum', 'change'])

function handleChange(page) {
  emit('update:pageNum', page)
  emit('change', page)
}
</script>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 16px;
}
</style>
