<template>
  <div>
    <div class="page-head" v-reveal>
      <h2>成绩分析（目标班级 #{{ targetId }}）</h2>
      <p class="muted">班级表现多维统计与成绩导出</p>
    </div>

    <!-- 骨架结构对齐真实内容（分布卡 + 表格 + 导出卡），避免数据到达时页面高度大幅变化 -->
    <template v-if="loading">
      <div class="card">
        <span class="skeleton sk-title"></span>
        <div class="sk-chips">
          <span v-for="n in 4" :key="n" class="skeleton sk-chip"></span>
        </div>
      </div>
      <SkeletonTable :rows="5" :cols="7" />
      <div class="card">
        <span class="skeleton sk-title"></span>
        <div class="sk-fields">
          <span v-for="n in 4" :key="n" class="skeleton sk-field"></span>
        </div>
      </div>
    </template>

    <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />

    <template v-else>
      <div class="card" v-reveal="{ delay: 60 }">
        <h3>班级状态分布</h3>
        <div class="dist">
          <span v-for="(v, k) in classDist" :key="k" class="chip chip-primary">{{ k }}: {{ v }}</span>
          <span v-if="!Object.keys(classDist).length" class="muted">暂无数据</span>
        </div>
      </div>

      <table v-if="rows.length" class="fade-in">
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
      <EmptyState v-if="!rows.length" icon="mdi:chart-box-outline" title="暂无成绩数据"
                  description="学生提交并完成判题后，成绩将在此汇总" />

      <div class="card export-card" v-reveal="{ delay: 120 }">
        <h3>导出成绩</h3>
        <div class="row">
          <select v-model="format"><option value="XLSX">XLSX</option><option value="CSV">CSV(ZIP)</option></select>
          <input v-model="studentNo" placeholder="学号筛选（可选）" />
          <input v-model="nameKeyword" placeholder="姓名关键词（可选）" />
          <button :class="{ 'is-loading': exporting }" :disabled="exporting" @click="exportGrades">发起导出</button>
          <button v-if="downloadToken" class="secondary" @click="download">下载文件</button>
        </div>
        <p v-if="exportStatus" class="muted status">
          <span class="spinner status-spinner" aria-hidden="true"></span>
          导出状态：{{ exportStatus }}
        </p>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'
import { useAsyncData, describeError } from '../../composables/useAsyncData'
import { useToast } from '../../composables/useToast'

const route = useRoute()
const targetId = route.params.targetId
const toast = useToast()

// 一次请求同时产出明细与分布，避免两个 loading 状态互相不同步
const { data, loading, error, load } = useAsyncData(async () => {
  const res = await api(`/teacher/analytics/targets/${targetId}`)
  return { rows: res.rows || [], dist: res.classStatusDistribution || {} }
})

const rows = computed(() => data.value?.rows ?? [])
const classDist = computed(() => data.value?.dist ?? {})

const exporting = ref(false)
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

async function exportGrades() {
  exporting.value = true
  try {
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
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    exporting.value = false
  }
}

async function pollStatus() {
  try {
    const res = await api(`/teacher/exports/${exportId}`)
    exportStatus.value = res.status
    if (res.status === 'READY') {
      const t = await api(`/teacher/exports/${exportId}/download-token`, { method: 'POST' })
      downloadToken.value = t.token
    } else if (res.status === 'QUEUED' || res.status === 'GENERATING') {
      setTimeout(pollStatus, 1000)
    }
  } catch (e) {
    // 轮询失败不阻塞页面，仅提示并终止本次轮询
    toast.error(describeError(e))
  }
}

function download() {
  const token = downloadToken.value
  window.open(`/api/teacher/exports/download?token=${token}`, '_blank')
}
</script>

<style scoped>
/* 骨架内部元素：尺寸与真实内容对齐，保证加载完成时高度不突变 */
.sk-title { display: block; width: 148px; height: 17px; border-radius: 6px; margin-bottom: var(--space-4); }
.sk-chips { display: flex; gap: var(--space-2); flex-wrap: wrap; }
.sk-chip { display: block; width: 76px; height: 24px; border-radius: 999px; }
.sk-fields { display: flex; gap: var(--space-3); flex-wrap: wrap; }
.sk-field { display: block; width: 168px; height: 38px; border-radius: 10px; }

.dist { display: flex; gap: var(--space-2); flex-wrap: wrap; }
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
  font-size: var(--fs-sm);
}
.export-card { margin-top: var(--space-1); }
.export-card input { min-width: 170px; }
.status { margin: var(--space-3) 0 0; font-size: var(--fs-sm); display: flex; align-items: center; gap: var(--space-2); }
.status-spinner { width: 12px; height: 12px; border-width: 2px; color: var(--accent); }
.fade-in { animation: m-fade-in var(--dur-slow) var(--ease-out) both; }
</style>
