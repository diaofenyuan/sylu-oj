<template>
  <div class="difficulty-badge" :class="badgeClass">
    <Icon :icon="difficultyIcon" />
    <span class="difficulty-text">{{ difficultyText }}</span>
    <div v-if="showStats" class="difficulty-stats">
      <div class="stat-bar">
        <div class="stat-fill" :style="{ width: passRate + '%' }"></div>
      </div>
      <span class="stat-text">{{ passRate }}% 通过率</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  difficulty: {
    type: String,
    default: 'BASIC'
  },
  passRate: {
    type: Number,
    default: null
  },
  showStats: {
    type: Boolean,
    default: false
  }
})

const difficultyConfig = {
  EASY: {
    text: '简单',
    icon: 'mdi:check-circle',
    class: 'diff-easy'
  },
  BASIC: {
    text: '基础',
    icon: 'mdi:circle',
    class: 'diff-basic'
  },
  INTERMEDIATE: {
    text: '中等',
    icon: 'mdi:triangle',
    class: 'diff-intermediate'
  },
  HARD: {
    text: '困难',
    icon: 'mdi:square',
    class: 'diff-hard'
  },
  EXPERT: {
    text: '专家',
    icon: 'mdi:star',
    class: 'diff-expert'
  }
}

const config = computed(() => {
  // 如果有通过率，自动计算难度
  if (props.passRate !== null && !props.difficulty) {
    if (props.passRate >= 70) return difficultyConfig.EASY
    if (props.passRate >= 50) return difficultyConfig.BASIC
    if (props.passRate >= 30) return difficultyConfig.INTERMEDIATE
    if (props.passRate >= 10) return difficultyConfig.HARD
    return difficultyConfig.EXPERT
  }
  
  return difficultyConfig[props.difficulty] || difficultyConfig.BASIC
})

const badgeClass = computed(() => config.value.class)
const difficultyIcon = computed(() => config.value.icon)
const difficultyText = computed(() => config.value.text)
</script>

<style scoped>
.difficulty-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid;
  transition: all 0.15s ease;
}

.difficulty-badge:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.difficulty-text {
  line-height: 1;
}

.difficulty-stats {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-left: 8px;
  padding-left: 8px;
  border-left: 1px solid currentColor;
}

.stat-bar {
  width: 60px;
  height: 4px;
  background: rgba(0, 0, 0, 0.1);
  border-radius: 2px;
  overflow: hidden;
}

.stat-fill {
  height: 100%;
  background: currentColor;
  transition: width 0.3s ease;
}

.stat-text {
  font-size: 11px;
  opacity: 0.9;
  white-space: nowrap;
}

/* 简单 - 绿色 */
.diff-easy {
  background: linear-gradient(135deg, #ecfdf5, #d1fae5);
  border-color: #10b981;
  color: #059669;
}

.dark .diff-easy {
  background: linear-gradient(135deg, #1a3a2f, #0f2922);
  border-color: #059669;
  color: #10b981;
}

/* 基础 - 蓝色 */
.diff-basic {
  background: linear-gradient(135deg, #eff6ff, #dbeafe);
  border-color: #3b82f6;
  color: #2563eb;
}

.dark .diff-basic {
  background: linear-gradient(135deg, #1e3a5f, #1a2e4a);
  border-color: #2563eb;
  color: #3b82f6;
}

/* 中等 - 橙色 */
.diff-intermediate {
  background: linear-gradient(135deg, #fffbeb, #fef3c7);
  border-color: #f59e0b;
  color: #d97706;
}

.dark .diff-intermediate {
  background: linear-gradient(135deg, #3a2f1a, #2d2515);
  border-color: #d97706;
  color: #f59e0b;
}

/* 困难 - 红色 */
.diff-hard {
  background: linear-gradient(135deg, #fef2f2, #fee2e2);
  border-color: #ef4444;
  color: #dc2626;
}

.dark .diff-hard {
  background: linear-gradient(135deg, #3a1a1a, #2d1515);
  border-color: #dc2626;
  color: #ef4444;
}

/* 专家 - 紫色 */
.diff-expert {
  background: linear-gradient(135deg, #faf5ff, #f3e8ff);
  border-color: #a855f7;
  color: #9333ea;
}

.dark .diff-expert {
  background: linear-gradient(135deg, #2d1a3a, #231a2d);
  border-color: #9333ea;
  color: #a855f7;
}
</style>
