import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'

export function useStudentAssignments() {
  const assignments = ref([])
  const loading = ref(true)
  const error = ref('')
  let timer = null
  let version = 0
  let disposed = false

  function scheduleRefresh() {
    clearTimeout(timer)
    if (!disposed) timer = setTimeout(() => document.hidden ? scheduleRefresh() : refresh(), 15000)
  }

  async function refresh() {
    if (disposed) return
    clearTimeout(timer)
    const requestVersion = ++version
    loading.value = true
    error.value = ''
    try {
      const list = await api('/student/assignments')
      if (requestVersion === version) assignments.value = list
    } catch (cause) {
      if (requestVersion === version) error.value = cause.message || '作业加载失败，请重试'
    } finally {
      if (requestVersion === version) {
        loading.value = false
        scheduleRefresh()
      }
    }
  }

  function onVisible() {
    if (!document.hidden) refresh()
  }
  onMounted(() => {
    refresh()
    // 窗口状态以服务端为准；返回页面时立即刷新，避免依赖学生设备时钟。
    document.addEventListener('visibilitychange', onVisible)
  })
  onBeforeUnmount(() => {
    disposed = true
    version++
    clearTimeout(timer)
    document.removeEventListener('visibilitychange', onVisible)
  })
  return { assignments, loading, error, refresh }
}
