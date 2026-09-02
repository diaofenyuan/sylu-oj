<template>
  <div class="progress-card">
    <div class="progress-header">
      <div class="header-info">
        <h3>{{ title }}</h3>
        <p class="subtitle">{{ subtitle }}</p>
      </div>
      <div class="progress-circle">
        <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
          <circle
            :cx="size / 2"
            :cy="size / 2"
            :r="radius"
            fill="none"
            :stroke="trackColor"
            :stroke-width="strokeWidth"
          />
          <circle
            :cx="size / 2"
            :cy="size / 2"
            :r="radius"
            fill="none"
            :stroke="progressColor"
            :stroke-width="strokeWidth"
            :stroke-dasharray="circumference"
            :stroke-dashoffset="dashOffset"
            stroke-linecap="round"
            class="progress-ring"
          />
        </svg>
        <div class="progress-text">
          <span class="percent">{{ percentage }}%</span>
          <span class="label">完成</span>
        </div>
      </div>
    </div>

    <div class="progress-body">
      <div class="stat-grid">
        <div class="stat-item">
          <Icon icon="mdi:check-circle" class="stat-icon success" />
          <div class="stat-info">
            <span class="stat-value">{{ completed }}</span>
            <span class="stat-label">已完成</span>
          </div>
        </div>

        <div class="stat-item">
          <Icon icon="mdi:clock-outline" class="stat-icon pending" />
          <div class="stat-info">
            <span class="stat-value">{{ inProgress }}</span>
            <span class="stat-label">进行中</span>
          </div>
        </div>

        <div class="stat-item">
          <Icon icon="mdi:alert-circle" class="stat-icon todo" />
          <div class="stat-info">
            <span class="stat-value">{{ todo }}</span>
            <span class="stat-label">未开始</span>
          </div>
        </div>

        <div class="stat-item">
          <Icon icon="mdi:format-list-checks" class="stat-icon total" />
          <div class="stat-info">
            <span class="stat-value">{{ total }}</span>
            <span class="stat-label">总题目</span>
          </div>
        </div>
      </div>

      <div class="progress-bar-container">
        <div class="progress-bar-track">
          <div class="progress-bar-fill completed-fill" :style="{ width: completedPercent + '%' }"></div>
          <div class="progress-bar-fill progress-fill" :style="{ width: inProgressPercent + '%', left: completedPercent + '%' }"></div>
        </div>
        <div class="progress-bar-labels">
          <span class="label-item completed-label">{{ completedPercent }}%</span>
          <span class="label-item progress-label">{{ inProgressPercent }}%</span>
          <span class="label-item todo-label">{{ todoPercent }}%</span>
        </div>
      </div>

      <div v-if="deadline" class="deadline-info">
        <Icon :icon="isOverdue ? 'mdi:alert-circle' : 'mdi:calendar-clock'" />
        <span :class="{ overdue: isOverdue }">
          {{ isOverdue ? '已截止' : '截止时间' }}: {{ formattedDeadline }}
        </span>
        <span v-if="!isOverdue" class="time-remaining">
          (剩余 {{ timeRemaining }})
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  title: {
    type: String,
    default: '作业进度'
  },
  subtitle: {
    type: String,
    default: ''
  },
  completed: {
    type: Number,
    default: 0
  },
  inProgress: {
    type: Number,
    default: 0
  },
  todo: {
    type: Number,
    default: 0
  },
  deadline: {
    type: String,
    default: null
  },
  size: {
    type: Number,
    default: 120
  },
  strokeWidth: {
    type: Number,
    default: 10
  }
})

const total = computed(() => props.completed + props.inProgress + props.todo)
const percentage = computed(() => {
  if (total.value === 0) return 0
  return Math.round((props.completed / total.value) * 100)
})

const completedPercent = computed(() => {
  if (total.value === 0) return 0
  return Math.round((props.completed / total.value) * 100)
})

const inProgressPercent = computed(() => {
  if (total.value === 0) return 0
  return Math.round((props.inProgress / total.value) * 100)
})

const todoPercent = computed(() => {
  if (total.value === 0) return 0
  return Math.round((props.todo / total.value) * 100)
})

const radius = computed(() => (props.size - props.strokeWidth) / 2)
const circumference = computed(() => 2 * Math.PI * radius.value)
const dashOffset = computed(() => circumference.value * (1 - percentage.value / 100))

const trackColor = computed(() => 'var(--border)')
const progressColor = computed(() => {
  if (percentage.value >= 80) return 'var(--ok)'
  if (percentage.value >= 50) return 'var(--accent)'
  return 'var(--warn)'
})

const isOverdue = computed(() => {
  if (!props.deadline) return false
  return new Date(props.deadline) < new Date()
})

const formattedDeadline = computed(() => {
  if (!props.deadline) return ''
  return props.deadline.replace('T', ' ').slice(0, 16)
})

const timeRemaining = computed(() => {
  if (!props.deadline) return ''
  const now = new Date()
  const end = new Date(props.deadline)
  const diff = end - now
  
  if (diff < 0) return '已截止'
  
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))
  
  if (days > 0) return `${days}天${hours}小时`
  if (hours > 0) return `${hours}小时`
  return '即将截止'
})
</script>

<style scoped>
.progress-card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px;
  box-shadow: var(--shadow-sm);
}

.progress-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.header-info h3 {
  margin: 0 0 4px;
  font-size: 18px;
  color: var(--text);
}

.subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

.progress-circle {
  position: relative;
  width: 120px;
  height: 120px;
}

.progress-ring {
  transform: rotate(-90deg);
  transform-origin: 50% 50%;
  transition: stroke-dashoffset 0.6s ease;
}

.progress-text {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
}

.percent {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: var(--text);
  line-height: 1;
}

.label {
  display: block;
  font-size: 12px;
  color: var(--muted);
  margin-top: 4px;
}

.progress-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  background: var(--panel-2);
  border-radius: 8px;
}

.stat-icon {
  font-size: 24px;
}

.stat-icon.success { color: var(--ok); }
.stat-icon.pending { color: var(--accent); }
.stat-icon.todo { color: var(--muted); }
.stat-icon.total { color: var(--text); }

.stat-info {
  display: flex;
  flex-direction: column;
}

.stat-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--text);
  line-height: 1;
}

.stat-label {
  font-size: 11px;
  color: var(--muted);
  margin-top: 2px;
}

.progress-bar-container {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.progress-bar-track {
  position: relative;
  height: 12px;
  background: var(--panel-2);
  border-radius: 6px;
  overflow: hidden;
}

.progress-bar-fill {
  position: absolute;
  height: 100%;
  transition: width 0.6s ease, left 0.6s ease;
}

.completed-fill {
  background: linear-gradient(90deg, #10b981, #059669);
  left: 0;
}

.progress-fill {
  background: linear-gradient(90deg, #3b82f6, #2563eb);
}

.progress-bar-labels {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--muted);
}

.deadline-info {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 8px;
  font-size: 13px;
  color: var(--accent);
}

.deadline-info.overdue {
  background: var(--danger-soft);
  border-color: var(--danger);
  color: var(--danger);
}

.overdue {
  color: var(--danger) !important;
}

.time-remaining {
  margin-left: auto;
  font-weight: 600;
}

@media (max-width: 768px) {
  .progress-header {
    flex-direction: column;
    gap: 20px;
    text-align: center;
  }
  
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
