import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'
import './styles/motion.css'
// 图标离线注册必须在应用挂载前完成，否则首屏图标会回退到 CDN 请求
import './icons'
import { Icon } from '@iconify/vue'
import { setupReveal } from './composables/useReveal'
import { toast } from './composables/useToast'
import { describeError } from './composables/useAsyncData'
import SkeletonList from './components/ui/SkeletonList.vue'
import SkeletonTable from './components/ui/SkeletonTable.vue'
import SkeletonStats from './components/ui/SkeletonStats.vue'
import SkeletonChart from './components/ui/SkeletonChart.vue'
import EmptyState from './components/ui/EmptyState.vue'
import ErrorState from './components/ui/ErrorState.vue'
import ToastHost from './components/ui/ToastHost.vue'
import AppOverlay from './components/ui/AppOverlay.vue'

const app = createApp(App)

// 全局基础组件
app.component('Icon', Icon)
app.component('SkeletonList', SkeletonList)
app.component('SkeletonTable', SkeletonTable)
app.component('SkeletonStats', SkeletonStats)
app.component('SkeletonChart', SkeletonChart)
app.component('EmptyState', EmptyState)
app.component('ErrorState', ErrorState)
app.component('ToastHost', ToastHost)
app.component('AppOverlay', AppOverlay)

/**
 * 全局异常兜底
 * 此前运行时错误只落在控制台，用户界面毫无反应。这里统一转为可见提示，
 * 避免"点击无响应"这类无从反馈的问题。
 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[app] 组件运行时异常:', err, info)
  toast.error('页面出现异常，请刷新后重试')
}

window.addEventListener('unhandledrejection', (event) => {
  console.error('[app] 未处理的异步异常:', event.reason)
  toast.error(describeError(event.reason))
})

// 全局指令：滚动入场揭示
setupReveal(app)

app.use(router)
app.mount('#app')
