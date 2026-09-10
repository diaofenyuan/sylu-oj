<template>
  <div class="case-details">
    <div class="details-header" @click="expanded = !expanded">
      <Icon :icon="expanded ? 'mdi:chevron-down' : 'mdi:chevron-right'" />
      <strong>测试点详情</strong>
      <span class="muted">({{ passedCount }}/{{ cases.length }} 通过)</span>
      <span class="spacer"></span>
      <span class="perf-summary">
        <Icon icon="mdi:timer" />
        最慢: {{ maxTime }}ms
        <Icon icon="mdi:memory" style="margin-left: var(--space-3)" />
        峰值: {{ maxMemory }}
      </span>
    </div>
    
    <div v-if="expanded" class="details-body">
      <!-- 测试点列表 -->
      <div class="case-list">
        <div v-for="c in cases" :key="c.order" class="case-item" :class="getCaseClass(c.status)">
          <div class="case-left">
            <span class="case-order">#{{ c.order }}</span>
            <span class="chip" :class="getStatusClass(c.status)">
              <Icon :icon="getStatusIcon(c.status)" />
              {{ getStatusText(c.status) }}
            </span>
            <span v-if="c.score !== undefined" class="case-score">{{ c.score }} 分</span>
          </div>
          <div class="case-right">
            <span class="case-time">
              <Icon icon="mdi:timer" />
              {{ c.timeMs }}ms
            </span>
            <span class="case-memory">
              <Icon icon="mdi:memory" />
              {{ formatMemory(c.memoryKb) }}
            </span>
          </div>
        </div>
      </div>

      <!-- 性能图表 -->
      <div class="perf-charts">
        <div class="chart-section">
          <h4>运行时间分布</h4>
          <div class="bar-chart">
            <div v-for="c in cases" :key="'time-' + c.order" class="bar-item">
              <div class="bar-bg">
                <div class="bar-fill" :style="{ width: getTimePercent(c.timeMs) + '%', background: getBarColor(c.status) }"></div>
              </div>
              <span class="bar-label">{{ c.timeMs }}ms</span>
            </div>
          </div>
        </div>

        <div class="chart-section">
          <h4>内存使用分布</h4>
          <div class="bar-chart">
            <div v-for="c in cases" :key="'mem-' + c.order" class="bar-item">
              <div class="bar-bg">
                <div class="bar-fill" :style="{ width: getMemoryPercent(c.memoryKb) + '%', background: getBarColor(c.status) }"></div>
              </div>
              <span class="bar-label">{{ formatMemory(c.memoryKb) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useJudgeStatus } from '../composables/useJudgeStatus'

const props = defineProps({
  caseDetails: {
    type: Array,
    default: () => []
  }
})

const { getStatusIcon, getStatusText, getStatusClass } = useJudgeStatus()
const expanded = ref(false)

const cases = computed(() => {
  if (!props.caseDetails || props.caseDetails.length === 0) return []
  return props.caseDetails.sort((a, b) => a.order - b.order)
})

const passedCount = computed(() => cases.value.filter(c => c.status === 'AC').length)

const maxTime = computed(() => {
  if (cases.value.length === 0) return 0
  return Math.max(...cases.value.map(c => c.timeMs || 0))
})

const maxMemory = computed(() => {
  if (cases.value.length === 0) return '0KB'
  const max = Math.max(...cases.value.map(c => c.memoryKb || 0))
  return formatMemory(max)
})

function formatMemory(kb) {
  if (kb >= 1024) {
    return (kb / 1024).toFixed(1) + 'MB'
  }
  return kb + 'KB'
}

function getTimePercent(timeMs) {
  if (maxTime.value === 0) return 0
  return (timeMs / maxTime.value) * 100
}

function getMemoryPercent(memoryKb) {
  const max = Math.max(...cases.value.map(c => c.memoryKb || 0))
  if (max === 0) return 0
  return (memoryKb / max) * 100
}

function getCaseClass(status) {
  return status === 'AC' ? 'case-pass' : 'case-fail'
}

function getBarColor(status) {
  if (status === 'AC') return 'var(--ok)'
  if (status === 'TLE') return 'var(--warn)'
  if (status === 'MLE') return 'var(--chart-purple)'
  return 'var(--danger)'
}
</script>

<style scoped>
.case-details {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  margin-top: var(--space-3);
  overflow: hidden;
}

.details-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) 16px;
  cursor: pointer;
  user-select: none;
  transition: background var(--dur-fast) var(--ease-out);
}

.details-header:hover {
  background: var(--panel-2);
}

.perf-summary {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--muted);
}

.details-body {
  border-top: 1px solid var(--border);
  padding: var(--space-4);
}

.case-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
}

.case-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--panel-2);
  border-radius: 8px;
  border-left: 3px solid transparent;
  transition: all var(--dur-fast) var(--ease-out);
}

.case-item:hover {
  background: var(--bg);
}

.case-pass {
  border-left-color: var(--ok);
}

.case-fail {
  border-left-color: var(--danger);
}

.case-left, .case-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.case-order {
  font-weight: 600;
  color: var(--muted);
  min-width: 30px;
}

.case-score {
  font-weight: 600;
  color: var(--accent);
}

.case-time, .case-memory {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--fs-sm);
  color: var(--muted);
}

.perf-charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-6);
}

.chart-section h4 {
  font-size: var(--fs-base);
  margin: 0 0 12px;
  color: var(--text);
}

.bar-chart {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.bar-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.bar-bg {
  flex: 1;
  height: 20px;
  background: var(--panel-2);
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  transition: width var(--dur-base) var(--ease-out);
  border-radius: 4px;
}

.bar-label {
  font-size: var(--fs-xs);
  color: var(--muted);
  min-width: 60px;
  text-align: right;
}

@media (max-width: 768px) {
  .perf-charts {
    grid-template-columns: 1fr;
  }
}
</style>
