<template>
  <div>
    <div class="page-head">
      <h2>成绩分析（目标班级 #{{ targetId }}）</h2>
      <p class="muted">班级表现多维统计与成绩导出</p>
    </div>

    <p v-if="loading" role="status">成绩加载中…</p>
    <p v-if="loadError" role="alert">{{ loadError }} <button @click="loadAnalytics">重新加载</button></p>
    <div class="card">
      <h3>班级状态分布</h3>
      <div class="dist">
        <span v-for="(v, k) in classDist" :key="k" class="chip chip-primary">{{ k }}: {{ v }}</span>
        <span v-if="!Object.keys(classDist).length" class="muted">暂无数据</span>
      </div>
    </div>

    <table>
      <thead>
        <tr>
          <th>排名</th><th>学号</th><th>姓名</th><th>总分</th>
          <th>通过率</th><th>提交次数</th><th>状态分布</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="r.studentId">
          <td><span class="rank">{{ r.rank }}</span></td>
          <td>{{ r.studentNo }}</td>
          <td><strong>{{ r.name }}</strong></td>
          <td><strong>{{ r.totalScore }}</strong></td>
          <td>{{ r.passRate }}%</td>
          <td>{{ r.submissionCount }}</td>
          <td class="muted">{{ distOf(r.statusDistribution) }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="!loading && !loadError && !rows.length" class="empty">暂无成绩数据</div>

    <div class="card export-card">
      <h3>导出成绩</h3>
      <div class="row">
        <select v-model="format"><option value="XLSX">XLSX</option><option value="CSV">CSV(ZIP)</option></select>
        <input v-model="studentNo" placeholder="学号筛选（可选）" />
        <input v-model="nameKeyword" placeholder="姓名关键词（可选）" />
        <button :disabled="exporting || downloading || loading || !!loadError" @click="exportGrades">{{ exporting ? '导出中…' : '发起导出' }}</button>
        <button v-if="canDownload" :disabled="downloading" class="secondary" @click="download">{{ downloading ? '下载中…' : '下载文件' }}</button>
      </div>
      <p v-if="exportStatus" class="muted status">导出状态：{{ exportStatus }}</p>
      <p v-if="exportError" role="alert">{{ exportError }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'
import { useGradeExport } from '../../composables/useGradeExport'

const route = useRoute()
const targetId = computed(() => route.params.targetId)
const rows = ref([])
const classDist = ref({})
const format = ref('XLSX')
const studentNo = ref('')
const nameKeyword = ref('')
const { exportStatus, exportError, exporting, downloading, canDownload, exportGrades: startExport, download, reset: resetExport } = useGradeExport()
const loadError = ref('')
const loading = ref(false)
let loadSequence = 0

function distOf(d) {
  if (!d) return ''
  return Object.entries(d).map(([k, v]) => `${k}:${v}`).join(', ')
}

async function loadAnalytics() {
  const current = ++loadSequence
  rows.value = []
  classDist.value = {}
  loadError.value = ''
  loading.value = true
  try {
    const data = await api(`/teacher/analytics/targets/${targetId.value}`)
    if (current !== loadSequence) return
    rows.value = data.rows
    classDist.value = data.classStatusDistribution
  } catch (err) {
    if (current === loadSequence) loadError.value = err.message || '成绩加载失败'
  } finally {
    if (current === loadSequence) loading.value = false
  }
}

watch(targetId, () => {
  resetExport()
  loadAnalytics()
}, { immediate: true })
onBeforeUnmount(() => { loadSequence++ })

async function exportGrades() {
  await startExport({
    assignmentTargetId: Number(targetId.value), format: format.value,
    filterStudentNo: studentNo.value || null, filterNameKeyword: nameKeyword.value || null
  })
}
</script>

<style scoped>
.dist { display: flex; gap: 8px; flex-wrap: wrap; }
.rank {
  display: inline-grid;
  place-items: center;
  min-width: 26px;
  height: 26px;
  padding: 0 6px;
  border-radius: 8px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  font-weight: 600;
  font-size: 13px;
}
.export-card { margin-top: 4px; }
.export-card input { min-width: 170px; }
.status { margin: 12px 0 0; font-size: 13px; }
</style>
