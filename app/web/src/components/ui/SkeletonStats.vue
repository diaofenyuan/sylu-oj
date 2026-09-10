<template>
  <div class="skeleton-stats" :style="{ '--sk-cols': count }"
       role="status" aria-busy="true" aria-live="polite">
    <div v-for="n in count" :key="n" class="sk-stat">
      <span class="skeleton sk-icon"></span>
      <div class="sk-text">
        <span class="skeleton sk-value"></span>
        <span class="skeleton sk-caption"></span>
      </div>
    </div>
    <span class="sr-only">统计数据加载中</span>
  </div>
</template>

<script setup>
/**
 * SkeletonStats — 概览指标卡加载骨架
 * -----------------------------------------------------------------------------
 * 用于仪表盘顶部的指标卡行。外层栅格列数与真实布局一致，
 * 卡片内的图标/数值/说明三层结构与真实卡片对齐，避免数据到达时的高度变化。
 *
 * 用法：
 *   <SkeletonStats :count="4" />
 */
defineProps({
  count: { type: Number, default: 4 }
})
</script>

<style scoped>
.skeleton-stats {
  display: grid;
  grid-template-columns: repeat(var(--sk-cols, 4), 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.sk-stat {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
}

.sk-icon { flex: none; width: 44px; height: 44px; border-radius: 12px; }
.sk-text { display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; flex: 1; }
.sk-value { display: block; width: 56%; height: 22px; border-radius: 6px; }
.sk-caption { display: block; width: 40%; height: 12px; border-radius: 5px; }

.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

@media (max-width: 900px) {
  .skeleton-stats { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 560px) {
  .skeleton-stats { grid-template-columns: 1fr; }
}
</style>
