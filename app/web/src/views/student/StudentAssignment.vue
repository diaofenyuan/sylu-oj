<template>
  <ProblemWorkbench v-if="info && info.window !== 'NOT_STARTED'" mode="assignment"
                    :target-id="targetId" :title="info.title" :meta="meta" />
  <div v-else-if="info" class="empty">
    作业尚未开始（{{ info.publishAt ? fmt(info.publishAt) : '' }} 发布），到点后自动开放
  </div>
  <div v-else class="empty">{{ loading ? '加载中…' : '作业不存在或不可访问' }}</div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'
import ProblemWorkbench from '../../components/ProblemWorkbench.vue'

const route = useRoute()
const targetId = route.params.targetId

const info = ref(null)
const meta = ref(null)
const loading = ref(true)

onMounted(async () => {
  try {
    const list = await api('/student/assignments')
    info.value = list.find(a => String(a.targetId) === String(targetId)) || null
    if (info.value) {
      meta.value = {
        mode: info.value.mode,
        publishAt: info.value.publishAt,
        deadline: info.value.deadline,
        window: info.value.window,
        attemptCount: info.value.attemptCount,
        maxSubmissions: info.value.maxSubmissions
      }
    }
  } finally {
    loading.value = false
  }
})

function fmt(s) {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}
</script>
