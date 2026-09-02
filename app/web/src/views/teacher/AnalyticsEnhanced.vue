<template>
  <div class="analytics-enhanced">
    <div class="page-head">
      <h2>成绩分析（目标班级 #{{ targetId }}）</h2>
      <p class="muted">班级表现多维统计与成绩导出</p>
    </div>

    <!-- 概览卡片 -->
    <div class="overview-grid">
      <div class="stat-card">
        <Icon icon="mdi:account-group" class="stat-icon" />
        <div class="stat-content">
          <div class="stat-value">{{ rows.length }}</div>
          <div class="stat-label">总学生数</div>
        </div>
      </div>
      <div class="stat-card">
        <Icon icon="mdi:chart-line" class="stat-icon success" />
        <div class="stat-content">
          <div class="stat-value">{{ avgScore.toFixed(1) }}</div>
          <div class="stat-label">平均分</div>
        </div>
      </div>
      <div class="stat-card">
        <Icon icon="mdi:percent" class="stat-icon accent" />
        <div class="stat-content">
          <div class="stat-value">{{ avgPassRate.toFixed(1) }}%</div>
          <div class="stat-label">平均通过率</div>
        </div>
      </div>
      <div class="stat-card">
        <Icon icon="mdi:file-document-multiple" class="stat-icon warn" />
        <div class="stat-content">
          <div class="stat-value">{{ totalSubmissions }}</div>
          <div class="stat-label">总提交数</div>
        </div>
      </div>
    </div>

    <!-- 状态分布 -->
    <div class="card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:chart-donut" />
          班级状态分布
        </h3>
      </div>
      <div class="status-chart">
        <div v-for="(v, k) in classDist" :key="k" class="status-bar">
          <div class="status-info">
            <span class="status-label">{{ k }}</span>
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

    <!-- 提交时间热力图 -->
    <div class="card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:clock-outline" />
          活跃度分析
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

    <!-- 学生排名表 -->
    <div class="card">
      <div class="card-header">
        <h3>
          <Icon icon="mdi:podium" />
          学生排名
        </h3>
        <div class="header-actions">
          <input 
            v-model="searchKeyword" 
            placeholder="搜索学号或姓名..."
            class="search-input"
          />
        </div>
      </div>
      
      <div class="table-container">
        <table>
          <thead>
            <tr>
              <th @click="sortBy('rank')">
                排名
                <Icon v-if="sortField === 'rank'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </th>
              <th @click="sortBy('studentNo')">
                学号
                <Icon v-if="sortField === 'studentNo'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </th>
              <th @click="sortBy('name')">
                姓名
                <Icon v-if="sortField === 'name'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </th>
              <th @click="sortBy('totalScore')">
                总分
                <Icon v-if="sortField === 'totalScore'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </th>
              <th @click="sortBy('passRate')">
                通过率
                <Icon v-if="sortField === 'passRate'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </th>
              <th @click="sortBy('submissionCount')">
                提交次数
                <Icon v-if="sortField === 'submissionCount'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
              </th>
              <th>状态分布</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in filteredRows" :key="r.studentId" :class="getRankClass(r.rank)">
              <td>
                <span class="rank-badge" :class="getRankClass(r.rank)">
                  <Icon v-if="r.rank <= 3" :icon="getRankIcon(r.rank)" />
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
          <label>导出格式</label>
          <select v-model="format">
            <option value="XLSX">Excel (XLSX)</option>
            <option value="CSV">CSV (ZIP)</option>
          </select>
        </div>
        <div class="form-group">
          <label>学号筛选</label>
          <input v-model="studentNo" placeholder="可选" />
        </div>
        <div class="form-group">
          <label>姓名关键词</label>
          <input v-model="nameKeyword" placeholder="可选" />
        </div>
      </div>
      <div class="export-actions">
        <button @click="exportGrades" class="btn-primary">
          <Icon icon="mdi:file-export" />
          发起导出
        </button>
        <button v-if="downloadToken" @click="download" class="btn-success">
          <Icon icon="mdi:download" />
          下载文件
        </button>
      </div>
      <p v-if="exportStatus" class="export-status">
        <Icon icon="mdi:information" />
        导出状态：{{ exportStatus }}
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'

const route = useRoute()
const targetId = route.params.targetId
const rows = ref([])
const classDist = ref({})
const format = ref('XLSX')
const studentNo = ref('')
const nameKeyword = ref('')
const exportStatus = ref('')
const downloadToken = ref('')
const searchKeyword = ref('')
const sortField = ref('rank')
const sortOrder = ref('asc')
let exportId = null

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

// 活跃度分析
const mostActiveStudent = computed(() => {
  if (!rows.value.length) return null
  return rows.value.reduce((max, r) => 
    r.submissionCount > (max?.submissionCount || 0) ? { name: r.name, count: r.submissionCount } : max
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

function getRankClass(rank) {
  if (rank === 1) return 'rank-gold'
  if (rank === 2) return 'rank-silver'
  if (rank === 3) return 'rank-bronze'
  return ''
}

function getRankIcon(rank) {
  if (rank === 1) return 'mdi:trophy'
  if (rank === 2) return 'mdi:medal'
  if (rank === 3) return 'mdi:medal-outline'
  return ''
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
.analytics-enhanced {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 概览卡片网格 */
.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition: all 0.2s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.stat-icon {
  font-size: 40px;
  color: var(--text);
}

.stat-icon.success { color: var(--ok); }
.stat-icon.accent { color: var(--accent); }
.stat-icon.warn { color: var(--warn); }

.stat-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--text);
  line-height: 1;
}

.stat-label {
  font-size: 13px;
  color: var(--muted);
}

/* 卡片头部 */
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.card-header h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.search-input {
  min-width: 200px;
}

/* 状态图表 */
.status-chart {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.status-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.status-info {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}

.status-label {
  font-weight: 600;
  color: var(--text);
}

.status-value {
  color: var(--muted);
}

.status-track {
  height: 24px;
  background: var(--panel-2);
  border-radius: 12px;
  overflow: hidden;
}

.status-fill {
  height: 100%;
  transition: width 0.6s ease;
  border-radius: 12px;
}

.status-AC { background: linear-gradient(90deg, #10b981, #059669); }
.status-WA { background: linear-gradient(90deg, #ef4444, #dc2626); }
.status-TLE { background: linear-gradient(90deg, #f59e0b, #d97706); }
.status-MLE { background: linear-gradient(90deg, #a855f7, #9333ea); }
.status-CE { background: linear-gradient(90deg, #eab308, #ca8a04); }
.status-RE { background: linear-gradient(90deg, #f97316, #ea580c); }

.empty-chart {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--muted);
}

/* 分数分布 */
.score-distribution {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
  padding: 20px;
}

.score-bucket {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.bucket-bar {
  width: 100%;
  height: 150px;
  background: var(--panel-2);
  border-radius: 8px 8px 0 0;
  display: flex;
  align-items: flex-end;
  overflow: hidden;
}

.bucket-fill {
  width: 100%;
  background: linear-gradient(180deg, var(--accent), var(--accent-dark));
  transition: height 0.6s ease;
  border-radius: 4px 4px 0 0;
}

.bucket-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
}

.bucket-count {
  font-size: 11px;
  color: var(--muted);
}

/* 活跃度信息 */
.activity-info {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.activity-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px;
  background: var(--panel-2);
  border-radius: 8px;
  font-size: 14px;
}

/* 表格增强 */
.table-container {
  overflow-x: auto;
}

table th {
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

table th:hover {
  background: var(--panel-2);
}

.rank-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-radius: 8px;
  font-weight: 600;
  font-size: 13px;
  background: var(--panel-2);
  border: 1px solid var(--border);
}

.rank-badge.rank-gold {
  background: linear-gradient(135deg, #fef3c7, #fde68a);
  border-color: #f59e0b;
  color: #92400e;
}

.rank-badge.rank-silver {
  background: linear-gradient(135deg, #f1f5f9, #e2e8f0);
  border-color: #94a3b8;
  color: #475569;
}

.rank-badge.rank-bronze {
  background: linear-gradient(135deg, #fed7aa, #fdba74);
  border-color: #ea580c;
  color: #7c2d12;
}

.score-badge {
  font-size: 16px;
  color: var(--accent);
}

.progress-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mini-progress {
  width: 60px;
  height: 8px;
  background: var(--panel-2);
  border-radius: 4px;
  overflow: hidden;
}

.mini-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--accent), var(--accent-dark));
  transition: width 0.3s ease;
}

.status-dist {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.mini-chip {
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.chip-AC { background: var(--ok-soft); color: var(--ok); }
.chip-WA { background: var(--danger-soft); color: var(--danger); }
.chip-TLE { background: var(--warn-soft); color: var(--warn); }
.chip-MLE { background: rgba(168, 85, 247, 0.1); color: #a855f7; }
.chip-CE { background: rgba(234, 179, 8, 0.1); color: #eab308; }
.chip-RE { background: rgba(249, 115, 22, 0.1); color: #f97316; }

/* 导出表单 */
.export-form {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.export-actions {
  display: flex;
  gap: 12px;
}

.btn-primary {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--accent);
  color: #fff;
}

.btn-success {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--ok);
  color: #fff;
}

.export-status {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 8px;
  font-size: 13px;
  color: var(--accent);
}

@media (max-width: 1024px) {
  .overview-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  
  .activity-info {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
  
  .score-distribution {
    grid-template-columns: repeat(3, 1fr);
  }
  
  .export-form {
    grid-template-columns: 1fr;
  }
}
</style>
