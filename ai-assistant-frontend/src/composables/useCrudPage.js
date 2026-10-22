import { ref, computed } from 'vue'
import { confirm as showConfirm, toast } from '../utils/message'

/**
 * 管理页 CRUD 通用骨架：分页加载 + 弹窗表单 + 状态切换 + 删除
 * @param {Object} options 配置项
 * @param {Function} options.fetchPage 分页查询 (params) => PageResult，params 含 pageNum/pageSize/keyword
 * @param {Function} options.emptyForm 空表单工厂 () => form
 * @param {Function} options.toForm 行数据转表单 (row) => form
 * @param {Function} options.persist 保存 (form, id|null) => Promise，id 为 null 表示新增
 * @param {Function} [options.validate] 保存前校验 (form) => 出错提示文案或 null
 * @param {Function} [options.toggle] 状态切换请求 (row, next) => Promise
 * @param {Function} [options.toggleConfirm] 切换确认文案 (next, row) => string
 * @param {Function} [options.remove] 删除请求 (row) => Promise
 * @param {Function} [options.removeConfirm] 删除确认文案 (row) => string
 * @param {string} [options.removeSuccessMsg] 删除成功提示，默认「已删除」
 * @returns {Object} 页面状态与操作函数
 */
export function useCrudPage(options) {
  const list = ref([])
  const total = ref(0)
  const pageNum = ref(1)
  const pageSize = ref(10)
  const keyword = ref('')
  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

  const loading = ref(false)
  const error = ref('')

  const showModal = ref(false)
  const isEdit = ref(false)
  const saving = ref(false)
  const editingId = ref(null)
  const form = ref(null)

  // 请求序号：并发/乱序请求时仅最后一次发起的请求可写入结果与 loading，避免旧响应覆盖新页面
  let loadSeq = 0

  async function loadData() {
    const seq = ++loadSeq
    loading.value = true
    error.value = ''
    try {
      const res = await options.fetchPage({
        pageNum: pageNum.value,
        pageSize: pageSize.value,
        keyword: keyword.value || undefined
      })
      if (seq !== loadSeq) return
      list.value = res.list || []
      total.value = res.total || 0
    } catch (e) {
      if (seq !== loadSeq) return
      // 加载失败时保留上一次的列表与总数，错误仅用于页面内联提示
      error.value = '加载失败：' + e.message
      toast.error(error.value)
    } finally {
      if (seq === loadSeq) loading.value = false
    }
  }
  function retry() {
    return loadData()
  }
  function reload() {
    pageNum.value = 1
    loadData()
  }
  function changePage(p) {
    pageNum.value = p
    loadData()
  }

  function openCreate() {
    isEdit.value = false
    editingId.value = null
    form.value = options.emptyForm()
    showModal.value = true
  }
  function openEdit(row) {
    isEdit.value = true
    editingId.value = row.id
    form.value = options.toForm(row)
    showModal.value = true
  }
  function closeModal() {
    showModal.value = false
  }

  async function save() {
    if (options.validate) {
      const message = options.validate(form.value)
      if (message) return toast.warn(message)
    }
    saving.value = true
    try {
      await options.persist(form.value, isEdit.value ? editingId.value : null)
      closeModal()
      await loadData()
    } catch (e) {
      toast.error('保存失败：' + e.message)
    } finally {
      saving.value = false
    }
  }

  async function toggleStatus(row) {
    const next = row.status === 1 ? 0 : 1
    if (options.toggleConfirm && !(await showConfirm(options.toggleConfirm(next, row)))) return
    try {
      await options.toggle(row, next)
      await loadData()
    } catch (e) {
      toast.error('操作失败：' + e.message)
    }
  }

  async function remove(row) {
    if (options.removeConfirm && !(await showConfirm(options.removeConfirm(row)))) return
    try {
      await options.remove(row)
      toast.success(options.removeSuccessMsg || '已删除')
      await loadData()
    } catch (e) {
      toast.error('删除失败：' + e.message)
    }
  }

  return {
    list, total, pageNum, pageSize, keyword, totalPages,
    loading, error,
    showModal, isEdit, saving, form,
    loadData, retry, reload, changePage, openCreate, openEdit, closeModal, save, toggleStatus, remove
  }
}
