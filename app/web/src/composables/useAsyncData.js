/**
 * useAsyncData — 页面数据加载的统一样板
 * -----------------------------------------------------------------------------
 * 解决的问题：
 *   此前各视图在 onMounted 中写 `try { data = await api(...) } finally { loading = false }`，
 *   没有 catch。请求失败时错误被静默吞掉，loading 归位后页面落入"暂无数据"空态 ——
 *   用户会误以为确实没有数据（考试场景下危害明显）。
 *
 * 本组合式把「加载中 / 成功 / 失败」三态显式化，视图据此渲染
 * SkeletonList / 内容 / ErrorState，语义不再混淆。
 *
 * 用法：
 *   const { data: assignments, loading, error, load } =
 *     useAsyncData(() => api('/student/assignments'))
 *
 *   <SkeletonList v-if="loading" />
 *   <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />
 *   <template v-else> ... </template>
 */
import { ref } from 'vue'

/**
 * 把底层异常转成面向用户的说明。
 * fetch 在网络中断时抛出 TypeError，直接展示 "Failed to fetch" 对用户没有意义。
 */
export function describeError(e) {
  if (!e) return '未知错误'
  if (e instanceof TypeError || /Failed to fetch|NetworkError|Load failed/i.test(e.message || '')) {
    return '网络连接中断，请检查网络后重试'
  }
  if (/401|未认证|登录已过期/.test(e.message || '')) {
    return '登录状态已失效，请重新登录'
  }
  return e.message || '请求失败'
}

export function useAsyncData(loader, options = {}) {
  const data = ref(options.initial ?? null)
  const loading = ref(true)
  const error = ref('')

  async function load() {
    loading.value = true
    // 重试前清空错误，使 ErrorState 在重新加载期间让位于 Skeleton
    error.value = ''
    try {
      data.value = await loader()
    } catch (e) {
      error.value = describeError(e)
    } finally {
      loading.value = false
    }
  }

  if (options.immediate !== false) load()

  return { data, loading, error, load }
}
