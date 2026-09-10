<template>
  <TransitionGroup name="toast" tag="div" class="toast-host">
    <div
      v-for="item in toasts"
      :key="item.id"
      class="toast"
      :class="[`toast-${item.type}`, { 'is-pulsing': item.pulse > 0 }]"
      :role="item.type === 'error' ? 'alert' : 'status'"
      :aria-live="item.type === 'error' ? 'assertive' : 'polite'"
    >
      <Icon :icon="ICONS[item.type]" class="toast-icon" aria-hidden="true" />
      <span class="toast-message">{{ item.message }}</span>
      <button
        type="button"
        class="toast-close"
        aria-label="关闭提示"
        @click="dismiss(item.id)"
      >
        <Icon icon="mdi:close" aria-hidden="true" />
      </button>
    </div>
  </TransitionGroup>
</template>

<script setup>
/**
 * ToastHost — 全局消息提示容器
 * 在 App.vue 中挂载一次；所有提示通过 useToast() 推入。
 *
 * 可访问性：
 *   错误提示使用 role="alert" + aria-live="assertive"，由读屏软件立即播报；
 *   其余提示使用 role="status" + aria-live="polite"，在空闲时播报。
 */
import { toasts, useToast } from '../../composables/useToast'

const { dismiss } = useToast()

const ICONS = {
  success: 'mdi:check-circle',
  error: 'mdi:close-circle',
  warning: 'mdi:alert-circle',
  info: 'mdi:information'
}
</script>

<style scoped>
.toast-host {
  position: fixed;
  left: 50%;
  bottom: 26px;
  transform: translateX(-50%);
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: min(520px, calc(100vw - 32px));
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 11px 12px 11px 14px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: var(--panel);
  box-shadow: var(--shadow-lg);
  font-size: 13.5px;
  line-height: 1.55;
  color: var(--text);
}

.toast-icon { flex: none; font-size: var(--fs-xl); margin-top: 1px; }
.toast-message { flex: 1; min-width: 0; word-break: break-word; text-align: left; }

.toast-close {
  flex: none;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  box-shadow: none;
  color: var(--muted);
  cursor: pointer;
}
.toast-close:hover:not(:disabled) {
  background: var(--panel-2);
  color: var(--text);
  transform: none;
  box-shadow: none;
}
.toast-close svg { width: 14px; height: 14px; }

/* 语义色：沿用全站 chip 的色彩语言，保持一致性 */
.toast-success { border-color: color-mix(in srgb, var(--ok) 35%, transparent); }
.toast-success .toast-icon { color: var(--ok); }

.toast-error { border-color: color-mix(in srgb, var(--danger) 38%, transparent); }
.toast-error .toast-icon { color: var(--danger); }

.toast-warning { border-color: color-mix(in srgb, var(--warn) 38%, transparent); }
.toast-warning .toast-icon { color: var(--warn); }

.toast-info .toast-icon { color: var(--accent); }

/* 重复触发同一提示时的轻微强调，表明"已再次生效" */
.toast.is-pulsing { animation: m-pop var(--dur-base) var(--ease-spring); }

/* 入场自下方滑入，离场淡出下移；仅使用 transform / opacity */
.toast-enter-active { transition: opacity var(--dur-base) var(--ease-out), transform var(--dur-base) var(--ease-out); }
.toast-leave-active { transition: opacity var(--dur-fast) ease, transform var(--dur-fast) ease; }
.toast-enter-from { opacity: 0; transform: translateY(12px) scale(0.98); }
.toast-leave-to { opacity: 0; transform: translateY(6px) scale(0.98); }
.toast-move { transition: transform var(--dur-base) var(--ease-out); }

@media (max-width: 640px) {
  .toast-host { bottom: 14px; width: calc(100vw - 20px); }
}
</style>
