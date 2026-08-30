<template>
  <div>
    <div class="page-head">
      <h2>我的授课班级</h2>
      <p class="muted">管理班级题库、组卷发布与成绩分析</p>
    </div>

    <div class="card class-card" v-for="c in classes" :key="c.teachingClassId">
      <div class="row">
        <div class="class-avatar">{{ c.name.slice(0, 1) }}</div>
        <div class="class-info">
          <strong>{{ c.name }}</strong>
          <span class="muted">{{ c.code }}</span>
        </div>
        <span class="chip" :class="c.role === 'PRIMARY' ? 'chip-primary' : 'chip-muted'">
          {{ c.role === 'PRIMARY' ? '主讲' : '助教' }}
        </span>
        <div class="spacer"></div>
        <router-link :to="`/teacher/classes/${c.teachingClassId}/problems`">
          <button>进入题库 →</button>
        </router-link>
      </div>
    </div>

    <div v-if="classes.length === 0" class="empty">暂无授课班级，请先完成班级组建</div>
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

<style scoped>
.class-card { padding: 18px 20px; }
.class-avatar {
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, #60a5fa, #2563eb);
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 16px;
  box-shadow: 0 3px 8px rgba(37, 99, 235, 0.22);
}
.class-info { display: flex; flex-direction: column; line-height: 1.45; }
.class-info strong { font-size: 15px; }
.class-info .muted { font-size: 13px; }
</style>
