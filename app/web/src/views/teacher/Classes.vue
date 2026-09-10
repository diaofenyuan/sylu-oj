<template>
  <div>
    <div class="page-head" v-reveal>
      <h2>我的授课班级</h2>
      <p class="muted">管理班级题库、组卷发布与成绩分析</p>
    </div>

    <SkeletonList v-if="loading" :count="3" />

    <ErrorState v-else-if="error" :detail="error" :retrying="loading" @retry="load" />

    <template v-else>
      <div class="card class-card" v-for="(c, i) in classes" :key="c.teachingClassId"
           v-reveal="{ delay: i * 70 }">
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

      <EmptyState
        v-if="classes.length === 0"
        icon="mdi:school-outline"
        title="暂无授课班级"
        description="请先完成班级组建，组建后即可管理题库与发布作业" />
    </template>
  </div>
</template>

<script setup>
import { api } from '../../api'
import { useAsyncData } from '../../composables/useAsyncData'

const { data: classes, loading, error, load } = useAsyncData(() => api('/teacher/classes'))
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
  font-size: var(--fs-lg);
  box-shadow: 0 3px 8px rgba(37, 99, 235, 0.22);
}
.class-info { display: flex; flex-direction: column; line-height: 1.45; }
.class-info strong { font-size: var(--fs-md); }
.class-info .muted { font-size: var(--fs-sm); }
</style>
