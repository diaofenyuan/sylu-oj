<template>
  <div>
    <h2>我的授课班级</h2>
    <div class="card" v-for="c in classes" :key="c.teachingClassId">
      <div class="row">
        <strong>{{ c.name }}</strong>
        <span class="muted">{{ c.code }} · 角色：{{ c.role === 'PRIMARY' ? '主讲' : '助教' }}</span>
        <div class="spacer"></div>
        <router-link :to="`/teacher/classes/${c.teachingClassId}/problems`">
          <button>题库</button>
        </router-link>
      </div>
    </div>
    <p v-if="classes.length === 0" class="muted">暂无授课班级</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../../api'

const classes = ref([])

onMounted(async () => {
  classes.value = await api('/teacher/classes')
})
</script>
