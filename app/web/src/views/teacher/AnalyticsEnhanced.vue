<template>
  <div class="analytics-enhanced">
    <div class="page-head" v-reveal>
      <h2>成绩分析（目标班级 #{{ targetId }}）</h2>
      <p class="muted">班级表现多维统计与成绩导出</p>
    </div>

    <!-- 骨架结构逐块对齐真实内容（指标卡 / 三类图表 / 排名表 / 导出表单），
         加载完成时页面高度基本不变，避免内容"撑开"造成的突兀感 -->
    <template v-if="loading">
      <SkeletonStats :count="4" />
      <SkeletonChart variant="rows" :buckets="5" />
      <SkeletonChart variant="bars" :height="150" :buckets="5" />
      <SkeletonChart variant="rows" :buckets="3" />
      <div class="card sk-panel">
        <span class="skeleton sk-title"></span>
        <SkeletonTable :rows="4" :cols="7" />
      </div>
      <div class="card sk-panel">
        <span class="skeleton sk-title"></span>
        <div class="sk-fields">
          <span v-for="n in 4" :key="n" class="skeleton sk-field"></span>
        </div>
      </div>
    </template>

    <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />

    <template v-else>
    <!-- 概览卡片 -->
    <div class="overview-grid">
      <div class="stat-card" v-reveal="{ delay: 40 }">
        <Icon icon="mdi:account-group" class="stat-icon" />
        <div class="stat-content">
          <div class="stat-value">{{ rows.length }}</div>
          <div class="stat-label">总学生数</div>
        </div>
      </div>
      <div class="stat-card" v-reveal="{ delay: 100 }">
        <Icon icon="mdi:chart-line" class="stat-icon success" />
        <div class="stat-content">
          <div class="stat-value">{{ avgScore.toFixed(1) }}</div>
          <div class="stat-label">平均分</div>
        </div>
      </div>
      <div class="stat-card" v-reveal="{ delay: 160 }">
        <Icon icon="mdi:percent" class="stat-icon accent" />
        <div class="stat-content">
          <div class="stat-value">{{ avgPassRate.toFixed(1) }}%</div>
          <div class="stat-label">平均通过率</div>
        </div>
      </div>
      <div class="stat-card" v-reveal="{ delay: 220 }">
        <Icon icon="mdi:file-document-multiple" class="stat-icon warn" />
        <div class="stat-content">
          <div class="stat-value">{{ totalSubmissions }}</div>
          <div class="stat-label">总提交数</div>
        </div>
      </div>
    </div>

    <!-- 状态分布 -->
    <div class="card" v-reveal="{ delay: 120 }">
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
              :style="{ '--w': (v / maxStatusCount * 100) + '%' }"
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
    <div class="card" v-reveal="{ delay: 180 }">
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
              :style="{ '--h': (bucket.count / maxBucketCount * 100) + '%' }">
            </div>
          </div>
          <div class="bucket-label">{{ bucket.range }}</div>
          <div class="bucket-count">{{ bucket.count }}人</div>
        </div>
      </div>
    </div>

    <!-- 提交时间热力图 -->
    <div class="card" v-reveal="{ delay: 240 }">
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
    <div class="card" v-reveal="{ delay: 300 }">
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
              <th :aria-sort="ariaSort('rank')">
                <button type="button" class="th-sort" @click="sortBy('rank')">
                  排名
                  <Icon v-if="sortField === 'rank'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
                </button>
              </th>
              <th :aria-sort="ariaSort('studentNo')">
                <button type="button" class="th-sort" @click="sortBy('studentNo')">
                  学号
                  <Icon v-if="sortField === 'studentNo'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
                </button>
              </th>
              <th :aria-sort="ariaSort('name')">
                <button type="button" class="th-sort" @click="sortBy('name')">
                  姓名
                  <Icon v-if="sortField === 'name'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
                </button>
              </th>
              <th :aria-sort="ariaSort('totalScore')">
                <button type="button" class="th-sort" @click="sortBy('totalScore')">
                  总分
                  <Icon v-if="sortField === 'totalScore'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
                </button>
              </th>
              <th :aria-sort="ariaSort('passRate')">
                <button type="button" class="th-sort" @click="sortBy('passRate')">
                  通过率
                  <Icon v-if="sortField === 'passRate'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
                </button>
              </th>
              <th :aria-sort="ariaSort('submissionCount')">
                <button type="button" class="th-sort" @click="sortBy('submissionCount')">
                  提交次数
                  <Icon v-if="sortField === 'submissionCount'" :icon="sortOrder === 'asc' ? 'mdi:arrow-up' : 'mdi:arrow-down'" />
                </button>
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
                    <div class="mini-fill" :style="{ '--w': r.passRate + '%' }"></div>
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
    <div class="card export-card" v-reveal="{ delay: 360 }">
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

// 明细与分布来自同一接口，合并为一次加载以保证 loading 状态不分裂
const { data: analytics, loading, error, load } = useAsyncData(async () => {
  const res = await api(`/teacher/analytics/targets/${targetId}`)
  return { rows: res.rows || [], dist: res.classStatusDistribution || {} }
})

const rows = computed(() => analytics.value?.rows ?? [])
const classDist = computed(() => analytics.value?.dist ?? {})
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

/**
 * 供 th 的 aria-sort 使用，向读屏软件播报当前排序字段与方向。
 * 未参与排序的列返回 none，避免读屏把所有列都朗读为可排序状态。
 */
function ariaSort(field) {
  if (sortField.value !== field) return 'none'
  return sortOrder.value === 'asc' ? 'ascending' : 'descending'
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

// 数据加载由 useAsyncData 在 setup 阶段自动发起

async function exportGrades() {
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
.analytics-enhanced {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* 概览卡片网格 */
.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
}

.stat-card {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition: transform var(--dur-base) var(--ease-out), box-shadow var(--dur-base) var(--ease-out),
              border-color var(--dur-base) var(--ease-out);
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
  gap: var(--space-1);
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--text);
  line-height: 1;
}

.stat-label {
  font-size: var(--fs-sm);
  color: var(--muted);
}

/* 卡片头部 */
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-5);
}

.card-header h3 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: var(--space-2);
}

.search-input {
  min-width: 200px;
}

/* 状态图表 */
.status-chart {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.status-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.status-info {
  display: flex;
  justify-content: space-between;
  font-size: var(--fs-sm);
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
  width: var(--w, 0%);
  border-radius: 12px;
  /* 自左向右生长，凸显各状态量级差异 */
  animation: bar-grow-x var(--dur-slower) var(--ease-out) both;
}

@keyframes bar-grow-x {
  from { width: 0; }
  to { width: var(--w, 0%); }
}

.status-AC { background: linear-gradient(90deg, var(--chart-ok), color-mix(in srgb, var(--chart-ok) 80%, #000)); }
.status-WA { background: linear-gradient(90deg, var(--chart-danger), color-mix(in srgb, var(--chart-danger) 80%, #000)); }
.status-TLE { background: linear-gradient(90deg, var(--chart-warn), color-mix(in srgb, var(--chart-warn) 80%, #000)); }
.status-MLE { background: linear-gradient(90deg, var(--chart-purple), color-mix(in srgb, var(--chart-purple) 80%, #000)); }
.status-CE { background: linear-gradient(90deg, var(--chart-yellow), color-mix(in srgb, var(--chart-yellow) 80%, #000)); }
.status-RE { background: linear-gradient(90deg, var(--chart-orange), color-mix(in srgb, var(--chart-orange) 80%, #000)); }

.empty-chart {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--muted);
}

/* 分数分布 */
.score-distribution {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--space-4);
  padding: var(--space-5);
}

.score-bucket {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
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
  height: var(--h, 0%);
  background: linear-gradient(180deg, var(--accent), var(--accent-strong));
  border-radius: 4px 4px 0 0;
  /* 数据到达后自底部生长，强化分布对比的可读性 */
  animation: bar-grow var(--dur-slower) var(--ease-out) both;
}

@keyframes bar-grow {
  from { height: 0; }
  to { height: var(--h, 0%); }
}

.bucket-label {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text);
}

.bucket-count {
  font-size: var(--fs-2xs);
  color: var(--muted);
}

/* 活跃度信息 */
.activity-info {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
}

.activity-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-4);
  background: var(--panel-2);
  border-radius: 8px;
  font-size: var(--fs-base);
}

/* 表格增强 */
.table-container {
  overflow-x: auto;
}

table th {
  user-select: none;
  white-space: nowrap;
}

/* 表头按钮：重置全局 button 的主色底，仅保留排版与交互态 */
.th-sort {
  width: 100%;
  justify-content: flex-start;
  gap: var(--space-1);
  padding: 0;
  border: none;
  border-radius: 6px;
  background: none;
  box-shadow: none;
  color: inherit;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out);
}
.th-sort:hover:not(:disabled) {
  background: none;
  box-shadow: none;
  transform: none;
  color: var(--accent);
}
.th-sort:active:not(:disabled) { transform: none; box-shadow: none; }
.th-sort svg { width: 14px; height: 14px; }

.rank-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 6px 12px;
  border-radius: 8px;
  font-weight: 600;
  font-size: var(--fs-sm);
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
  font-size: var(--fs-lg);
  color: var(--accent);
}

.progress-cell {
  display: flex;
  align-items: center;
  gap: var(--space-2);
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
  background: linear-gradient(90deg, var(--accent), var(--accent-strong));
  animation: bar-grow-x var(--dur-slower) var(--ease-out) both;
}

.status-dist {
  display: flex;
  gap: var(--space-1);
  flex-wrap: wrap;
}

.mini-chip {
  padding: 2px 6px;
  border-radius: 4px;
  font-size: var(--fs-2xs);
  font-weight: 600;
  white-space: nowrap;
}

.chip-AC { background: var(--ok-soft); color: var(--ok); }
.chip-WA { background: var(--danger-soft); color: var(--danger); }
.chip-TLE { background: var(--warn-soft); color: var(--warn); }
.chip-MLE { background: color-mix(in srgb, var(--chart-purple) 12%, transparent); color: var(--chart-purple); }
.chip-CE { background: color-mix(in srgb, var(--chart-yellow) 12%, transparent); color: var(--chart-yellow); }
.chip-RE { background: color-mix(in srgb, var(--chart-orange) 12%, transparent); color: var(--chart-orange); }

/* 导出表单 */
.export-form {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text);
}

.export-actions {
  display: flex;
  gap: var(--space-3);
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
  margin-top: var(--space-3);
  padding: 10px 12px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 8px;
  font-size: var(--fs-sm);
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

/* 骨架内部元素：尺寸与真实内容对齐，保证加载完成时高度不突变 */
.sk-panel .sk-title { display: block; width: 132px; height: 17px; border-radius: 6px; margin-bottom: var(--space-4); }
.sk-fields { display: flex; gap: var(--space-3); flex-wrap: wrap; }
.sk-field { display: block; width: 168px; height: 38px; border-radius: 10px; }
</style>
