<template>
  <div class="empty-state" :class="`empty-${tone}`">
    <span class="empty-icon" aria-hidden="true">
      <Icon :icon="icon" />
    </span>
    <p class="empty-title">{{ title }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <slot />
  </div>
</template>

<script setup>
/**
 * EmptyState — 统一空状态占位
 * 提供图标 + 标题 + 描述的结构化空态，入场带轻微缩放淡入，避免生硬的空白页面。
 */
defineProps({
  icon: { type: String, default: 'mdi:inbox-outline' },
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
  tone: { type: String, default: 'neutral' } // neutral | ok | warn
})
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-1);
  padding: 46px 20px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius);
  background: var(--panel);
  text-align: center;
  animation: m-scale-in var(--dur-slow) var(--ease-spring) both;
}

.empty-icon {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  font-size: var(--fs-3xl);
  margin-bottom: 6px;
  background: var(--panel-2);
  color: var(--muted);
}

.empty-ok .empty-icon { background: var(--ok-soft); color: var(--ok); }
.empty-warn .empty-icon { background: var(--warn-soft); color: var(--warn); }

.empty-title { margin: 0; font-weight: 600; color: var(--text); }
.empty-desc { margin: 0; font-size: var(--fs-sm); color: var(--muted); max-width: 420px; }
</style>
