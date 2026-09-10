/**
 * useFocusTrap — 弹层焦点管理
 * -----------------------------------------------------------------------------
 * 解决的问题：
 *   此前的弹层（题目列表抽屉、代码模板、快捷键帮助、排行榜）只做了视觉遮罩，
 *   键盘用户可以 Tab 到被遮罩的页面背景上，Esc 也无法关闭，关闭后焦点丢失。
 *
 * 本组合式负责四件事：
 *   1. 打开时把焦点移入弹层，并把 Tab / Shift+Tab 循环限制在弹层内部
 *   2. Esc 触发关闭回调
 *   3. 打开期间锁定页面滚动（并补偿滚动条宽度，避免页面横向跳动）
 *   4. 关闭时把焦点归还给打开弹层的触发元素
 *
 * 用法：
 *   const panelRef = ref(null)
 *   useFocusTrap(panelRef, () => props.open, { onEscape: () => emit('close') })
 *
 * 注意：keydown 以捕获阶段注册，以便在 CodeMirror 等内部已处理按键的组件之前生效。
 */
import { nextTick, onBeforeUnmount, watch } from 'vue'

// 可聚焦元素选择器；排除 tabindex="-1" 等显式不可参与 Tab 序列的元素
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  'audio[controls]',
  'video[controls]',
  '[contenteditable]:not([contenteditable="false"])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

/**
 * 模块级滚动锁计数：多个弹层叠加时，只有最后一个关闭才真正解锁，
 * 避免"关掉上层弹层导致下层弹层背景可滚动"的问题。
 */
let scrollLockCount = 0
let savedOverflow = ''
let savedPaddingRight = ''

function lockScroll() {
  if (scrollLockCount === 0) {
    const { style } = document.body
    savedOverflow = style.overflow
    savedPaddingRight = style.paddingRight
    // 锁定滚动会使滚动条消失，用等宽内边距补偿以免内容横向跳动
    const gap = window.innerWidth - document.documentElement.clientWidth
    style.overflow = 'hidden'
    if (gap > 0) style.paddingRight = `${gap}px`
  }
  scrollLockCount += 1
}

function unlockScroll() {
  scrollLockCount = Math.max(0, scrollLockCount - 1)
  if (scrollLockCount === 0) {
    document.body.style.overflow = savedOverflow
    document.body.style.paddingRight = savedPaddingRight
  }
}

/**
 * 收集容器内可见且可聚焦的元素。
 * 用 getClientRects() 判断可见性，可正确处理 position: fixed 元素
 * （这类元素的 offsetParent 恒为 null，用 offsetParent 判断会漏掉）。
 */
function getFocusable(container) {
  return Array.from(container.querySelectorAll(FOCUSABLE_SELECTOR))
    .filter((el) => el.getClientRects().length > 0)
}

export function useFocusTrap(panelRef, isActive, options = {}) {
  const { onEscape, restoreFocus = true } = options

  let previouslyFocused = null
  let locked = false

  function handleKeydown(event) {
    if (event.key === 'Escape') {
      if (onEscape) {
        event.preventDefault()
        event.stopPropagation()
        onEscape()
      }
      return
    }
    if (event.key !== 'Tab') return

    const panel = panelRef.value
    if (!panel) return

    const items = getFocusable(panel)
    if (items.length === 0) {
      // 无可聚焦项时把焦点留在弹层容器上，避免 Tab 逃逸到背景
      event.preventDefault()
      panel.focus()
      return
    }

    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement

    if (event.shiftKey) {
      if (active === first || !panel.contains(active)) {
        event.preventDefault()
        last.focus()
      }
    } else if (active === last || !panel.contains(active)) {
      event.preventDefault()
      first.focus()
    }
  }

  watch(isActive, async (active) => {
    if (active) {
      previouslyFocused = document.activeElement
      lockScroll()
      locked = true
      document.addEventListener('keydown', handleKeydown, true)
      // 等待弹层渲染完成后再移动焦点
      await nextTick()
      const panel = panelRef.value
      if (panel) {
        const items = getFocusable(panel)
        ;(items[0] ?? panel).focus()
      }
      return
    }

    if (!locked) return
    document.removeEventListener('keydown', handleKeydown, true)
    unlockScroll()
    locked = false

    // 焦点归还给触发元素，让键盘用户回到原位置继续操作
    if (restoreFocus && previouslyFocused instanceof HTMLElement && document.contains(previouslyFocused)) {
      previouslyFocused.focus()
    }
    previouslyFocused = null
  }, { immediate: true })

  onBeforeUnmount(() => {
    // 组件在打开状态下被卸载时，必须释放监听与滚动锁，否则页面将保持不可滚动
    document.removeEventListener('keydown', handleKeydown, true)
    if (locked) {
      unlockScroll()
      locked = false
    }
  })
}
