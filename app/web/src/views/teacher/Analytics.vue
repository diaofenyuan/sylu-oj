<template>
  <div>
    <h2>成绩分析（目标班级 #{{ targetId }}）</h2>
    <div class="card">
      <h3>班级状态分布</h3>
      <p class="muted">{{ distText }}</p>
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
          <td>{{ r.rank }}</td><td>{{ r.studentNo }}</td><td>{{ r.name }}</td>
          <td>{{ r.totalScore }}</td><td>{{ r.passRate }}%</td>
          <td>{{ r.submissionCount }}</td><td>{{ distOf(r.statusDistribution) }}</td>
        </tr>
      </tbody>
    </table>

    <div class="card" style="margin-top: 20px">
      <h3>导出成绩</h3>
      <div class="row">
        <select v-model="format"><option value="XLSX">XLSX</option><option value="CSV">CSV(ZIP)</option></select>
        <input v-model="studentNo" placeholder="学号筛选（可选）" />
        <input v-model="nameKeyword" placeholder="姓名关键词（可选）" />
        <button @click="exportGrades">发起导出</button>
      </div>
      <p v-if="exportStatus" class="muted">导出状态：{{ exportStatus }}</p>
      <button v-if="downloadToken" class="secondary" @click="download">下载文件</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
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

const distText = computed(() => distOf(classDist.value))

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
