<template>
  <div class="practice-page">
    <div class="page-head practice-head">
      <div>
        <h2>刷题中心</h2>
        <p class="muted">从入门到困难，完成 100 道编程题，提交后由安全沙盒自动评测</p>
      </div>
      <div class="progress-summary">
        <strong>{{ passedCount }}<span>/{{ problems.length }}</span></strong>
        <span class="muted">已通过</span>
      </div>
    </div>

    <div class="progress-track" aria-label="刷题总进度">
      <span :style="{ width: `${progressPercent}%` }"></span>
    </div>

    <div class="level-strip">
      <button v-for="level in levels" :key="level.key" class="level-tab"
              :class="{ active: difficulty === level.key }" @click="difficulty = level.key">
        <span class="level-dot" :class="`dot-${level.key.toLowerCase()}`"></span>
        <span>{{ level.label }}</span>
        <small>{{ levelPassed(level.key) }}/{{ levelCount(level.key) }}</small>
      </button>
      <button class="level-tab all-tab" :class="{ active: difficulty === '' }" @click="difficulty = ''">
        <span>全部题目</span><small>{{ passedCount }}/{{ problems.length }}</small>
      </button>
    </div>

    <div class="workspace" :class="{ 'has-problem': !!selected }">
      <aside class="problem-list panel">
        <div class="list-toolbar">
          <input v-model.trim="keyword" placeholder="搜索题目" aria-label="搜索题目" />
          <select v-model="statusFilter" aria-label="筛选完成状态">
            <option value="ALL">全部状态</option>
            <option value="UNATTEMPTED">未开始</option>
            <option value="ATTEMPTED">已尝试</option>
            <option value="AC">已通过</option>
          </select>
        </div>
        <div v-if="loading" class="empty list-empty">题目加载中…</div>
        <div v-else-if="!filteredProblems.length" class="empty list-empty">没有匹配的题目</div>
        <button v-for="problem in filteredProblems" :key="problem.problemId"
                class="problem-row" :class="{ selected: selectedId === problem.problemId }"
                @click="selectProblem(problem.problemId)">
          <span class="problem-no">{{ problem.code.replace('PRACTICE-', '').replaceAll('-', ' ') }}</span>
          <span class="problem-title">{{ problem.title }}</span>
          <span class="problem-state" :class="stateClass(problem.status)">{{ stateText(problem.status) }}</span>
        </button>
      </aside>

      <section class="editor panel" v-if="selected">
        <div class="editor-head">
          <div>
            <div class="eyebrow">{{ difficultyLabel(selected.difficulty) }}</div>
            <h3>{{ selected.title }}</h3>
          </div>
          <span class="chip" :class="stateClass(selected.status)">{{ stateText(selected.status) }}</span>
        </div>
        <div class="problem-description">{{ selected.description }}</div>

        <div v-if="selected.samples?.length" class="samples">
          <div v-for="sample in selected.samples" :key="sample.orderNum" class="sample-box">
            <div class="sample-label">样例 {{ sample.orderNum }}</div>
            <div class="sample-io"><span>输入</span><pre>{{ sample.input }}</pre></div>
            <div class="sample-io"><span>输出</span><pre>{{ sample.expectedOutput }}</pre></div>
          </div>
        </div>

        <div class="code-toolbar">
          <select v-model="language" aria-label="选择编程语言">
            <option v-for="item in selected.languages" :key="item" :value="item">{{ langName(item) }}</option>
          </select>
          <span class="muted">提交将进入隔离沙盒评测</span>
          <span class="spacer"></span>
          <button @click="submit" :disabled="submitting || !code.trim()">
            {{ submitting ? '评测中…' : '提交代码' }}
          </button>
        </div>
        <textarea v-model="code" class="code-editor" rows="16" spellcheck="false"
                  placeholder="在此编写代码…"></textarea>
        <div v-if="feedback" class="feedback" :class="feedbackClass">{{ feedback }}</div>
        <div v-if="selected.bestScore > 0" class="best-score">
          当前最佳得分 <strong>{{ selected.bestScore }}</strong> / 100
        </div>
      </section>

      <section v-else class="editor panel empty-detail">
        <div class="empty-icon">⌘</div>
        <h3>选择一道题开始练习</h3>
        <p class="muted">题目按难度分级，提交代码后会由现有判题沙盒执行全部隐藏用例。</p>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../../api'

const levels = [
  { key: 'EASY', label: '入门' },
  { key: 'BASIC', label: '基础' },
  { key: 'INTERMEDIATE', label: '进阶' },
  { key: 'HARD', label: '困难' }
]
const problems = ref([])
const selected = ref(null)
const selectedId = ref(null)
const difficulty = ref('')
const keyword = ref('')
const statusFilter = ref('ALL')
const loading = ref(true)
const submitting = ref(false)
const feedback = ref('')
const feedbackClass = ref('')
const language = ref('CPP')
const code = ref('')
let pollTimer = null

const filteredProblems = computed(() => problems.value.filter(problem => {
  const matchesDifficulty = !difficulty.value || problem.difficulty === difficulty.value
  const matchesKeyword = !keyword.value || `${problem.title} ${problem.description}`.toLowerCase().includes(keyword.value.toLowerCase())
  const matchesStatus = statusFilter.value === 'ALL'
    || (statusFilter.value === 'AC' && problem.status === 'AC')
    || (statusFilter.value === 'ATTEMPTED' && problem.status !== 'UNATTEMPTED')
    || (statusFilter.value === 'UNATTEMPTED' && problem.status === 'UNATTEMPTED')
  return matchesDifficulty && matchesKeyword && matchesStatus
}))
const passedCount = computed(() => problems.value.filter(problem => problem.status === 'AC').length)
const progressPercent = computed(() => problems.value.length ? Math.round(passedCount.value / problems.value.length * 100) : 0)

function difficultyLabel(key) { return levels.find(level => level.key === key)?.label || key }
function levelCount(key) { return problems.value.filter(problem => problem.difficulty === key).length }
function levelPassed(key) { return problems.value.filter(problem => problem.difficulty === key && problem.status === 'AC').length }
function stateText(status) { return ({ AC: '已通过', PD: '评测中', UNATTEMPTED: '未开始', WA: '未通过', CE: '编译错误', TLE: '超时', MLE: '内存超限', OLE: '输出超限', PE: '格式错误', RE: '运行错误', SE: '评测服务异常', BSC: '沙盒拦截' })[status] || status }
function stateClass(status) { return ({ AC: 'chip-ok', PD: 'chip-warn', UNATTEMPTED: 'chip-muted', WA: 'chip-bad', CE: 'chip-bad', TLE: 'chip-bad', MLE: 'chip-bad', OLE: 'chip-bad', PE: 'chip-bad', RE: 'chip-bad', SE: 'chip-bad', BSC: 'chip-bad' })[status] || 'chip-muted' }
function langName(value) { return ({ C: 'C', CPP: 'C++', PYTHON: 'Python', JAVA: 'Java' })[value] || value }
function draftKey(id) { return `oj-practice-draft-${id}` }

async function loadProblems() {
  loading.value = true
  try {
    problems.value = await api('/student/practice/problems')
    if (!selectedId.value && problems.value.length) await selectProblem(problems.value[0].problemId)
  } finally {
    loading.value = false
  }
}

async function selectProblem(problemId) {
  selectedId.value = problemId
  feedback.value = ''
  selected.value = await api(`/student/practice/problems/${problemId}`)
  language.value = selected.value.languages?.[0] || 'CPP'
  code.value = localStorage.getItem(draftKey(problemId)) || ''
  await refreshSubmissionStatus()
}

async function refreshSubmissionStatus() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  if (!selected.value) return
  const problemId = selected.value.problemId
  const submissions = await api(`/student/submissions?assignmentTargetId=${selected.value.assignmentTargetId}&problemId=${problemId}`)
  // 切题期间忽略旧请求返回，避免覆盖新题目的状态。
  if (!selected.value || selected.value.problemId !== problemId) return
  const latest = submissions.at(-1)
  if (latest && latest.judgeStatus !== 'PD') {
    selected.value.status = latest.judgeStatus
    if (latest.judgeStatus === 'AC') {
      selected.value.bestScore = 100
      const item = problems.value.find(problem => problem.problemId === selected.value.problemId)
      if (item) { item.status = 'AC'; item.bestScore = 100 }
    }
  }
  if (latest?.judgeStatus === 'PD') {
    pollTimer = setTimeout(() => {
      pollTimer = null
      refreshSubmissionStatus()
    }, 2200)
  }
}

async function submit() {
  if (!selected.value || !code.value.trim()) return
  localStorage.setItem(draftKey(selected.value.problemId), code.value)
  submitting.value = true
  feedback.value = ''
  try {
    await api('/student/submissions', {
      method: 'POST',
      body: {
        assignmentTargetId: selected.value.assignmentTargetId,
        problemId: selected.value.problemId,
        language: language.value,
        code: code.value,
        idempotencyKey: crypto.randomUUID()
      }
    })
    feedback.value = '代码已送入安全沙盒，正在评测隐藏用例…'
    feedbackClass.value = 'feedback-pending'
    selected.value.status = 'PD'
    const item = problems.value.find(problem => problem.problemId === selected.value.problemId)
    if (item) item.status = 'PD'
    await refreshSubmissionStatus()
  } catch (error) {
    feedback.value = `提交失败：${error.message}`
    feedbackClass.value = 'feedback-error'
  } finally {
    submitting.value = false
  }
}

watch(code, value => {
  if (selected.value && value) localStorage.setItem(draftKey(selected.value.problemId), value)
})
watch(difficulty, async value => {
  // 难度切换只在前端筛选，避免重复创建目录或产生请求瀑布。
  if (value && selected.value && selected.value.difficulty !== value) {
    const next = problems.value.find(problem => problem.difficulty === value)
    if (next) await selectProblem(next.problemId)
  }
})

onMounted(loadProblems)
onUnmounted(() => { if (pollTimer) clearTimeout(pollTimer) })
</script>

<style scoped>
.practice-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
.practice-head h2 { margin-bottom: 4px; }
.practice-head p { margin: 0; }
.progress-summary { display: flex; flex-direction: column; align-items: flex-end; line-height: 1.2; }
.progress-summary strong { font-size: 26px; color: var(--accent); }
.progress-summary strong span { color: var(--muted); font-size: 15px; font-weight: 500; }
.progress-summary .muted { font-size: 12px; margin-top: 4px; }
.progress-track { height: 7px; border-radius: 999px; background: #e8edf6; overflow: hidden; margin: -8px 0 22px; }
.progress-track span { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #2563eb, #0ea5e9); transition: width .25s ease; }
.level-strip { display: flex; gap: 8px; margin-bottom: 18px; overflow-x: auto; padding-bottom: 2px; }
.level-tab { flex: 1; min-width: 136px; border: 1px solid var(--border); background: var(--panel); color: var(--muted); box-shadow: none; padding: 11px 13px; justify-content: flex-start; border-radius: 10px; }
.level-tab:hover { background: var(--panel-2); border-color: var(--border-strong); box-shadow: none; }
.level-tab.active { background: var(--accent-soft); border-color: #bfd4ff; color: var(--accent-strong); }
.level-tab small { margin-left: auto; color: inherit; font-size: 12px; }
.level-dot { width: 8px; height: 8px; border-radius: 50%; background: #94a3b8; }
.dot-easy { background: #22c55e; }.dot-basic { background: #0ea5e9; }.dot-intermediate { background: #f59e0b; }.dot-hard { background: #ef4444; }
.all-tab { flex: 0 0 auto; min-width: 110px; }
.workspace { display: grid; grid-template-columns: 330px minmax(0, 1fr); gap: 16px; min-height: 650px; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm); }
.problem-list { overflow: hidden; display: flex; flex-direction: column; }
.list-toolbar { padding: 13px; display: flex; gap: 8px; border-bottom: 1px solid var(--border); background: var(--panel-2); }
.list-toolbar input { min-width: 0; flex: 1; width: 0; padding: 8px 10px; }
.list-toolbar select { width: 100px; padding: 8px 7px; font-size: 12px; }
.problem-row { width: 100%; display: grid; grid-template-columns: 64px minmax(0, 1fr) auto; gap: 8px; text-align: left; justify-content: initial; background: transparent; color: var(--text); border: 0; border-bottom: 1px solid var(--border); border-radius: 0; box-shadow: none; padding: 12px 13px; font-weight: 500; }
.problem-row:hover { background: var(--panel-2); box-shadow: none; }
.problem-row.selected { background: var(--accent-soft); box-shadow: inset 3px 0 var(--accent); }
.problem-no { color: var(--muted); font-size: 11px; text-transform: uppercase; }
.problem-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.problem-state { font-size: 11px; color: var(--muted); white-space: nowrap; }
.problem-state.chip-ok { color: var(--ok); }.problem-state.chip-warn { color: var(--warn); }.problem-state.chip-bad { color: var(--danger); }
.list-empty { margin: 15px; padding: 25px 10px; }
.editor { padding: 23px; min-width: 0; }
.editor-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--border); padding-bottom: 15px; }
.editor-head h3 { margin: 2px 0 0; font-size: 20px; }
.eyebrow { color: var(--accent); font-size: 12px; font-weight: 700; }
.problem-description { white-space: pre-wrap; line-height: 1.8; margin: 18px 0; }
.samples { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; margin-bottom: 18px; }
.sample-box { border: 1px solid var(--border); border-radius: 9px; background: var(--panel-2); padding: 11px 12px; }
.sample-label { font-size: 12px; font-weight: 700; color: var(--muted); margin-bottom: 8px; }
.sample-io { display: grid; grid-template-columns: 30px 1fr; gap: 7px; margin-top: 5px; }
.sample-io span { font-size: 11px; color: var(--muted); }.sample-io pre { margin: 0; font: 12px/1.5 Consolas, monospace; white-space: pre-wrap; }
.code-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 9px; }
.code-toolbar select { min-width: 115px; }.code-toolbar .muted { font-size: 12px; }
.code-editor { width: 100%; min-height: 300px; resize: vertical; font: 13px/1.65 Consolas, Monaco, monospace; background: #0f172a; color: #dbeafe; border-color: #1e293b; border-radius: 9px; padding: 15px; }
.code-editor:focus { border-color: #60a5fa; box-shadow: 0 0 0 3px rgba(37, 99, 235, .18); }
.feedback { margin-top: 12px; border-radius: 8px; padding: 10px 12px; font-size: 13px; }
.feedback-pending { color: var(--accent); background: var(--accent-soft); }.feedback-error { color: var(--danger); background: var(--danger-soft); }
.best-score { margin-top: 12px; font-size: 13px; color: var(--muted); }.best-score strong { color: var(--ok); font-size: 16px; margin-left: 5px; }
.empty-detail { display: grid; place-items: center; align-content: center; text-align: center; padding: 40px; }.empty-detail h3 { margin: 10px 0 4px; }.empty-detail p { max-width: 360px; }.empty-icon { color: var(--accent); font-size: 30px; }
@media (max-width: 860px) { .workspace { grid-template-columns: 1fr; }.problem-list { max-height: 330px; }.editor { min-height: 560px; } }
@media (max-width: 560px) { .practice-head { align-items: flex-start; }.progress-summary { display: none; }.editor { padding: 16px; }.code-toolbar { flex-wrap: wrap; }.code-toolbar .spacer { display: none; }.code-toolbar button { margin-left: auto; }.level-tab { min-width: 118px; } }
</style>
