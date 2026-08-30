<template>
  <div>
    <div class="page-head">
      <h2>我的作业</h2>
      <p class="muted">所属班级已发布的作业与考试</p>
    </div>

    <div class="card asg-card" v-for="a in assignments" :key="a.targetId">
      <div class="row">
        <span class="chip" :class="a.mode === 'EXAM' ? 'chip-warn' : 'chip-primary'">
          {{ a.mode === 'EXAM' ? '正式考试' : '普通作业' }}
        </span>
        <div class="asg-info">
          <strong>{{ a.title }}</strong>
          <span class="muted">截止：{{ fmt(a.deadline) }}</span>
        </div>
        <div class="spacer"></div>
        <div class="asg-meta">
          <span class="muted attempts">已提交 {{ a.attemptCount }}/{{ a.maxSubmissions }} 次</span>
          <router-link :to="`/student/targets/${a.targetId}`">
            <button>进入作业 →</button>
          </router-link>
        </div>
      </div>
    </div>

    <div v-if="!loading && !assignments.length" class="empty">暂无已发布的作业</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api'

const assignments = ref([])
const loading = ref(true)

function fmt(s) {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}

onMounted(async () => {
  try {
    assignments.value = await api('/student/assignments')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.asg-card { padding: 18px 20px; }
.asg-info { display: flex; flex-direction: column; line-height: 1.45; }
.asg-info strong { font-size: 15px; }
.asg-info .muted { font-size: 13px; }
.asg-meta { display: flex; align-items: center; gap: 16px; }
.attempts { font-size: 13px; }
</style>
