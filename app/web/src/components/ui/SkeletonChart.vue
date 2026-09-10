<template>
  <div class="card skeleton-chart" role="status" aria-busy="true" aria-live="polite">
    <div class="sk-head">
      <span class="skeleton sk-title"></span>
    </div>

    <!-- bars：柱状/分布类图表；rows：横向条形列表；blocks：热力图或分块类内容 -->
    <div v-if="variant === 'bars'" class="sk-bars"
         :style="{ '--sk-cols': buckets, '--sk-h': `${height}px` }">
      <div v-for="n in buckets" :key="n" class="sk-bucket">
        <div class="sk-track">
          <span class="skeleton sk-bar" :style="{ '--sk-bar-h': barHeight(n) }"></span>
        </div>
        <span class="skeleton sk-label"></span>
      </div>
    </div>

    <div v-else-if="variant === 'rows'" class="sk-rows">
      <div v-for="n in buckets" :key="n" class="sk-row">
        <span class="skeleton sk-row-label"></span>
        <span class="skeleton sk-row-track">
          <span class="sk-row-fill" :style="{ '--sk-w': rowWidth(n) }"></span>
        </span>
      </div>
    </div>

    <div v-else class="sk-blocks" :style="{ '--sk-cols': buckets }">
      <span v-for="n in buckets" :key="n" class="skeleton sk-block"></span>
    </div>

    <span class="sr-only">图表加载中</span>
  </div>
</template>

<script setup>
/**
 * SkeletonChart — 图表卡片加载骨架
 * -----------------------------------------------------------------------------
 * 结构对齐真实图表卡片：外层同样使用 .card，绘图区预留与图表一致的高度，
 * 使数据到达时卡片自身高度不变（否则会连带推动下方内容，产生布局偏移）。
 *
 * 用法：
 *   <SkeletonChart variant="bars" :height="150" :buckets="5" />
 */
defineProps({
  variant: { type: String, default: 'bars' }, // bars | rows | blocks
  // 绘图区高度，应与真实图表的目标高度一致
  height: { type: Number, default: 150 },
  // bars/blocks 为柱子数量，rows 为条形行数
  buckets: { type: Number, default: 5 }
})

// 高度错落，让骨架看起来像一组分布而不是等高的方块；取值稳定，骨架自身不抖动
const barHeight = (n) => `${28 + ((n * 23) % 62)}%`
const rowWidth = (n) => `${40 + ((n * 29) % 55)}%`
</script>

<style scoped>
.skeleton-chart { margin-bottom: var(--space-4); }
.sk-head { margin-bottom: var(--space-4); }
.sk-title { display: block; width: 132px; height: 16px; border-radius: 6px; }

.sk-bars {
  display: grid;
  grid-template-columns: repeat(var(--sk-cols, 5), 1fr);
  gap: var(--space-4);
  padding: var(--space-5) 0 0;
}
.sk-bucket {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
}
/* 与真实柱状图的绘图区一致：底部对齐、固定高度 */
.sk-track {
  width: 100%;
  height: var(--sk-h, 150px);
  display: flex;
  align-items: flex-end;
}
.sk-bar {
  display: block;
  width: 100%;
  height: var(--sk-bar-h, 50%);
  min-height: 8px;
  border-radius: 8px 8px 0 0;
}
.sk-label { display: block; width: 60%; height: 11px; border-radius: 5px; }

.sk-blocks {
  display: grid;
  grid-template-columns: repeat(var(--sk-cols, 5), 1fr);
  gap: var(--space-3);
}
.sk-block { display: block; height: 56px; border-radius: 10px; }

/* 横向条形列表：对应"状态分布""活跃度"这类逐行展示的图表 */
.sk-rows { display: flex; flex-direction: column; gap: var(--space-3); }
.sk-row { display: flex; align-items: center; gap: var(--space-3); }
.sk-row-label { display: block; flex: none; width: 84px; height: 12px; border-radius: 5px; }
.sk-row-track {
  display: block;
  flex: 1;
  height: 14px;
  border-radius: 999px;
  overflow: hidden;
}
.sk-row-fill { display: block; height: 100%; width: var(--sk-w, 60%); background: var(--border); }

.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
</style>
