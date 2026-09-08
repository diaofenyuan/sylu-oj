<template>
  <div class="analytics-enhanced">
    <router-link to="/teacher/assignments" class="back-link"><Icon icon="mdi:arrow-left" aria-hidden="true" />返回作业管理</router-link>
    <div class="page-head">
      <h2>成绩分析</h2>
      <p class="muted">查看班级提交情况、分数分布与学生成绩。</p>
    </div>

    <p v-if="loading" role="status">成绩加载中…</p>
    <p v-if="loadError" role="alert">{{ loadError }} <button @click="loadAnalytics">重新加载</button></p>
    <!-- 概览卡片 -->
    <div class="overview-grid">
      <div class="stat-card">
        <div class="stat-content">
          <div class="stat-value">{{ rows.length }}</div>
          <div class="stat-label">总学生数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-content">
          <div class="stat-value">{{ avgScore.toFixed(1) }}</div>
          <div class="stat-label">平均分</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-content">
          <div class="stat-value">{{ avgPassRate.toFixed(1) }}%</div>
          <div class="stat-label">平均通过率</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-content">
          <div class="stat-value">{{ totalSubmissions }}</div>
          <div class="stat-label">总提交数</div>
        </div>
      </div>
    </div>

    <div class="charts-grid">
    <!-- 状态分布 -->
    <div class="card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:chart-donut" />
          评测结果分布
        </h3>
      </div>
      <div class="status-chart">
        <div v-for="(v, k) in classDist" :key="k" class="status-bar">
          <div class="status-info">
            <span class="status-label">{{ getStatusText(k) }} <small>{{ k }}</small></span>
            <span class="status-value">{{ v }} 次</span>
          </div>
          <div class="status-track">
            <div 
              class="status-fill" 
              :style="{ width: (v / maxStatusCount * 100) + '%' }"
              :class="'status-' + k">
            </div>
          </div>
        </div>
        <div v-if="!Object.keys(classDist).length" class="empty-chart">
          <Icon icon="mdi:information" />
          暂无数据
        </div>
      </div>
    </div>

    <!-- 分数分布图 -->
    <div class="card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:chart-bar" />
          分数分布
        </h3>
      </div>
      <div class="score-distribution">
        <div v-for="bucket in scoreDistribution" :key="bucket.range" class="score-bucket">
          <div class="bucket-bar">
            <div 
              class="bucket-fill" 
              :style="{ height: (bucket.count / maxBucketCount * 100) + '%' }">
            </div>
          </div>
          <div class="bucket-label">{{ bucket.range }}</div>
          <div class="bucket-count">{{ bucket.count }}人</div>
        </div>
      </div>
    </div>

    </div>

    <!-- 学习情况 -->
    <div class="card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:clock-outline" />
          学习情况
        </h3>
      </div>
      <div class="activity-info">
        <div class="activity-item">
          <Icon icon="mdi:fire" />
          <span>最活跃学生：{{ mostActiveStudent?.name || '-' }} ({{ mostActiveStudent?.count || 0 }} 次提交)</span>
        </div>
        <div class="activity-item">
          <Icon icon="mdi:alert-circle" />
          <span>未提交学生：{{ inactiveStudents }} 人</span>
        </div>
        <div class="activity-item">
          <Icon icon="mdi:trophy" />
          <span>满分学生：{{ perfectScoreStudents }} 人</span>
        </div>
      </div>
    </div>

    <!-- 学生成绩表 -->
    <div class="card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:podium" />
          学生成绩
        </h3>
        <div class="header-actions">
          <input 
            v-model="searchKeyword" 
            placeholder="搜索学号或姓名"
            aria-label="搜索学生成绩"
            type="search"
            class="search-input"
          />
        </div>
      </div>
      
      <div class="table-container">
        <table>
          <thead>
            <tr>
              <th :aria-sort="sortField === 'rank' ? (sortOrder === 'asc' ? 'ascending' : 'descending') : 'none'"><button class="sort-button" @click="sortBy('rank')">
                排名
                <Icon v-if="sortField === 'rank'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </button></th>
              <th :aria-sort="sortField === 'studentNo' ? (sortOrder === 'asc' ? 'ascending' : 'descending') : 'none'"><button class="sort-button" @click="sortBy('studentNo')">
                学号
                <Icon v-if="sortField === 'studentNo'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </button></th>
              <th :aria-sort="sortField === 'name' ? (sortOrder === 'asc' ? 'ascending' : 'descending') : 'none'"><button class="sort-button" @click="sortBy('name')">
                姓名
                <Icon v-if="sortField === 'name'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </button></th>
              <th :aria-sort="sortField === 'totalScore' ? (sortOrder === 'asc' ? 'ascending' : 'descending') : 'none'"><button class="sort-button" @click="sortBy('totalScore')">
                总分
                <Icon v-if="sortField === 'totalScore'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </button></th>
              <th :aria-sort="sortField === 'passRate' ? (sortOrder === 'asc' ? 'ascending' : 'descending') : 'none'"><button class="sort-button" @click="sortBy('passRate')">
                通过率
                <Icon v-if="sortField === 'passRate'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </button></th>
              <th :aria-sort="sortField === 'submissionCount' ? (sortOrder === 'asc' ? 'ascending' : 'descending') : 'none'"><button class="sort-button" @click="sortBy('submissionCount')">
                提交次数
                <Icon v-if="sortField === 'submissionCount'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </button></th>
              <th>状态分布</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in filteredRows" :key="r.studentId">
              <td>
                <span class="rank-badge">
                  {{ r.rank }}
                </span>
              </td>
              <td>{{ r.studentNo }}</td>
              <td><strong>{{ r.name }}</strong></td>
              <td>
                <strong class="score-badge">{{ r.totalScore }}</strong>
              </td>
              <td>
                <div class="progress-cell">
                  <div class="mini-progress">
                    <div class="mini-fill" :style="{ width: r.passRate + '%' }"></div>
                  </div>
                  <span>{{ r.passRate }}%</span>
                </div>
              </td>
              <td>{{ r.submissionCount }}</td>
              <td class="status-dist">
                <span v-for="(v, k) in r.statusDistribution" :key="k" class="mini-chip" :class="'chip-' + k">
                  {{ k }}:{{ v }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!filteredRows.length" class="empty">暂无成绩数据</div>
      </div>
    </div>

    <!-- 导出卡片 -->
    <div class="card export-card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:download" />
          导出成绩
        </h3>
      </div>
      <div class="export-form">
        <div class="form-group">
          <label for="grade-format">导出格式</label>
          <select id="grade-format" v-model="format">
            <option value="XLSX">Excel (XLSX)</option>
            <option value="CSV">CSV (ZIP)</option>
          </select>
        </div>
        <div class="form-group">
          <label for="grade-student">学号筛选</label>
          <input id="grade-student" v-model="studentNo" placeholder="可选" />
        </div>
        <div class="form-group">
          <label for="grade-name">姓名关键词</label>
          <input id="grade-name" v-model="nameKeyword" placeholder="可选" />
        </div>
      </div>
      <div class="export-actions">
        <button @click="exportGrades" :disabled="exporting || downloading || loading || !!loadError" class="btn-primary">
          <Icon icon="mdi:file-export" />
          {{ exporting ? '导出中…' : '发起导出' }}
        </button>
        <button v-if="canDownload" @click="download" :disabled="downloading" class="btn-success">
          <Icon icon="mdi:download" />
          {{ downloading ? '下载中…' : '下载文件' }}
        </button>
      </div>
      <p v-if="exportStatus" class="export-status">
        <Icon icon="mdi:information" />
        导出状态：{{ exportStatus }}
      </p>
      <p v-if="exportError" role="alert">{{ exportError }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'
import { useGradeExport } from '../../composables/useGradeExport'
import { useJudgeStatus } from '../../composables/useJudgeStatus'

const { getStatusText } = useJudgeStatus()

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
const searchKeyword = ref('')
const sortField = ref('rank')
const sortOrder = ref('asc')

// 统计数据
const avgScore = computed(() => {
  if (!rows.value.length) return 0
  return rows.value.reduce((sum, r) => sum + r.totalScore, 0) / rows.value.length
})

const avgPassRate = computed(() => {
  if (!rows.value.length) return 0
  return rows.value.reduce((sum, r) => sum + r.passRate, 0) / rows.value.length
})

const totalSubmissions = computed(() => {
  return rows.value.reduce((sum, r) => sum + r.submissionCount, 0)
})

const maxStatusCount = computed(() => {
  return Math.max(...Object.values(classDist.value), 1)
})

// 分数分布
const scoreDistribution = computed(() => {
  const buckets = [
    { range: '0-20', min: 0, max: 20, count: 0 },
    { range: '21-40', min: 21, max: 40, count: 0 },
    { range: '41-60', min: 41, max: 60, count: 0 },
    { range: '61-80', min: 61, max: 80, count: 0 },
    { range: '81-100', min: 81, max: 100, count: 0 }
  ]
  
  rows.value.forEach(r => {
    const bucket = buckets.find(b => r.totalScore >= b.min && r.totalScore <= b.max)
    if (bucket) bucket.count++
  })
  
  return buckets
})

const maxBucketCount = computed(() => {
  return Math.max(...scoreDistribution.value.map(b => b.count), 1)
})

// 学习情况
const mostActiveStudent = computed(() => {
  if (!rows.value.length) return null
  return rows.value.reduce((max, r) => 
    !max || r.submissionCount > max.count ? { name: r.name, count: r.submissionCount } : max
  , null)
})

const inactiveStudents = computed(() => {
  return rows.value.filter(r => r.submissionCount === 0).length
})

const perfectScoreStudents = computed(() => {
  return rows.value.filter(r => r.passRate === 100).length
})

// 搜索和排序
const filteredRows = computed(() => {
  let result = rows.value
  
  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase()
    result = result.filter(r => 
      r.studentNo.toLowerCase().includes(keyword) || 
      r.name.toLowerCase().includes(keyword)
    )
  }
  
  result = [...result].sort((a, b) => {
    let aVal = a[sortField.value]
    let bVal = b[sortField.value]
    
    if (typeof aVal === 'string') {
      aVal = aVal.toLowerCase()
      bVal = bVal.toLowerCase()
    }
    
    if (sortOrder.value === 'asc') {
      return aVal > bVal ? 1 : -1
    } else {
      return aVal < bVal ? 1 : -1
    }
  })
  
  return result
})

function sortBy(field) {
  if (sortField.value === field) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortField.value = field
    sortOrder.value = 'asc'
  }
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
.analytics-enhanced { display: flex; flex-direction: column; gap: 24px; }
.analytics-enhanced > .card, .analytics-enhanced > .page-head, .back-link { margin-bottom: 0; }
.overview-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); padding: 24px 0; background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius); }
.stat-card { padding: 0 28px; border-right: 1px solid var(--border); }
.stat-card:last-child { border-right: 0; }
.stat-content { display: flex; flex-direction: column; gap: 12px; }
.stat-value { font-size: 30px; font-weight: 600; color: var(--text); line-height: 1.2; font-variant-numeric: tabular-nums; }
.stat-label { order: -1; font-size: 13px; color: var(--muted); }
.charts-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.charts-grid .card { margin-bottom: 0; }
.card-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; margin-bottom: 24px; }
.card-header h3 { display: flex; align-items: center; gap: 8px; margin: 0; }
.card-header h3 > svg { width: 18px; height: 18px; color: var(--muted); }
.header-actions { display: flex; max-width: 100%; }
.search-input { width: 260px; }
.status-chart { display: flex; flex-direction: column; gap: 16px; }
.status-bar { display: flex; flex-direction: column; gap: 8px; }
.status-info { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
.status-label { color: var(--text); }
.status-label small { margin-left: 6px; color: var(--muted); font-size: 11px; }
.status-value { color: var(--muted); font-variant-numeric: tabular-nums; }
.status-track { height: 8px; background: var(--panel-2); border-radius: 3px; overflow: hidden; }
.status-fill { height: 100%; background: var(--muted); border-radius: 3px; }
.status-AC { background: var(--ok); }
.empty-chart { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 40px; color: var(--muted); }
/* 同一坐标下比较所有分数区间，窄屏保持五列，避免换行破坏比较关系。 */
.score-distribution { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 18px; padding-top: 20px; }
.score-bucket { display: flex; flex-direction: column; align-items: center; gap: 10px; min-width: 0; }
.bucket-bar { width: 100%; height: 220px; display: flex; align-items: flex-end; justify-content: center; border-bottom: 1px solid var(--border); }
.bucket-fill { width: 65%; min-width: 12px; background: var(--accent); border-radius: 3px 3px 0 0; }
.bucket-label { font-size: 12px; white-space: nowrap; color: var(--text); }
.bucket-count { font-size: 12px; color: var(--muted); }
.activity-info { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px; }
.activity-item { display: flex; align-items: flex-start; gap: 10px; font-size: 13px; line-height: 1.7; }
.activity-item > svg { width: 18px; height: 18px; flex-shrink: 0; margin-top: 2px; color: var(--muted); }
.table-container { overflow-x: auto; }
.table-container table { border: 0; border-radius: 0; margin-bottom: 0; }
.sort-button { padding: 0; min-height: 32px; border: 0; border-radius: 0; background: transparent; color: var(--muted); font-size: inherit; font-weight: inherit; white-space: nowrap; }
.sort-button:hover { background: transparent; color: var(--text); }
.sort-button > svg { width: 14px; height: 14px; }
.rank-badge { font-variant-numeric: tabular-nums; color: var(--muted); }
.score-badge { font-size: 14px; color: var(--text); }
.progress-cell { display: flex; align-items: center; gap: 8px; font-variant-numeric: tabular-nums; }
.mini-progress { width: 48px; height: 4px; background: var(--panel-2); border-radius: 2px; overflow: hidden; }
.mini-fill { height: 100%; background: var(--accent); }
.status-dist { min-width: 140px; }
.mini-chip { display: inline-block; padding: 2px 5px; margin: 2px; border-radius: 4px; font-size: 11px; white-space: nowrap; background: var(--panel-2); color: var(--muted); }
.chip-AC { background: var(--ok-soft); color: var(--ok); }
.chip-WA { background: var(--danger-soft); color: var(--danger); }
.chip-TLE, .chip-MLE { background: var(--warn-soft); color: var(--warn); }
.export-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; margin-bottom: 20px; }
.form-group { display: flex; flex-direction: column; gap: 8px; }
.form-group label { font-size: 13px; color: var(--muted); }
.export-actions { display: flex; flex-wrap: wrap; gap: 12px; }
.btn-primary { background: var(--button-bg); color: #fff; }
.btn-success { background: var(--panel); color: var(--text); border-color: var(--border-strong); }
.btn-success:hover { background: var(--panel-2); color: var(--text); }
.export-status { display: flex; align-items: center; gap: 6px; margin-top: 16px; font-size: 13px; color: var(--muted); }
@media (max-width: 1000px) { .activity-info { grid-template-columns: 1fr; gap: 16px; } .stat-card { padding-inline: 20px; } }
@media (max-width: 768px) {
  .overview-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0; }
  .stat-card { padding: 20px; }
  .stat-card:nth-child(2n) { border-right: 0; }
  .stat-card:nth-child(-n+2) { border-bottom: 1px solid var(--border); }
  .stat-value { font-size: 26px; }
  .charts-grid, .export-form { grid-template-columns: 1fr; }
  .score-distribution { gap: 8px; }
  .bucket-bar { height: 160px; }
  .header-actions, .search-input { width: 100%; }
}
</style>
