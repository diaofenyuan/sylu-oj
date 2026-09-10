<template>
  <div v-if="loading" class="page-loader" role="status" aria-busy="true">
    <span class="spinner spinner-lg" aria-hidden="true"></span>
    <p class="muted">正在加载作业…</p>
  </div>

  <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />

  <ProblemWorkbench v-else-if="info && info.window !== 'NOT_STARTED'" mode="assignment"
                    :target-id="targetId" :title="info.title" :meta="meta" />

  <EmptyState v-else-if="info" icon="mdi:clock-outline" tone="warn"
              title="作业尚未开始"
              :description="`将于 ${info.publishAt ? fmt(info.publishAt) : '指定时间'} 发布，到点后自动开放`" />

  <EmptyState v-else icon="mdi:link-variant-off"
              title="作业不存在或不可访问"
              description="请确认作业已发布，且你已加入对应教学班" />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'
import { useAsyncData } from '../../composables/useAsyncData'
import ProblemWorkbench from '../../components/ProblemWorkbench.vue'

const route = useRoute()
const targetId = route.params.targetId

// 先在作业列表中定位，再抽取元信息；整体作为一次加载，三态由此统一管理
const { data, loading, error, load } = useAsyncData(async () => {
  const list = await api('/student/assignments')
  const found = list.find((a) => String(a.targetId) === String(targetId)) || null
  if (!found) return { info: null, meta: null }
  return {
    info: found,
    meta: {
      mode: found.mode,
      publishAt: found.publishAt,
      deadline: found.deadline,
      window: found.window,
      attemptCount: found.attemptCount,
      maxSubmissions: found.maxSubmissions
    }
  }
})

const info = computed(() => data.value?.info ?? null)
const meta = computed(() => data.value?.meta ?? null)

function fmt(s) {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}
</script>

<style scoped>
.page-loader {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  padding: 80px 20px;
  color: var(--accent);
  animation: m-fade-in var(--dur-base) var(--ease-out) both;
}
.page-loader p { margin: 0; }
</style>
