/**
 * useToast — 全局消息提示
 * -----------------------------------------------------------------------------
 * 模块级单例：任意组件调用 `useToast()` 得到的都是同一个队列，
 * 由 `<ToastHost>`（在 App.vue 中挂载一次）统一渲染。
 *
 * 用法：
 *   const toast = useToast()
 *   toast.success('已保存')
 *   toast.error(e.message)
 *   toast.info('审批已提交，等待第二位教师批准')
 *
 * 设计约定：
 *   - 错误提示停留时间更长（6s），普通提示 4s，避免用户错过失败信息
 *   - 相同内容在短时间内重复触发时只刷新计时，不叠加重复气泡
 *   - 错误类型通过 aria-live="assertive" 由读屏软件立即播报
 *
 * 提示放置原则（避免内联与 Toast 混用导致体验不一致）：
 *   - 有「就近错误位」的表单（如登录卡片下方的错误插槽）→ 内联展示，
 *     保持用户输入上下文不被瞬时消息打断；
 *   - 无就近错误位的紧凑操作（工具栏按钮、表格行操作、页面级加载失败）
 *     → 使用全局 Toast，不占用页面布局。
 */
import { reactive } from 'vue'

export const TOAST_TYPES = ['success', 'error', 'warning', 'info']

// 各类型默认停留时长（毫秒）
const DEFAULT_DURATION = {
  success: 4000,
  info: 4000,
  warning: 5000,
  error: 6000
}

const MAX_VISIBLE = 4

let seq = 0

/** 全局提示队列（供 ToastHost 渲染，不直接在业务组件中读写） */
export const toasts = reactive([])

function remove(id) {
  const index = toasts.findIndex((t) => t.id === id)
  if (index !== -1) toasts.splice(index, 1)
}

function scheduleRemoval(item, duration) {
  if (item.timer) clearTimeout(item.timer)
  if (duration <= 0) return
  item.timer = setTimeout(() => remove(item.id), duration)
}

function push(message, type = 'info', options = {}) {
  const text = String(message ?? '').trim()
  if (!text) return null

  const safeType = TOAST_TYPES.includes(type) ? type : 'info'
  const duration = options.duration ?? DEFAULT_DURATION[safeType]

  // 去重：同类型同内容且仍在展示中 → 仅重新计时，并做一个轻微强调
  const existing = toasts.find((t) => t.message === text && t.type === safeType)
  if (existing) {
    existing.pulse += 1
    scheduleRemoval(existing, duration)
    return existing.id
  }

  const item = {
    id: ++seq,
    message: text,
    type: safeType,
    pulse: 0,
    timer: null
  }
  toasts.push(item)

  // 超出上限时移除最早的一条，避免遮挡页面
  while (toasts.length > MAX_VISIBLE) {
    const oldest = toasts[0]
    if (oldest.timer) clearTimeout(oldest.timer)
    toasts.shift()
  }

  scheduleRemoval(item, duration)
  return item.id
}

export function useToast() {
  return {
    show: push,
    success: (message, options) => push(message, 'success', options),
    error: (message, options) => push(message, 'error', options),
    warning: (message, options) => push(message, 'warning', options),
    info: (message, options) => push(message, 'info', options),
    dismiss: remove,
    clear: () => {
      for (const t of toasts) if (t.timer) clearTimeout(t.timer)
      toasts.splice(0, toasts.length)
    }
  }
}

// 供 App 级错误处理直接调用，无需先创建组合式实例
export const toast = useToast()
