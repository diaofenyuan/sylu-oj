/**
 * 图标注册（离线）
 * -----------------------------------------------------------------------------
 * 作用：
 *   把 src/icons/mdi-subset.json 中的图标子集注册进 @iconify/vue，
 *   使 `<Icon icon="mdi:xxx" />` 完全从本地数据渲染，
 *   不再向 https://api.iconify.design 发起运行时请求。
 *
 * 为什么不在运行时注册：
 *   @iconify/vue 默认按需向公共 CDN 拉取图标。校内网、内网或 CDN 受限环境下
 *   会导致全站图标丢失；同时首屏图标还受网络 RTT 影响。
 *
 * 维护方式：
 *   新增图标后执行 `npm --prefix app/web run icons` 重新生成子集文件。
 *   开发模式下若使用了未注册的图标，控制台会给出明确告警。
 */
import { addIcon } from '@iconify/vue'
import subset from './mdi-subset.json'

const { icons, width, height } = subset

for (const [name, data] of Object.entries(icons)) {
  addIcon(`mdi:${name}`, {
    ...data,
    // 补齐集合级默认尺寸，避免尺寸缺失导致图标渲染为 1em 意外大小
    width: data.width ?? width,
    height: data.height ?? height
  })
}

/**
 * 开发期守卫：检测页面上是否出现了未注册的图标名。
 * 未注册的图标会触发 Iconify 的 CDN 请求，在此显式提示以便及时补登记。
 */
if (import.meta.env.DEV) {
  const registered = new Set(Object.keys(icons).map((n) => `mdi:${n}`))

  // 拦截 Iconify 发出的图标数据请求，命中即说明存在未注册图标
  const originalFetch = window.fetch
  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : input?.url ?? ''
    if (url.includes('api.iconify.design') || url.includes('/mdi.json')) {
      console.warn(
        '[icons] 检测到未注册图标，正在回退到 Iconify CDN 请求：\n' +
        `  ${url}\n` +
        '  请执行 `npm --prefix app/web run icons` 重新生成图标子集。'
      )
    }
    return originalFetch.call(this, input, init)
  }

  // 暴露给调试使用
  window.__registeredIcons = registered
}
