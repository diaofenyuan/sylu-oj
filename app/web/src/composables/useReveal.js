/**
 * useReveal — 元素滚动入场揭示指令
 * -----------------------------------------------------------------------------
 * 用法：
 *   v-reveal                              // 默认上移淡入
 *   v-reveal="'left'"                     // 指定方向：up | down | left | right | zoom
 *   v-reveal="{ variant: 'up', delay: 120 }"   // 对象式，delay 单位 ms
 *   v-reveal="{ delay: i * 60 }"          // 在 v-for 中实现列表交错入场
 *
 * 实现要点：
 *   1. 基础类在 beforeMount 阶段写入，避免首次绘制时元素闪现
 *   2. 使用单个共享 IntersectionObserver 实例，元素进入视口后立即取消观察，零长驻开销
 *   3. 不支持 IntersectionObserver 或用户开启"减弱动效"时，直接显示元素，保证内容可达
 */

// 合法入场方向，非法值回退为 up
const VARIANTS = ['up', 'down', 'left', 'right', 'zoom']

function normalize(value) {
  if (!value) return { variant: 'up', delay: 0 }
  if (typeof value === 'string') {
    return { variant: VARIANTS.includes(value) ? value : 'up', delay: 0 }
  }
  return {
    variant: VARIANTS.includes(value.variant) ? value.variant : 'up',
    delay: Number(value.delay) || 0
  }
}

export function setupReveal(app) {
  const reduceMotion = typeof window !== 'undefined' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const supported = typeof IntersectionObserver !== 'undefined'

  // 共享观察器：进入视口约 8% 即触发，底部预留一定边距让动画更早发生
  const observer = (!reduceMotion && supported)
    ? new IntersectionObserver((entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-visible')
            observer.unobserve(entry.target)
          }
        }
      }, { threshold: 0.08, rootMargin: '0px 0px -6% 0px' })
    : null

  app.directive('reveal', {
    beforeMount(el, binding) {
      const { variant, delay } = normalize(binding.value)
      el.classList.add('reveal', `reveal-${variant}`)
      if (delay) el.style.setProperty('--reveal-delay', `${delay}ms`)
    },
    mounted(el) {
      if (!observer) {
        el.classList.add('is-visible')
        return
      }
      // 已在视口内的元素由观察器回调立即触发，形成自然的首屏入场
      observer.observe(el)
    },
    unmounted(el) {
      if (observer) observer.unobserve(el)
    }
  })
}
