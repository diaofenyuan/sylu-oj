<template>
  <div>
    <h2>组卷与发布</h2>
    <div class="card">
      <div class="row">
        <input v-model="title" placeholder="作业标题" />
        <select v-model="mode">
          <option value="HOMEWORK">普通作业</option>
          <option value="EXAM">正式考试</option>
        </select>
      </div>
      <h3>选择题目（权重之和必须恰为 100）</h3>
      <div v-for="(item, i) in items" :key="i" class="row item-row">
        <input v-model="item.problemId" type="number" placeholder="题目 ID" />
        <input v-model="item.weight" type="number" step="0.01" placeholder="权重" />
        <button class="danger" @click="items.splice(i, 1)">删除</button>
      </div>
      <button class="secondary" @click="items.push({ problemId: '', weight: '' })">添加题目</button>
      <p class="muted">当前权重和：{{ weightSum }}</p>
      <button :disabled="weightSum !== 100" @click="saveDraft">保存草稿</button>
    </div>

    <div v-if="draftId" class="card">
      <h3>发布到目标班级</h3>
      <div v-for="(t, i) in targets" :key="i" class="row item-row">
        <input v-model="t.teachingClassId" type="number" placeholder="教学班 ID" />
        <input v-model="t.publishAt" type="datetime-local" placeholder="发布时间" />
        <input v-model="t.deadline" type="datetime-local" placeholder="截止时间" />
        <input v-model="t.maxSubmissions" type="number" placeholder="最大提交次数" />
        <button class="danger" @click="targets.splice(i, 1)">删除</button>
      </div>
      <button class="secondary" @click="targets.push({ teachingClassId: '', publishAt: '', deadline: '', maxSubmissions: 5 })">
        添加目标班级
      </button>
      <button @click="publish">发布</button>
    </div>
    <p v-if="msg" class="muted">{{ msg }}</p>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { api } from '../../api'

const title = ref('')
const mode = ref('HOMEWORK')
const items = ref([{ problemId: '', weight: '' }])
const targets = ref([{ teachingClassId: '', publishAt: '', deadline: '', maxSubmissions: 5 }])
const draftId = ref(null)
const msg = ref('')

const weightSum = computed(() =>
  items.value.reduce((s, i) => s + (parseFloat(i.weight) || 0), 0)
)

async function saveDraft() {
  const res = await api('/teacher/assignments', {
    method: 'POST',
    body: {
      title: title.value, mode: mode.value,
      items: items.value.map(i => ({ problemId: Number(i.problemId), weight: Number(i.weight) }))
    }
  })
  draftId.value = res.id
  msg.value = '草稿已保存，ID=' + res.id
}

async function publish() {
  const res = await api(`/teacher/assignments/${draftId.value}/publish`, {
    method: 'POST',
    body: {
      targets: targets.value.map(t => ({
        teachingClassId: Number(t.teachingClassId),
        publishAt: t.publishAt, deadline: t.deadline,
        maxSubmissions: Number(t.maxSubmissions)
      }))
    }
  })
  msg.value = '已发布 ' + res.length + ' 个目标班级'
}
</script>

<style scoped>
.item-row { margin: 6px 0; }
.item-row input { width: 160px; }
</style>
