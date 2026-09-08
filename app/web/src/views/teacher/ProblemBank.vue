<template>
  <div>
    <router-link to="/teacher/classes" class="back-link"><Icon icon="mdi:arrow-left" aria-hidden="true" />返回授课班级</router-link>
    <div class="page-head">
      <h2>班级题库</h2>
      <p class="muted">创建与维护编程题，发布后学生方可作答</p>
    </div>

    <div class="card">
      <h3>新建编程题</h3>
      <div class="row create-row">
        <label class="form-field">题号<input v-model="form.code" placeholder="例如 P1001" /></label>
        <label class="form-field grow-field">题目名称<input v-model="form.title" placeholder="输入清晰的题目名称" /></label>
        <label class="form-field">编程语言<select v-model="form.language">
          <option value="C">C</option>
          <option value="CPP">C++</option>
          <option value="PYTHON">Python</option>
          <option value="JAVA">Java</option>
        </select></label>
        <button :disabled="loading || saving || !bankId" @click="create">{{ saving ? '创建中…' : '创建题目' }}</button>
      </div>
      <p class="muted hint">同一题库内题号、题名均不可重复。发布后，题目可用于组卷与练习。</p>
      <div v-if="errMsg" class="err-banner" role="alert">{{ errMsg }}</div>
    </div>

    <div class="page-toolbar"><span class="muted">共 {{ problems.length }} 道题目</span><label class="search-field"><span class="sr-only">搜索班级题库</span><Icon icon="mdi:magnify" aria-hidden="true" /><input v-model.trim="keyword" type="search" placeholder="搜索题号或题目名称" /></label></div>
    <p v-if="loading" role="status" class="muted">正在加载题库…</p>
    <div v-if="loadError" class="error-banner" role="alert"><span>{{ loadError }}</span><button class="secondary" :disabled="loading" @click="load">重新加载</button></div>
    <table v-if="filteredProblems.length">
      <thead><tr><th>题号</th><th>题名</th><th>语言</th><th>状态</th><th>版本</th><th></th></tr></thead>
      <tbody>
        <tr v-for="p in filteredProblems" :key="p.id">
          <td><code>{{ p.code }}</code></td>
          <td><strong>{{ p.title }}</strong></td>
          <td>{{ (p.languages || []).join(' / ') }}</td>
          <td>
            <span class="chip" :class="p.status === 'PUBLISHED' ? 'chip-ok' : 'chip-warn'">
              {{ statusText(p.status) }}
            </span>
          </td>
          <td class="muted">v{{ p.version }}</td>
          <td>
            <button v-if="p.status !== 'PUBLISHED'" class="secondary" @click="publish(p.id)">发布</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else-if="!loading && !loadError" class="empty">{{ problems.length ? '没有匹配的题目，请调整搜索内容。' : '题库还是空的，在上方创建第一道题目吧。' }}</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'

const route = useRoute()
const classId = route.params.classId
const problems = ref([])
const form = ref({ code: '', title: '', language: 'CPP' })
const errMsg = ref('')
const bankId = ref(null)
const keyword = ref('')
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const filteredProblems = computed(() => problems.value.filter(p => (p.code + ' ' + p.title).toLowerCase().includes(keyword.value.toLowerCase())))

const STATUS = { DRAFT: '草稿', PUBLISHED: '已发布' }
function statusText(s) {
  return STATUS[s] || s
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const banks = await api(`/teacher/problem-banks?teachingClassId=${classId}`)
    bankId.value = banks[0]?.id
    if (!bankId.value) {
      const bank = await api('/teacher/problem-banks', {
        method: 'POST', body: { teachingClassId: Number(classId), name: '默认题库' }
      })
      bankId.value = bank.id
    }
    problems.value = await api(`/teacher/problems?bankId=${bankId.value}`)
  } catch (e) { loadError.value = e.message || '题库加载失败' }
  finally { loading.value = false }
}

async function create() {
  if (saving.value || loading.value || !bankId.value) return
  errMsg.value = ''
  const code = form.value.code.trim()
  const title = form.value.title.trim()
  if (!code || !title) {
    errMsg.value = '题号与题名均为必填'
    return
  }
  if (problems.value.some(p => p.code === code)) {
    errMsg.value = `题号「${code}」已存在于当前题库`
    return
  }
  if (problems.value.some(p => p.title === title)) {
    errMsg.value = `题名「${title}」已存在于当前题库`
    return
  }
  saving.value = true
  try {
    await api('/teacher/problems', {
      method: 'POST',
      body: {
        bankId: bankId.value, code, title,
        languages: [form.value.language],
        testcases: [{ orderNum: 1, sample: true, input: '1 2', expectedOutput: '3', score: 10 }]
      }
    })
    form.value.code = ''
    form.value.title = ''
    await load()
  } catch (e) {
    errMsg.value = e.message
  } finally { saving.value = false }
}

async function publish(id) {
  await api(`/teacher/problems/${id}/publish`, { method: 'PUT' })
  await load()
}

onMounted(load)
</script>

<style scoped>
.hint { margin: 12px 0 0; font-size: 13px; }
.err-banner {
  background: var(--danger-soft);
  color: var(--danger);
  border: 1px solid #fecaca;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  margin-top: 12px;
}
td code {
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 13px;
}
.create-row { align-items: flex-end; }
.grow-field { flex: 1; min-width: 200px; }
@media (max-width: 600px) { .create-row > label, .create-row > button { width: 100%; } }
</style>
