<template>
  <div>
    <h2>班级题库</h2>
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
      <p class="muted">创建后可在题目详情中维护公开样例与隐藏用例。</p>
    </div>

    <table v-if="problems.length">
      <thead><tr><th>题号</th><th>题名</th><th>语言</th><th>状态</th><th>版本</th><th></th></tr></thead>
      <tbody>
        <tr v-for="p in problems" :key="p.id">
          <td>{{ p.code }}</td><td>{{ p.title }}</td>
          <td>{{ p.languages }}</td><td>{{ p.status }}</td><td>{{ p.version }}</td>
          <td><button v-if="p.status !== 'PUBLISHED'" class="secondary" @click="publish(p.id)">发布</button></td>
        </tr>
      </tbody>
    </table>
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

async function load() {
  const banks = await api(`/teacher/problem-banks?teachingClassId=${classId}`)
  let bankId = banks[0]?.id
  if (!bankId) {
    const bank = await api('/teacher/problem-banks', {
      method: 'POST', body: { teachingClassId: Number(classId), name: '默认题库' }
    })
    bankId = bank.id
  }
  problems.value = await api(`/teacher/problems?bankId=${bankId}`)
}

async function create() {
  const banks = await api(`/teacher/problem-banks?teachingClassId=${classId}`)
  await api('/teacher/problems', {
    method: 'POST',
    body: {
      bankId: banks[0].id, code: form.value.code, title: form.value.title,
      languages: [form.value.language],
      testcases: [{ orderNum: 1, sample: true, input: '1 2', expectedOutput: '3', score: 10 }]
    }
  })
  form.value.code = ''
  form.value.title = ''
  await load()
}

async function publish(id) {
  await api(`/teacher/problems/${id}/publish`, { method: 'PUT' })
  await load()
}

onMounted(load)
</script>
