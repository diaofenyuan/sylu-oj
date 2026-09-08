<template>
  <div class="difficulty-badge" :class="badgeClass">
    <Icon :icon="difficultyIcon" aria-hidden="true" />
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
.difficulty-badge { display: inline-flex; align-items: center; gap: 6px; padding: 4px 8px; border-radius: 5px; font-size: 12px; font-weight: 500; line-height: 1.5; background: var(--panel-2); color: var(--muted); }
.difficulty-badge > svg { width: 12px; height: 12px; }
.difficulty-stats { display: flex; flex-direction: column; gap: 4px; margin-left: 8px; padding-left: 8px; border-left: 1px solid var(--border-strong); }
.stat-bar { width: 60px; height: 4px; background: var(--border); border-radius: 2px; overflow: hidden; }
.stat-fill { height: 100%; background: currentColor; }
.stat-text { font-size: 11px; white-space: nowrap; }
/* 难度由文字与形状共同表达，颜色只提供辅助提示。 */
.diff-easy { background: var(--ok-soft); color: var(--ok); }
.diff-intermediate { background: var(--warn-soft); color: var(--warn); }
.diff-hard { background: var(--danger-soft); color: var(--danger); }
.diff-expert { color: var(--text); border: 1px solid var(--border-strong); }
</style>
