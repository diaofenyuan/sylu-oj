import { computed, onScopeDispose, ref } from 'vue'
import { api } from '../api.js'

export function useGradeExport() {
  const exportStatus = ref('')
  const exportError = ref('')
  const exporting = ref(false)
  const downloading = ref(false)
  const canDownload = computed(() => exportStatus.value === 'READY')
  let exportId = null
  let fileFormat = 'XLSX'
  let timer = null
  let generation = 0

  function reset() {
    generation++
    clearTimeout(timer)
    exportId = null
    exportStatus.value = ''
    exportError.value = ''
    exporting.value = false
    downloading.value = false
  }
  onScopeDispose(reset)

  async function pollStatus(current) {
    try {
      const res = await api(`/teacher/exports/${exportId}`)
      if (current !== generation) return
      exportStatus.value = res.status
      if (res.status === 'QUEUED' || res.status === 'GENERATING') {
        timer = setTimeout(() => pollStatus(current), 1000)
        return
      }
      exporting.value = false
      if (res.status !== 'READY') {
        exportError.value = res.status === 'EXPIRED' ? '导出文件已过期，请重新导出' : '导出失败，请重新尝试'
      }
    } catch (err) {
      if (current !== generation) return
      exporting.value = false
      exportError.value = err.message || '查询导出状态失败，请重新尝试'
    }
  }

  async function exportGrades(body) {
    if (exporting.value || downloading.value) return
    reset()
    const current = generation
    fileFormat = body.format
    exporting.value = true
    try {
      const res = await api('/teacher/exports', { method: 'POST', body })
      if (current !== generation) return
      exportId = res.taskId
      exportStatus.value = res.status
      await pollStatus(current)
    } catch (err) {
      if (current !== generation) return
      exporting.value = false
      exportError.value = err.message || '创建导出失败，请重新尝试'
    }
  }

  async function download() {
    if (!canDownload.value || downloading.value || exporting.value) return
    const current = generation
    downloading.value = true
    exportError.value = ''
    try {
      // 下载授权仅可使用一次，每次点击重新签发；文件请求仍须携带登录令牌。
      const { token } = await api(`/teacher/exports/${exportId}/download-token`, { method: 'POST' })
      if (current !== generation) return
      const blob = await api(`/teacher/exports/download?token=${encodeURIComponent(token)}`, { responseType: 'blob' })
      if (current !== generation) return
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `grades-${exportId}.${fileFormat === 'CSV' ? 'zip' : 'xlsx'}`
      document.body.appendChild(link)
      link.click()
      link.remove()
      // 延迟释放，避免浏览器尚未读取 Blob 就失去下载内容。
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (err) {
      if (current !== generation) return
      exportError.value = err.message || '下载失败，请重试'
    } finally {
      if (current === generation) downloading.value = false
    }
  }

  return { exportStatus, exportError, exporting, downloading, canDownload, exportGrades, download, reset }
}
