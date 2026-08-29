<template>
  <div class="app-shell">
    <header class="topbar">
      <span class="brand">在线判题系统 · 教师端</span>
      <nav v-if="authed">
        <router-link to="/teacher/classes">授课班级</router-link>
        <router-link to="/teacher/assignment">组卷发布</router-link>
      </nav>
      <div class="spacer"></div>
      <button v-if="authed" class="secondary" @click="logout">退出</button>
    </header>
    <main><router-view /></main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { getToken, clearToken } from './api'

const router = useRouter()
const authed = computed(() => !!getToken())

function logout() {
  clearToken()
  router.push('/login')
}
</script>

<style scoped>
.app-shell { min-height: 100vh; display: flex; flex-direction: column; }
.topbar {
  display: flex; align-items: center; gap: 20px;
  padding: 12px 20px; background: var(--panel);
  border-bottom: 1px solid var(--border);
}
.brand { font-weight: 700; }
nav { display: flex; gap: 16px; }
main { padding: 20px; flex: 1; }
</style>
