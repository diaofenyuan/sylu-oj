<template>
  <div v-if="error" class="assignment-notice" role="alert">
    {{ error }} <button class="secondary" :disabled="loading" @click="refresh">重试加载</button>
  </div>
  <ProblemWorkbench v-if="info && info.window !== 'NOT_STARTED'" mode="assignment"
                    :key="targetId" :target-id="targetId" :title="info.title" :meta="info" />
  <div v-else-if="info" class="empty">
    作业尚未开始（{{ info.publishAt ? fmt(info.publishAt) : '' }} 发布），页面将自动更新开放状态。
    <button class="secondary" :disabled="loading" @click="refresh">刷新状态</button>
  </div>
  <div v-else-if="!error" class="empty">{{ loading ? '加载中…' : '作业不存在或不可访问' }}</div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useStudentAssignments } from '../../composables/useStudentAssignments'
import ProblemWorkbench from '../../components/ProblemWorkbench.vue'

const route = useRoute()
const targetId = computed(() => route.params.targetId)
const { assignments, loading, error, refresh } = useStudentAssignments()
const info = computed(() => assignments.value.find(a => String(a.targetId) === String(targetId.value)) ?? null)
watch(targetId, refresh)

function fmt(s) {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}
</script>

<style scoped>
.assignment-notice { padding: 12px 16px; color: var(--danger); }
</style>
