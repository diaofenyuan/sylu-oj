<template>
  <div>
    <div class="page-head">
      <h2>成绩分析（目标班级 #{{ targetId }}）</h2>
      <p class="muted">班级表现多维统计与成绩导出</p>
    </div>

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
    <div v-if="!rows.length" class="empty">暂无成绩数据</div>

    <div class="card export-card">
      <h3>导出成绩</h3>
      <div class="row">
        <select v-model="format"><option value="XLSX">XLSX</option><option value="CSV">CSV(ZIP)</option></select>
        <input v-model="studentNo" placeholder="学号筛选（可选）" />
        <input v-model="nameKeyword" placeholder="姓名关键词（可选）" />
        <button @click="exportGrades">发起导出</button>
        <button v-if="downloadToken" class="secondary" @click="download">下载文件</button>
      </div>
      <p v-if="exportStatus" class="muted status">导出状态：{{ exportStatus }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { api, getToken } from '../../api'

const route = useRoute()
const targetId = route.params.targetId
const rows = ref([])
const classDist = ref({})
const format = ref('XLSX')
const studentNo = ref('')
const nameKeyword = ref('')
const exportStatus = ref('')
const downloadToken = ref('')
let exportId = null

function distOf(d) {
  if (!d) return ''
  return Object.entries(d).map(([k, v]) => `${k}:${v}`).join(', ')
}

onMounted(async () => {
  const data = await api(`/teacher/analytics/targets/${targetId}`)
  rows.value = data.rows
  classDist.value = data.classStatusDistribution
})

async function exportGrades() {
  const res = await api('/teacher/exports', {
    method: 'POST',
    body: {
      assignmentTargetId: Number(targetId), format: format.value,
      filterStudentNo: studentNo.value || null, filterNameKeyword: nameKeyword.value || null
    }
  })
  exportId = res.taskId
  exportStatus.value = res.status
  pollStatus()
}

async function pollStatus() {
  const res = await api(`/teacher/exports/${exportId}`)
  exportStatus.value = res.status
  if (res.status === 'READY') {
    const t = await api(`/teacher/exports/${exportId}/download-token`, { method: 'POST' })
    downloadToken.value = t.token
  } else if (res.status === 'QUEUED' || res.status === 'GENERATING') {
    setTimeout(pollStatus, 1000)
  }
}

function download() {
  const token = downloadToken.value
  window.open(`/api/teacher/exports/download?token=${token}`, '_blank')
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
