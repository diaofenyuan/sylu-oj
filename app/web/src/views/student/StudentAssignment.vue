<template>
  <div v-if="info">
    <div class="page-head">
      <h2>{{ info.title }}</h2>
      <p class="muted">
        <span class="chip" :class="info.mode === 'EXAM' ? 'chip-warn' : 'chip-primary'">
          {{ info.mode === 'EXAM' ? '正式考试' : '普通作业' }}
        </span>
        截止：{{ fmt(info.deadline) }} · 已提交 {{ info.attemptCount }}/{{ info.maxSubmissions }} 次
      </p>
    </div>

    <div class="card" v-for="p in problems" :key="p.problemId">
      <div class="row prob-head">
        <h3>{{ p.title }}</h3>
        <span class="muted">{{ (p.languages || []).map(langName).join(' / ') }}</span>
      </div>
      <p class="desc">{{ p.description || '暂无题目描述' }}</p>

      <div v-if="p.samples && p.samples.length">
        <h4>公开样例</h4>
        <div class="sample" v-for="s in p.samples" :key="s.orderNum">
          <span class="io-label">输入</span>
          <pre>{{ s.input }}</pre>
        </div>
      </div>

      <div class="row submit-row">
        <select v-model="p._lang">
          <option v-for="l in p.languages" :key="l" :value="l">{{ langName(l) }}</option>
        </select>
        <span class="spacer"></span>
        <button :disabled="submitting || attemptsExhausted" @click="submit(p)">
          {{ submitting ? '提交中…' : '提交代码' }}
        </button>
      </div>
      <textarea v-model="p._code" rows="10" spellcheck="false" placeholder="在此编写或粘贴代码…"></textarea>

      <div v-if="feedback" class="ok-banner">{{ feedback }}</div>
    </div>

    <div v-if="!problems.length && !loading" class="empty">该作业暂无题目</div>

    <div class="card">
      <h3>我的提交</h3>
      <table v-if="submissions.length">
        <thead>
          <tr><th>次数</th><th>题目</th><th>语言</th><th>状态</th><th>提交时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in submissions" :key="s.submissionId">
            <td>#{{ s.attemptNo }}</td>
            <td>{{ problemTitle(s.problemId) }}</td>
            <td>{{ langName(s.language) }}</td>
            <td><span class="chip" :class="statusClass(s.judgeStatus)">{{ statusText(s.judgeStatus) }}</span></td>
            <td class="muted">{{ fmt(s.submittedAt) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">还没有提交记录</p>
    </div>

    <div class="card" v-if="analytics">
      <h3>我的成绩</h3>
      <div class="stats">
        <div class="stat"><span class="num">{{ analytics.totalScore ?? '-' }}</span><span class="muted">总分</span></div>
        <div class="stat"><span class="num">{{ analytics.passRate ?? '-' }}%</span><span class="muted">通过率</span></div>
        <div class="stat"><span class="num">{{ analytics.acProblems }}/{{ analytics.problemsTotal }}</span><span class="muted">AC 题数</span></div>
        <div class="stat"><span class="num">{{ analytics.submissionCount }}</span><span class="muted">提交次数</span></div>
        <div class="stat"><span class="num">No.{{ analytics.rank ?? '-' }}</span><span class="muted">班级排名</span></div>
      </div>
    </div>
  </div>
  <div v-else class="empty">{{ loading ? '加载中…' : '作业不存在或不可访问' }}</div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'

const route = useRoute()
const targetId = route.params.targetId

const info = ref(null)
const problems = ref([])
const submissions = ref([])
const analytics = ref(null)
const loading = ref(true)
const submitting = ref(false)
const feedback = ref('')
let pollTimer = null

const LANG_NAMES = { C: 'C', CPP: 'C++', PYTHON: 'Python', JAVA: 'Java' }
const STATUS_NAMES = { PD: '排队中', AC: '通过', WA: '答案错误', TLE: '超时', MLE: '超内存', RE: '运行错误', CE: '编译错误' }

function langName(l) {
  return LANG_NAMES[l] || l
}
function statusText(s) {
  return STATUS_NAMES[s] || s
}
function statusClass(s) {
  if (s === 'AC') return 'chip-ok'
  if (s === 'PD') return 'chip-muted'
  if (['WA', 'RE', 'CE', 'TLE', 'MLE'].includes(s)) return 'chip-bad'
  return 'chip-warn'
}
function fmt(s) {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}
function problemTitle(problemId) {
  const p = problems.value.find(x => x.problemId === problemId)
  return p ? p.title : `#${problemId}`
}

const attemptsExhausted = computed(() =>
  info.value && info.value.maxSubmissions > 0 && info.value.attemptCount >= info.value.maxSubmissions
)

async function loadMeta() {
  const list = await api('/student/assignments')
  info.value = list.find(a => String(a.targetId) === String(targetId)) || null
}

async function loadSubmissions() {
  submissions.value = await api(`/student/submissions?assignmentTargetId=${targetId}`)
  if (info.value) {
    info.value.attemptCount = submissions.value.length
  }
  if (submissions.value.some(s => s.judgeStatus === 'PD')) {
    pollTimer = setTimeout(loadSubmissions, 2500)
  }
}

async function loadAll() {
  try {
    await loadMeta()
    if (info.value) {
      problems.value = (await api(`/student/targets/${targetId}/problems`))
        .map(p => ({ ...p, _lang: (p.languages && p.languages[0]) || '', _code: '' }))
      await Promise.all([loadSubmissions(), loadAnalytics()])
    }
  } finally {
    loading.value = false
  }
}

async function loadAnalytics() {
  try {
    analytics.value = await api(`/student/analytics/targets/${targetId}`)
  } catch {
    analytics.value = null
  }
}

async function submit(p) {
  if (!p._code.trim()) return
  submitting.value = true
  feedback.value = ''
  try {
    await api('/student/submissions', {
      method: 'POST',
      body: {
        assignmentTargetId: Number(targetId),
        problemId: p.problemId,
        language: p._lang,
        code: p._code,
        idempotencyKey: crypto.randomUUID()
      }
    })
    feedback.value = '已提交，正在评测…'
    await loadSubmissions()
    await loadAnalytics()
  } catch (e) {
    feedback.value = '提交失败：' + e.message
  } finally {
    submitting.value = false
  }
}

onMounted(loadAll)
onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<style scoped>
.page-head p { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.prob-head { margin-bottom: 8px; }
.prob-head h3 { margin: 0; }
.desc { color: var(--text); line-height: 1.8; margin: 0 0 14px; white-space: pre-wrap; }
h4 { margin: 14px 0 8px; font-size: 13.5px; color: var(--muted); }
.sample {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.io-label {
  flex: none;
  font-size: 12px;
  color: var(--muted);
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 1px 8px;
  margin-top: 2px;
}
.sample pre { margin: 0; font-size: 13px; white-space: pre-wrap; word-break: break-all; }
.submit-row { margin: 14px 0 10px; }
.submit-row select { min-width: 120px; }
textarea {
  width: 100%;
  font-family: Consolas, Monaco, "Courier New", monospace;
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
}
.ok-banner { margin-top: 12px; }
.stats { display: flex; gap: 12px; flex-wrap: wrap; }
.stat {
  flex: 1;
  min-width: 100px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px 10px;
}
.stat .num { font-size: 19px; font-weight: 700; }
.stat .muted { font-size: 12px; }
</style>
