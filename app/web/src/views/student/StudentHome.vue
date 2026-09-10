<template>
  <div>
    <div class="page-head" v-reveal>
      <div>
        <h2>我的作业</h2>
        <p class="muted">所属班级已发布的作业与考试</p>
      </div>
      <div class="spacer"></div>
      <router-link to="/student/contest/1">
        <button class="secondary contest-entry">
          <Icon icon="mdi:sword-cross" />
          比赛模式
        </button>
      </router-link>
    </div>

    <SkeletonList v-if="loading" :count="3" />

    <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />

    <template v-else>
      <div class="card asg-card" v-for="(a, i) in assignments" :key="a.targetId"
           v-reveal="{ delay: i * 70 }">
        <div class="row">
          <span class="chip" :class="a.mode === 'EXAM' ? 'chip-warn' : 'chip-primary'">
            {{ a.mode === 'EXAM' ? '正式考试' : '普通作业' }}
          </span>
          <span class="chip" :class="winClass(a.window)">{{ winLabel(a.window) }}</span>
          <div class="asg-info">
            <strong>{{ a.title }}</strong>
            <span class="muted">
              发布：{{ a.publishAt ? fmt(a.publishAt) : '不限' }}
              · 截止：{{ a.deadline ? fmt(a.deadline) : '不限' }}
            </span>
          </div>
          <div class="spacer"></div>
          <div class="asg-meta">
            <span class="muted attempts">已提交 {{ a.attemptCount }}/{{ a.maxSubmissions }} 次</span>
            <router-link :to="`/student/targets/${a.targetId}`">
              <button :disabled="a.window === 'NOT_STARTED'">
                {{ a.window === 'NOT_STARTED' ? '未开始' : '进入作业 →' }}
              </button>
            </router-link>
          </div>
        </div>
      </div>

      <EmptyState
        v-if="!assignments.length"
        icon="mdi:clipboard-text-outline"
        title="暂无已发布的作业"
        description="任课教师发布作业或考试后，会在这里列出" />
    </template>
  </div>
</template>

<script setup>
import { api } from '../../api'
import { useAsyncData } from '../../composables/useAsyncData'

// 加载失败时 error 有值，由 ErrorState 呈现并提供重试，避免与"暂无作业"混淆
const { data: assignments, loading, error, load } = useAsyncData(() => api('/student/assignments'))

function fmt(s) {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}
function winLabel(w) {
  return ({ NOT_STARTED: '未开始', OPEN: '进行中', CLOSED: '已截止' })[w] || w
}
function winClass(w) {
  return ({ NOT_STARTED: 'chip-warn', OPEN: 'chip-ok', CLOSED: 'chip-muted' })[w] || 'chip-muted'
}
</script>

<style scoped>
.asg-card { padding: 18px 20px; }
.asg-info { display: flex; flex-direction: column; line-height: 1.45; }
.asg-info strong { font-size: var(--fs-md); }
.asg-info .muted { font-size: var(--fs-sm); }
.asg-meta { display: flex; align-items: center; gap: var(--space-4); }
.attempts { font-size: var(--fs-sm); }
.page-head { display: flex; align-items: center; gap: var(--space-3); }
.contest-entry { display: inline-flex; align-items: center; gap: 6px; }
</style>
