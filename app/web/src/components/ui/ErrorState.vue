<template>
  <div class="error-state" role="alert">
    <span class="error-icon" aria-hidden="true">
      <Icon :icon="icon" />
    </span>
    <p class="error-title">{{ title }}</p>
    <p v-if="description" class="error-desc">{{ description }}</p>
    <p v-if="detail" class="error-detail">{{ detail }}</p>
    <button v-if="retryable" type="button" class="secondary" :class="{ 'is-loading': retrying }"
            :disabled="retrying" @click="emit('retry')">
      {{ retryText }}
    </button>
  </div>
</template>

<script setup>
/**
 * ErrorState — 统一错误状态
 * -----------------------------------------------------------------------------
 * 与 EmptyState 语义明确区分：
 *   EmptyState 表示「请求成功但没有数据」；
 *   ErrorState 表示「请求失败，数据未知」。
 * 混用会让用户误判（例如后端 500 时显示"暂无作业"）。
 *
 * 用法：
 *   <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />
 */
defineProps({
  icon: { type: String, default: 'mdi:server-network-off' },
  title: { type: String, default: '加载失败' },
  description: { type: String, default: '请检查网络连接后重试' },
  // 具体错误信息（如接口返回的 message），便于用户反馈与运维定位
  detail: { type: String, default: '' },
  retryText: { type: String, default: '重新加载' },
  retryable: { type: Boolean, default: true },
  retrying: { type: Boolean, default: false }
})

const emit = defineEmits(['retry'])
</script>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 42px 20px;
  border: 1px dashed color-mix(in srgb, var(--danger) 42%, transparent);
  border-radius: var(--radius);
  background: var(--panel);
  text-align: center;
  animation: m-fade-in-up var(--dur-slow) var(--ease-out) both;
}

.error-icon {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  font-size: var(--fs-3xl);
  margin-bottom: 6px;
  background: var(--danger-soft);
  color: var(--danger);
}

.error-title { margin: 0; font-weight: 600; color: var(--text); }
.error-desc { margin: 0; font-size: var(--fs-sm); color: var(--muted); max-width: 460px; }

.error-detail {
  margin: var(--space-1) 0 0;
  font-size: var(--fs-xs);
  color: var(--muted);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 10px;
  max-width: 520px;
  word-break: break-word;
}

.error-state button { margin-top: var(--space-3); width: auto; }
</style>
