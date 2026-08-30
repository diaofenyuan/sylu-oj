<template>
  <div>
    <div class="page-head">
      <h2>班级题库</h2>
      <p class="muted">创建与维护编程题，发布后学生方可作答</p>
    </div>

    <div class="card">
      <h3>新建编程题</h3>
      <div class="row">
        <input v-model="form.code" placeholder="题号" />
        <input v-model="form.title" placeholder="题名" />
        <select v-model="form.language">
          <option value="C">C</option>
          <option value="CPP">C++</option>
          <option value="PYTHON">Python</option>
          <option value="JAVA">Java</option>
        </select>
        <button @click="create">创建题目</button>
      </div>
      <p class="muted hint">创建后可在题目详情中维护公开样例与隐藏用例。同一题库内题号、题名均不可重复。</p>
      <div v-if="errMsg" class="err-banner">{{ errMsg }}</div>
    </div>

    <table v-if="problems.length">
      <thead><tr><th>题号</th><th>题名</th><th>语言</th><th>状态</th><th>版本</th><th></th></tr></thead>
      <tbody>
        <tr v-for="p in problems" :key="p.id">
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
    <div v-else class="empty">题库为空，先创建第一道题目吧</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'

const route = useRoute()
const classId = route.params.classId
const problems = ref([])
const form = ref({ code: '', title: '', language: 'CPP' })
const errMsg = ref('')
let bankId = null

const STATUS = { DRAFT: '草稿', PUBLISHED: '已发布' }
function statusText(s) {
  return STATUS[s] || s
}

async function load() {
  const banks = await api(`/teacher/problem-banks?teachingClassId=${classId}`)
  bankId = banks[0]?.id
  if (!bankId) {
    const bank = await api('/teacher/problem-banks', {
      method: 'POST', body: { teachingClassId: Number(classId), name: '默认题库' }
    })
    bankId = bank.id
  }
  problems.value = await api(`/teacher/problems?bankId=${bankId}`)
}

async function create() {
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
  try {
    await api('/teacher/problems', {
      method: 'POST',
      body: {
        bankId, code, title,
        languages: [form.value.language],
        testcases: [{ orderNum: 1, sample: true, input: '1 2', expectedOutput: '3', score: 10 }]
      }
    })
    form.value.code = ''
    form.value.title = ''
    await load()
  } catch (e) {
    errMsg.value = e.message
  }
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
</style>
