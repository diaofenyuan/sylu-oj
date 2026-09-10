<template>
  <div>
    <div class="page-head" v-reveal>
      <h2>班级题库</h2>
      <p class="muted">创建与维护编程题，发布后学生方可作答</p>
    </div>

    <div class="card" v-reveal="{ delay: 60 }">
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
        <button :class="{ 'is-loading': creating }" :disabled="creating" @click="create">创建题目</button>
      </div>
      <p class="muted hint">创建后可在题目详情中维护公开样例与隐藏用例。同一题库内题号、题名均不可重复。</p>
    </div>

    <SkeletonTable v-if="loading" :rows="4" :cols="6" />

    <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />

    <table v-else-if="problems.length" class="fade-in">
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
            <button v-if="p.status !== 'PUBLISHED'" class="secondary" :class="{ 'is-loading': publishingId === p.id }"
                    :disabled="publishingId === p.id" @click="publish(p.id)">发布</button>
          </td>
        </tr>
      </tbody>
    </table>

    <EmptyState
      v-else
      icon="mdi:file-document-outline"
      title="题库为空"
      description="先在上方创建第一道题目吧" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api'
import { useAsyncData, describeError } from '../../composables/useAsyncData'
import { useToast } from '../../composables/useToast'

const route = useRoute()
const classId = route.params.classId
const toast = useToast()

const form = ref({ code: '', title: '', language: 'CPP' })
const creating = ref(false)
const publishingId = ref(null)
// 题库 ID 在加载过程中解析得到，供创建题目时复用
let bankId = null

const { data: problems, loading, error, load } = useAsyncData(async () => {
  const banks = await api(`/teacher/problem-banks?teachingClassId=${classId}`)
  bankId = banks[0]?.id
  if (!bankId) {
    const bank = await api('/teacher/problem-banks', {
      method: 'POST', body: { teachingClassId: Number(classId), name: '默认题库' }
    })
    bankId = bank.id
  }
  return api(`/teacher/problems?bankId=${bankId}`)
}, { initial: [] })

const STATUS = { DRAFT: '草稿', PUBLISHED: '已发布' }
function statusText(s) {
  return STATUS[s] || s
}

async function create() {
  const code = form.value.code.trim()
  const title = form.value.title.trim()
  // 表单校验属于即时输入反馈，用轻量提示而非阻断式错误态
  if (!code || !title) {
    toast.warning('题号与题名均为必填')
    return
  }
  if (problems.value.some((p) => p.code === code)) {
    toast.warning(`题号「${code}」已存在于当前题库`)
    return
  }
  if (problems.value.some((p) => p.title === title)) {
    toast.warning(`题名「${title}」已存在于当前题库`)
    return
  }
  creating.value = true
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
    toast.success(`题目「${title}」已创建`)
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    creating.value = false
  }
}

async function publish(id) {
  publishingId.value = id
  try {
    await api(`/teacher/problems/${id}/publish`, { method: 'PUT' })
    await load()
    toast.success('题目已发布，学生现在可以作答')
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    publishingId.value = null
  }
}
</script>

<style scoped>
.hint { margin: var(--space-3) 0 0; font-size: var(--fs-sm); }

.fade-in { animation: m-fade-in var(--dur-slow) var(--ease-out) both; }

td code {
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px 8px;
  font-size: var(--fs-sm);
}
</style>
