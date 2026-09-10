<template>
  <div class="route-progress" :class="{ active: visible, done }" aria-hidden="true">
    <div class="bar" :style="{ width: `${percent}%` }"></div>
  </div>
</template>

<script setup>
/**
 * RouteProgress — 路由顶部加载进度条
 * -----------------------------------------------------------------------------
 * 在导航开始（含异步鉴权守卫）时启动，导航完成后收敛至 100% 并淡出。
 * 采用"预测式"进度：起始 8%，缓慢逼近 90%，结束后一次性补满，避免虚假停顿感。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const visible = ref(false)
const done = ref(false)
const percent = ref(0)

let pending = 0        // 并发导航计数，避免重定向守卫造成的过早收敛
let stepTimer = null  // 进度递增定时器
let hideTimer = null  // 淡出后重置定时器
let watchdog = null   // 兜底：导航异常未回调时强制收敛，防止进度条常驻

function clearTimers() {
  if (stepTimer) { clearInterval(stepTimer); stepTimer = null }
  if (hideTimer) { clearTimeout(hideTimer); hideTimer = null }
  if (watchdog) { clearTimeout(watchdog); watchdog = null }
}

function start() {
  pending += 1
  if (visible.value) return
  clearTimers()
  done.value = false
  visible.value = true
  percent.value = 8
  // 越接近 90% 步长越小，营造"仍在加载"的自然观感
  stepTimer = setInterval(() => {
    const remaining = 90 - percent.value
    if (remaining <= 0.5) return
    percent.value += Math.max(0.5, remaining * 0.12)
  }, 180)
  // 极端情况下（如守卫抛出未捕获异常）保证进度条最终消失
  watchdog = setTimeout(() => { pending = 0; finish() }, 8000)
}

function finish() {
  pending = Math.max(0, pending - 1)
  if (pending > 0) return
  clearTimers()
  if (!visible.value) return
  percent.value = 100
  done.value = true
  hideTimer = setTimeout(() => {
    visible.value = false
    // 等待淡出结束再复位宽度，避免下次启动出现回退动画
    hideTimer = setTimeout(() => { percent.value = 0; done.value = false }, 220)
  }, 220)
}

let offBefore = null
let offAfter = null
let offError = null

onMounted(() => {
  offBefore = router.beforeEach(() => { start(); return true })
  offAfter = router.afterEach(() => finish())
  offError = router.onError(() => finish())
})

onBeforeUnmount(() => {
  clearTimers()
  offBefore?.()
  offAfter?.()
  offError?.()
})
</script>
