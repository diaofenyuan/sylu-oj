<template>
  <div>
    <div class="page-head">
      <h2>组卷与发布</h2>
      <p class="muted">从题库勾选题目、分配权重，选择班级一键发布（权重之和须恰为 100）</p>
    </div>

    <div class="card">
      <div class="row head-row">
        <input v-model="title" placeholder="作业标题" class="grow" />
        <select v-model="mode">
          <option value="HOMEWORK">普通作业</option>
          <option value="EXAM">正式考试</option>
        </select>
        <select v-model="draftPick" @change="loadDraft" class="grow-draft">
          <option value="">载入已有试卷…</option>
          <option v-for="a in drafts" :key="a.id" :value="a.id">
            #{{ a.id }} {{ a.title }}（{{ statusLabel(a.status) }}）
          </option>
        </select>
        <button v-if="draftId" class="secondary" @click="resetEditor">新建试卷</button>
      </div>
      <p v-if="draftId" class="muted draft-hint">正在编辑试卷 #{{ draftId }}（{{ statusLabel(draftStatus) }}），修改权重后需重新保存</p>
    </div>

    <div class="card">
      <h3>1. 选择题目</h3>
      <div class="row pick-bar">
        <select v-model="pickClassId" @change="onPickClass">
          <option value="">选择授课班级</option>
          <option v-for="c in classes" :key="c.teachingClassId" :value="c.teachingClassId">
            {{ c.name }}（{{ c.code }}）
          </option>
        </select>
        <select v-model="pickBankId" @change="loadPickProblems" :disabled="!pickClassId">
          <option value="">选择题库</option>
          <option v-for="b in banks" :key="b.id" :value="b.id">{{ b.name }}</option>
        </select>
        <input v-model="pickKeyword" placeholder="搜索编号/标题" class="slim" />
        <span class="muted">{{ filteredPick.length }} 道题</span>
      </div>

      <div v-if="filteredPick.length" class="pick-list">
        <table>
          <thead><tr><th style="width: 40px;"></th><th>编号</th><th>标题</th><th>语言</th><th>状态</th><th style="width: 130px;">权重</th></tr></thead>
          <tbody>
            <tr v-for="p in filteredPick" :key="p.id" :class="{ picked: pickedIds.has(p.id) }">
              <td><input type="checkbox" :checked="pickedIds.has(p.id)" @change="togglePick(p)" /></td>
              <td class="mono">{{ p.code }}</td>
              <td>{{ p.title }}</td>
              <td class="muted">{{ langLabel(p.languages) }}</td>
              <td><span class="chip" :class="p.status === 'PUBLISHED' ? 'chip-ok' : 'chip-warn'">{{ p.status === 'PUBLISHED' ? '已发布' : '草稿' }}</span></td>
              <td><input v-if="pickedIds.has(p.id)" v-model="weights[p.id]" type="number" step="0.01" min="0" class="slim" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty">选择班级与题库后展示题目列表</div>
    </div>

    <div class="card">
      <div class="row section-row">
        <h3>2. 权重分配</h3>
        <div class="spacer"></div>
        <span>已选 <strong>{{ picked.length }}</strong> 题</span>
        <span class="weight" :class="weightSum === 100 ? 'ok' : 'bad'">
          权重和：<strong>{{ weightSum }}</strong><span v-if="weightSum !== 100"> / 100</span>
        </span>
        <button class="secondary slim-btn" :disabled="!picked.length" @click="evenSplit">平均分配</button>
      </div>
      <div v-if="picked.length" class="picked-summary">
        <span v-for="(p, i) in picked" :key="p.id" class="chip chip-primary">
          {{ p.code }} · {{ weights[p.id] || 0 }}分
          <button class="chip-x" @click="togglePick(p)">×</button>
        </span>
      </div>
      <div v-else class="empty">尚未选择题目</div>
    </div>

    <div class="card">
      <h3>3. 发布到班级</h3>
      <div class="row pick-bar">
        <select v-model="targetPick">
          <option value="">选择要发布的班级</option>
          <option v-for="c in classes" :key="c.teachingClassId" :value="c.teachingClassId" :disabled="targetClassIds.has(c.teachingClassId)">
            {{ c.name }}（{{ c.code }}）{{ targetClassIds.has(c.teachingClassId) ? ' · 已添加' : '' }}
          </option>
        </select>
        <button class="secondary" :disabled="!targetPick" @click="addTarget">+ 添加</button>
      </div>

      <div class="row pick-bar uniform-bar">
        <span class="muted">统一时间（留空表示不限时，发布后还可修改/立即收卷）：</span>
        <input v-model="uniformPublishAt" type="datetime-local" aria-label="统一发布时间" />
        <span class="muted">—</span>
        <input v-model="uniformDeadline" type="datetime-local" aria-label="统一截止时间" />
        <button class="secondary slim-btn" @click="applyUniform">应用到全部班级</button>
      </div>

      <table v-if="targets.length">
        <thead><tr><th>班级</th><th>发布时间（可选）</th><th>截止时间（可选）</th><th style="width: 130px;">最大提交次数</th><th style="width: 60px;"></th></tr></thead>
        <tbody>
          <tr v-for="(t, i) in targets" :key="t.teachingClassId">
            <td>{{ className(t.teachingClassId) }}</td>
            <td><input v-model="t.publishAt" type="datetime-local" /></td>
            <td><input v-model="t.deadline" type="datetime-local" /></td>
            <td><input v-model="t.maxSubmissions" type="number" min="1" class="slim" /></td>
            <td><button class="danger slim-btn" @click="targets.splice(i, 1)">移除</button></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">尚未添加发布班级</div>

      <div class="row action-row">
        <button :disabled="!canPublish" @click="saveAndPublish">{{ draftId ? '保存并发布' : '保存草稿并发布' }}</button>
        <button class="secondary" :disabled="!itemsReady" @click="saveDraft">仅保存草稿</button>
      </div>
    </div>

    <div v-if="msg" class="ok-banner">{{ msg }}</div>
    <div v-if="errMsg" class="err-banner">{{ errMsg }}</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api'

const title = ref('')
const mode = ref('HOMEWORK')
const draftId = ref(null)
const draftStatus = ref('DRAFT')
const drafts = ref([])
const draftPick = ref('')

const classes = ref([])
const banks = ref([])
const pickClassId = ref('')
const pickBankId = ref('')
const pickKeyword = ref('')
const pickProblems = ref([])
const picked = ref([])
const weights = ref({})

const targets = ref([])
const targetPick = ref('')
const uniformPublishAt = ref('')
const uniformDeadline = ref('')

const msg = ref('')
const errMsg = ref('')

const pickedIds = computed(() => new Set(picked.value.map(p => p.id)))
const targetClassIds = computed(() => new Set(targets.value.map(t => t.teachingClassId)))
const filteredPick = computed(() => {
  const kw = pickKeyword.value.trim().toLowerCase()
  if (!kw) return pickProblems.value
  return pickProblems.value.filter(p =>
    p.code.toLowerCase().includes(kw) || p.title.toLowerCase().includes(kw)
  )
})
const weightSum = computed(() =>
  Math.round(picked.value.reduce((s, p) => s + (parseFloat(weights.value[p.id]) || 0), 0) * 100) / 100
)
const itemsReady = computed(() =>
  title.value.trim() && picked.value.length > 0 && weightSum.value === 100
)
const targetsReady = computed(() =>
  targets.value.length > 0 &&
  targets.value.every(t => t.teachingClassId && Number(t.maxSubmissions) > 0)
)
const canPublish = computed(() => itemsReady.value && targetsReady.value)

onMounted(async () => {
  try {
    const [cls, list] = await Promise.all([api('/teacher/classes'), api('/teacher/assignments')])
    classes.value = cls
    drafts.value = list
  } catch (e) {
    errMsg.value = e.message
  }
})

function notify(text) {
  msg.value = text
  errMsg.value = ''
  setTimeout(() => { if (msg.value === text) msg.value = '' }, 5000)
}
function fail(e) {
  errMsg.value = e.message
  msg.value = ''
}

async function onPickClass() {
  pickBankId.value = ''
  pickProblems.value = []
  if (!pickClassId.value) { banks.value = []; return }
  try {
    banks.value = await api(`/teacher/problem-banks?teachingClassId=${pickClassId.value}`)
    if (banks.value.length === 1) {
      pickBankId.value = banks.value[0].id
      await loadPickProblems()
    }
  } catch (e) { fail(e) }
}

async function loadPickProblems() {
  if (!pickBankId.value) { pickProblems.value = []; return }
  try {
    pickProblems.value = await api(`/teacher/problems?bankId=${pickBankId.value}`)
  } catch (e) { fail(e) }
}

function togglePick(p) {
  const idx = picked.value.findIndex(x => x.id === p.id)
  if (idx >= 0) {
    picked.value.splice(idx, 1)
    delete weights.value[p.id]
  } else {
    picked.value.push(p)
    weights.value[p.id] = weights.value[p.id] ?? ''
  }
}

function evenSplit() {
  const n = picked.value.length
  if (!n) return
  const base = Math.floor((100 / n) * 100) / 100
  let used = 0
  picked.value.forEach((p, i) => {
    const w = i === n - 1 ? Math.round((100 - used) * 100) / 100 : base
    weights.value[p.id] = w
    used += w
  })
}

function draftItems() {
  return picked.value.map(p => ({ problemId: p.id, weight: Number(weights.value[p.id]) }))
}

async function saveDraft() {
  try {
    const body = {
      title: title.value,
      mode: mode.value,
      items: draftItems()
    }
    if (draftId.value) {
      await api(`/teacher/assignments/${draftId.value}`, { method: 'PUT', body })
      notify('试卷已更新（#' + draftId.value + '）')
    } else {
      const res = await api('/teacher/assignments', { method: 'POST', body })
      draftId.value = res.id
      draftStatus.value = res.status
      notify('草稿已保存（#' + res.id + '），可继续发布')
    }
    await loadDrafts()
  } catch (e) { fail(e) }
}

function addTarget() {
  const id = Number(targetPick.value)
  if (!id || targetClassIds.value.has(id)) return
  targets.value.push({
    teachingClassId: id,
    publishAt: uniformPublishAt.value,
    deadline: uniformDeadline.value,
    maxSubmissions: 50
  })
  targetPick.value = ''
}

function applyUniform() {
  for (const t of targets.value) {
    t.publishAt = uniformPublishAt.value
    t.deadline = uniformDeadline.value
  }
  notify('统一时间已应用到全部班级（若已有时间将被覆盖）')
}

async function saveAndPublish() {
  try {
    if (!draftId.value) {
      const res = await api('/teacher/assignments', {
        method: 'POST',
        body: { title: title.value, mode: mode.value, items: draftItems() }
      })
      draftId.value = res.id
    }
    await api(`/teacher/assignments/${draftId.value}/publish`, {
      method: 'POST',
      body: {
        targets: targets.value.map(t => ({
          teachingClassId: Number(t.teachingClassId),
          publishAt: t.publishAt ? normTime(t.publishAt) : null,
          deadline: t.deadline ? normTime(t.deadline) : null,
          maxSubmissions: Number(t.maxSubmissions)
        }))
      }
    })
    draftStatus.value = 'PUBLISHED'
    notify(`已发布到 ${targets.value.length} 个班级（试卷 #${draftId.value}）`)
    await loadDrafts()
  } catch (e) { fail(e) }
}

async function loadDrafts() {
  drafts.value = await api('/teacher/assignments')
}

async function loadDraft() {
  if (!draftPick.value) return
  try {
    const d = await api(`/teacher/assignments/${draftPick.value}`)
    draftId.value = d.id
    draftStatus.value = d.status
    title.value = d.title
    mode.value = d.mode
    picked.value = []
    weights.value = {}
    const known = new Map()
    pickProblems.value.forEach(p => known.set(p.id, p))
    for (const item of d.items) {
      const p = known.get(item.problemId) || {
        id: item.problemId,
        code: '#' + item.problemId,
        title: '题目 #' + item.problemId,
        languages: '',
        status: 'PUBLISHED'
      }
      picked.value.push(p)
      weights.value[p.id] = item.weight
    }
    targets.value = d.targets
      .filter(t => t.status !== 'WITHDRAWN')
      .map(t => ({
        teachingClassId: t.teachingClassId,
        publishAt: sliceTime(t.publishAt),
        deadline: sliceTime(t.deadline),
        maxSubmissions: t.maxSubmissions
      }))
    const dts = d.targets.filter(t => t.status !== 'WITHDRAWN')
    if (dts.length === 1) {
      uniformPublishAt.value = sliceTime(dts[0].publishAt)
      uniformDeadline.value = sliceTime(dts[0].deadline)
    }
    draftPick.value = ''
    notify('已载入试卷 #' + d.id + '（' + statusLabel(d.status) + '）')
  } catch (e) { fail(e) }
}

function resetEditor() {
  draftId.value = null
  draftStatus.value = 'DRAFT'
  title.value = ''
  picked.value = []
  weights.value = {}
  targets.value = []
  uniformPublishAt.value = ''
  uniformDeadline.value = ''
  notify('已切换为新建试卷')
}

function className(id) {
  const c = classes.value.find(x => x.teachingClassId === id)
  return c ? `${c.name}（${c.code}）` : '班级 #' + id
}
function statusLabel(s) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', CLOSED: '已关闭' }[s] || s
}
function langLabel(l) {
  const map = { C: 'C', CPP: 'C++', PYTHON: 'Python', JAVA: 'Java' }
  return String(l || '').split(',').map(x => map[x] || x).join(' / ')
}
function normTime(v) { return v && v.length === 16 ? v + ':00' : v }
function sliceTime(v) { return v ? String(v).slice(0, 16) : '' }
</script>

<style scoped>
.head-row { margin-bottom: 6px; }
.head-row input { min-width: 260px; }
.grow { flex: 1; min-width: 220px; }
.grow-draft { max-width: 320px; }
.draft-hint { margin: 6px 0 0; font-size: 13px; }
.pick-bar { margin-bottom: 14px; }
.pick-list { max-height: 380px; overflow-y: auto; border: 1px solid var(--border); border-radius: 10px; }
.pick-list table { margin-bottom: 0; border: none; box-shadow: none; }
.pick-list tr.picked td { background: var(--accent-soft); }
.mono { font-family: Consolas, monospace; font-size: 13px; }
.section-row { margin-bottom: 12px; }
.section-row h3 { margin: 0; }
.weight { font-size: 13.5px; }
.weight.ok { color: var(--ok); }
.weight.bad { color: var(--danger); }
.slim-btn { padding: 5px 12px; font-size: 13px; }
.picked-summary { display: flex; flex-wrap: wrap; gap: 8px; }
.chip-x {
  background: none;
  border: none;
  box-shadow: none;
  color: inherit;
  padding: 0 0 0 4px;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}
.chip-x:hover { background: none; color: var(--danger); }
.action-row { margin-top: 16px; }
.uniform-bar { align-items: center; flex-wrap: wrap; }
input[type="datetime-local"] { padding: 7px 10px; }
.ok-banner {
  background: var(--ok-soft);
  color: var(--ok);
  border: 1px solid #bbe7d4;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  margin-top: 4px;
}
.err-banner {
  background: var(--danger-soft);
  color: var(--danger);
  border: 1px solid #fecaca;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  margin-top: 4px;
}
</style>
