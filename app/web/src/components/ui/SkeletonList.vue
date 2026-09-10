<template>
  <div class="skeleton-list" role="status" aria-live="polite" aria-busy="true">
    <div v-for="n in count" :key="n" class="card skeleton-card">
      <div class="row">
        <div class="skeleton skeleton-avatar"></div>
        <div class="skeleton-lines">
          <div class="skeleton skeleton-line" style="--skeleton-w: 42%"></div>
          <div class="skeleton skeleton-line" style="--skeleton-w: 68%"></div>
        </div>
        <div class="spacer"></div>
        <div class="skeleton skeleton-btn"></div>
      </div>
    </div>
    <span class="sr-only">内容加载中</span>
  </div>
</template>

<script setup>
/**
 * SkeletonList — 列表加载骨架屏
 * 用于卡片列表在数据返回前的占位，形状与 .card 列表保持一致以避免布局跳动。
 */
defineProps({
  count: { type: Number, default: 3 }
})
</script>

<style scoped>
.skeleton-card { padding: 18px 20px; }
.skeleton-card:hover { transform: none; box-shadow: var(--shadow-sm); }
.skeleton-avatar { flex: none; width: 42px; height: 42px; border-radius: 12px; }
.skeleton-lines { flex: 1; display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; }
.skeleton-lines .skeleton-line { margin-bottom: 0; }
.skeleton-btn { width: 104px; height: 34px; border-radius: 10px; flex: none; }
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
@media (max-width: 640px) {
  .skeleton-btn { display: none; }
}
</style>
