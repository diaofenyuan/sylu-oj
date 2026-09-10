<template>
  <!--
    传送到 body：弹层可能位于带 transform 的页面过渡容器内，
    而 transform 会让 position: fixed 相对该容器定位，从而在过渡期间发生偏移。
  -->
  <Teleport to="body">
    <Transition :name="`overlay-${placement}`">
      <div
        v-if="open"
        class="overlay"
        :class="`overlay--${placement}`"
        @mousedown.self="onBackdrop"
      >
        <div
          ref="panelRef"
          class="overlay-panel"
          :class="panelClass"
          role="dialog"
          aria-modal="true"
          :aria-label="ariaLabel"
          tabindex="-1"
        >
          <slot />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
/**
 * AppOverlay — 弹层可访问性基座
 * -----------------------------------------------------------------------------
 * 统一提供：遮罩层、对话框语义、焦点陷阱、Esc 关闭、滚动锁定、焦点归还。
 * 业务组件只需关心面板内部的内容与样式，不再各自重复实现这些行为。
 *
 * 用法：
 *   <AppOverlay :open="visible" placement="center" aria-label="代码模板" @close="...">
 *     <div class="picker-content"> ... </div>
 *   </AppOverlay>
 *
 * placement：
 *   - center      居中对话框（模板选择、快捷键帮助、排行榜）
 *   - drawer-left 左侧抽屉（题目列表）
 */
import { ref } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'

const props = defineProps({
  open: { type: Boolean, default: false },
  placement: { type: String, default: 'center' }, // center | drawer-left
  ariaLabel: { type: String, default: '' },
  panelClass: { type: [String, Array, Object], default: '' },
  closeOnBackdrop: { type: Boolean, default: true },
  closeOnEsc: { type: Boolean, default: true }
})

const emit = defineEmits(['close'])

const panelRef = ref(null)

useFocusTrap(panelRef, () => props.open, {
  onEscape: () => { if (props.closeOnEsc) emit('close') }
})

function onBackdrop() {
  if (props.closeOnBackdrop) emit('close')
}
</script>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  display: flex;
}

.overlay--center {
  align-items: center;
  justify-content: center;
  padding: var(--space-5);
  background: rgba(15, 23, 42, 0.5);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.overlay--drawer-left {
  align-items: stretch;
  justify-content: flex-start;
  background: rgba(15, 23, 42, 0.45);
}

.overlay-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-width: 100%;
  max-height: 100%;
  outline: none;
}

.overlay--drawer-left .overlay-panel { height: 100%; }

/* ---- 过渡：遮罩淡入淡出，面板按方向做位移 ---- */
.overlay-center-enter-active,
.overlay-center-leave-active,
.overlay-drawer-left-enter-active,
.overlay-drawer-left-leave-active {
  transition: opacity var(--dur-base) var(--ease-out);
}
.overlay-center-enter-from,
.overlay-center-leave-to,
.overlay-drawer-left-enter-from,
.overlay-drawer-left-leave-to {
  opacity: 0;
}

.overlay-panel { transition: transform var(--dur-base) var(--ease-out), opacity var(--dur-base) var(--ease-out); }
.overlay-center-enter-from .overlay-panel,
.overlay-center-leave-to .overlay-panel {
  transform: scale(0.97);
  opacity: 0;
}
.overlay-drawer-left-enter-from .overlay-panel,
.overlay-drawer-left-leave-to .overlay-panel {
  transform: translateX(-24px);
  opacity: 0;
}
</style>
