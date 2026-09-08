<template>
  <div>
    <div class="page-head home-head">
      <div><h2>我的作业</h2><p class="muted">安排好每一次练习，循序渐进地解决问题。</p></div>
      <router-link class="button-link" to="/student/practice"><Icon icon="mdi:code-braces" aria-hidden="true" />开始刷题</router-link>
    </div>
    <dl class="assignment-overview" aria-label="作业概览">
      <div v-for="item in overview" :key="item.key"><dt>{{ item.label }}</dt><dd :class="item.key === 'OPEN' ? 'accent' : ''">{{ loading && !assignments.length ? '—' : counts[item.key] }}<span>份</span></dd></div>
    </dl>
    <div class="page-toolbar">
      <div class="filters" role="group" aria-label="筛选作业状态">
        <button v-for="item in filters" :key="item.key" :aria-pressed="filter === item.key" :class="{ active: filter === item.key }" @click="filter = item.key">{{ item.label }}<span>{{ item.key === 'ALL' ? assignments.length : counts[item.key] }}</span></button>
      </div>
      <label class="search-field"><span class="sr-only">搜索作业</span><Icon icon="mdi:magnify" aria-hidden="true" /><input v-model.trim="keyword" type="search" placeholder="搜索作业名称" /></label>
    </div>
    <div v-if="error" role="alert" class="error-banner"><span>{{ error }}</span><button class="secondary" :disabled="loading" @click="refresh">重新加载</button></div>
    <div class="assignment-list" :aria-busy="loading" aria-label="作业列表">
      <template v-if="loading && !assignments.length && !error"><div v-for="n in 3" :key="n" class="loading-row"><div class="skeleton"></div><div class="skeleton"></div></div><span role="status" class="sr-only">正在加载作业</span></template>
      <article v-for="a in filteredAssignments" :key="a.targetId" class="assignment-row">
        <div class="assignment-icon" :class="{ exam: a.mode === 'EXAM' }"><Icon :icon="a.mode === 'EXAM' ? 'mdi:clipboard-text-outline' : 'mdi:notebook-outline'" aria-hidden="true" /></div>
        <div class="asg-info">
          <div class="asg-labels"><span class="kind">{{ a.mode === 'EXAM' ? '正式考试' : '普通作业' }}</span><span class="chip" :class="winClass(a.window)">{{ winLabel(a.window) }}</span></div>
          <h3>{{ a.title }}</h3>
          <p class="asg-dates"><span>{{ a.window === 'NOT_STARTED' ? '开始' : '截止' }}时间</span><time>{{ fmt(a.window === 'NOT_STARTED' ? a.publishAt : a.deadline) }}</time></p>
        </div>
        <div class="asg-action"><span class="attempts">已提交 <strong>{{ a.attemptCount }}</strong> / {{ a.maxSubmissions }} 次</span>
          <button v-if="a.window === 'NOT_STARTED'" class="secondary" disabled>等待开始</button>
          <router-link v-else class="button-link" :class="{ secondary: a.window === 'CLOSED' }" :to="`/student/targets/${a.targetId}`">{{ a.window === 'CLOSED' ? '查看作业' : a.attemptCount > 0 ? '继续作答' : '开始作答' }}<Icon icon="mdi:arrow-right" aria-hidden="true" /></router-link>
        </div>
      </article>
    </div>
    <div v-if="!loading && !error && !filteredAssignments.length" class="empty">
      <Icon icon="mdi:notebook-outline" aria-hidden="true" /><h3>{{ assignments.length ? '没有找到匹配的作业' : '暂时没有待办作业' }}</h3>
      <p>{{ assignments.length ? '换个关键词，或查看全部作业。' : '老师发布后会显示在这里，也可以先去刷题中心练习。' }}</p>
      <button v-if="assignments.length" class="secondary" @click="filter = 'ALL'; keyword = ''">清除筛选</button>
      <router-link v-else class="button-link secondary" to="/student/practice">去刷题中心</router-link>
    </div>
    <div class="list-foot"><span role="status">共 {{ filteredAssignments.length }} 份作业 · 状态自动更新</span><button class="refresh-button" :disabled="loading" @click="refresh"><Icon icon="mdi:refresh" :class="{ 'spin-icon': loading }" aria-hidden="true" />{{ loading ? '刷新中' : '刷新' }}</button></div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useStudentAssignments } from '../../composables/useStudentAssignments'

const { assignments, loading, error, refresh } = useStudentAssignments()
const keyword = ref('')
const filter = ref('ALL')
const overview = [{ key: 'OPEN', label: '进行中' }, { key: 'NOT_STARTED', label: '未开始' }, { key: 'CLOSED', label: '已截止' }]
const filters = [{ key: 'ALL', label: '全部' }, ...overview]
const counts = computed(() => Object.fromEntries(overview.map(item => [item.key, assignments.value.filter(a => a.window === item.key).length])))
const filteredAssignments = computed(() => assignments.value
  .filter(a => (filter.value === 'ALL' || a.window === filter.value) && a.title.toLowerCase().includes(keyword.value.toLowerCase()))
  .slice().sort((a, b) => {
    // 开放状态以服务端为准；优先展示正在进行、较早截止的任务。
    const order = { OPEN: 0, NOT_STARTED: 1, CLOSED: 2 }
    return (order[a.window] ?? 3) - (order[b.window] ?? 3) || String(a.deadline || '9999').localeCompare(String(b.deadline || '9999'))
  }))
function fmt(value) { return value ? String(value).replace('T', ' ').slice(0, 16).replaceAll('-', '/') : '不限时' }
function winLabel(value) { return ({ NOT_STARTED: '未开始', OPEN: '进行中', CLOSED: '已截止' })[value] || value }
function winClass(value) { return ({ NOT_STARTED: 'chip-warn', OPEN: 'chip-ok', CLOSED: 'chip-muted' })[value] || 'chip-muted' }
</script>

<style scoped>
.home-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 20px; }
.assignment-overview { display: grid; grid-template-columns: repeat(3, 1fr); padding: 26px 0; background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius); margin: 0 0 32px; }
.assignment-overview > div { padding: 0 32px; border-right: 1px solid var(--border); }
.assignment-overview > div:last-child { border: 0; }
.assignment-overview dt { color: var(--muted); font-size: 13px; margin-bottom: 8px; }
.assignment-overview dd { margin: 0; font: 600 30px/1.2 "Segoe UI", sans-serif; font-variant-numeric: tabular-nums; }
.assignment-overview dd.accent { color: var(--accent); }
.assignment-overview dd span { font-size: 12px; color: var(--muted); margin-left: 8px; font-weight: 400; }
.filters { display: flex; gap: 4px; flex-wrap: wrap; }
.filters button { background: transparent; color: var(--muted); font-weight: 500; padding: 8px 12px; }
.filters button:hover { background: var(--panel-2); color: var(--text); }
.filters button.active { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.filters button span { font-size: 12px; font-variant-numeric: tabular-nums; }
.assignment-list { overflow: hidden; border-radius: var(--radius); background: var(--panel); }
.assignment-row { display: flex; align-items: center; gap: 20px; padding: 26px 28px; border: 1px solid var(--border); border-bottom: 0; }
.assignment-row:first-of-type { border-radius: var(--radius) var(--radius) 0 0; }
.assignment-row:last-of-type { border-bottom: 1px solid var(--border); border-radius: 0 0 var(--radius) var(--radius); }
.assignment-row:only-of-type { border-radius: var(--radius); }
.assignment-icon { width: 46px; height: 52px; flex-shrink: 0; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); border-radius: 12px; }
.assignment-icon.exam { color: var(--warn); background: var(--warn-soft); }
.assignment-icon svg { width: 23px; height: 23px; }
.asg-info { flex: 1; min-width: 0; }
.asg-labels { display: flex; align-items: center; gap: 10px; margin-bottom: 9px; }
.kind { font-size: 12px; color: var(--muted); }
.asg-info h3 { font-size: 16px; margin: 0 0 8px; }
.asg-dates { display: flex; flex-wrap: wrap; gap: 4px 12px; margin: 0; font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.asg-action { display: flex; align-items: center; gap: 24px; }
.attempts { color: var(--muted); font-size: 12px; white-space: nowrap; }
.attempts strong { color: var(--text); font-weight: 500; }
.asg-action > a, .asg-action > button { min-width: 118px; }
.list-foot { display: flex; align-items: center; justify-content: space-between; color: var(--muted); font-size: 12px; padding-top: 16px; }
.refresh-button { background: transparent; color: var(--muted); font-weight: 400; font-size: 12px; padding: 6px 10px; }
.refresh-button:hover { background: var(--panel-2); color: var(--text); }
.loading-row { padding: 30px; display: grid; gap: 14px; border: 1px solid var(--border); }
.loading-row .skeleton { height: 18px; width: 50%; }
.loading-row .skeleton:last-child { width: 32%; height: 12px; }
@media (max-width: 1000px) { .asg-action { flex-direction: column; align-items: flex-end; gap: 12px; } }
@media (max-width: 600px) {
  .home-head { align-items: flex-start; gap: 16px; }
  .assignment-overview { margin-bottom: 24px; padding: 20px 0; }
  .assignment-overview > div { padding: 0 18px; }
  .assignment-overview dd { font-size: 26px; }
  .filters { width: 100%; justify-content: space-between; }
  .filters button { padding: 8px 10px; gap: 6px; }
  .assignment-row { display: grid; grid-template-columns: 1fr; gap: 16px; padding: 22px 20px; }
  .assignment-icon { display: none; }
  .asg-action { flex-direction: row; justify-content: space-between; align-items: center; padding-top: 14px; border-top: 1px solid var(--border); }
  .asg-dates { font-size: 13px; }
}
</style>
