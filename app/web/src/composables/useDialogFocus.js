import { nextTick, onBeforeUnmount, watch } from 'vue'

export function useDialogFocus(visible, panel, close) {
  let returnFocus = null
  const controls = () => [...(panel.value?.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]') || [])].filter(el => el.getClientRects().length)

  function onKeydown(event) {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); return }
    if (event.key !== 'Tab') return
    const items = controls()
    const first = items[0]
    const last = items.at(-1)
    if (!first) { event.preventDefault(); panel.value?.focus(); return }
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) {
      event.preventDefault(); last.focus()
    } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.value)) {
      event.preventDefault(); first.focus()
    }
  }

  function cleanup() {
    document.removeEventListener('keydown', onKeydown, true)
    if (returnFocus?.isConnected) returnFocus.focus()
    returnFocus = null
  }

  watch(visible, async (open, _, onCleanup) => {
    if (!open) { cleanup(); return }
    let active = true
    onCleanup(() => { active = false })
    returnFocus = document.activeElement
    await nextTick()
    if (!active || !panel.value) return
    // 打开时把焦点带入弹层，关闭后送回触发按钮，键盘操作不会落到遮罩后方。
    panel.value.focus()
    document.addEventListener('keydown', onKeydown, true)
  }, { immediate: true })
  onBeforeUnmount(cleanup)
}
