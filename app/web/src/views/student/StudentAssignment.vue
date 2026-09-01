<template>
  <ProblemWorkbench v-if="info" mode="assignment" :target-id="targetId" :title="info.title" :meta="meta" />
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
        deadline: info.value.deadline,
        attemptCount: info.value.attemptCount,
        maxSubmissions: info.value.maxSubmissions
      }
    }
  } finally {
    loading.value = false
  }
})
</script>
