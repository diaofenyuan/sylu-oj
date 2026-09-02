<template>
  <div class="app-shell">
    <header v-if="!isLogin" class="topbar">
      <router-link :to="homeFor(role)" class="brand">
        <span class="logo">OJ</span>
        <span class="brand-name">SYLU-OJ</span>
        <span class="brand-tag">{{ role === 'STUDENT' ? '学生端' : role === 'ADMIN' ? '管理员端' : '教师端' }}</span>
      </router-link>
      <nav v-if="authed && role === 'STUDENT'">
        <router-link to="/student">我的作业</router-link>
        <router-link to="/student/practice">刷题中心</router-link>
      </nav>
      <nav v-else-if="authed && role === 'ADMIN'">
        <router-link to="/admin">管理控制台</router-link>
        <router-link to="/teacher/classes">授课班级</router-link>
        <router-link to="/teacher/assignment">组卷发布</router-link>
        <router-link to="/teacher/assignments">作业管理</router-link>
      </nav>
      <nav v-else-if="authed">
        <router-link to="/teacher/classes">授课班级</router-link>
        <router-link to="/teacher/assignment">组卷发布</router-link>
        <router-link to="/teacher/assignments">作业管理</router-link>
      </nav>
      <div class="spacer"></div>
      <button class="theme-toggle" @click="toggleTheme" :title="themeTitle">
        <Icon :icon="themeIcon" />
      </button>
      <button v-if="authed" class="secondary" @click="logout">退出登录</button>
    </header>
    <main :class="{ full: isLogin, workbench: isWorkbench }"><router-view /></main>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getToken } from './api'
import { getRole, clearSession } from './auth'
import { useTheme } from './composables/useTheme'

const router = useRouter()
const route = useRoute()
const { theme, toggleTheme, applyTheme } = useTheme()

const isLogin = computed(() => route.path === '/login' || route.path === '/')
const isWorkbench = computed(() =>
  route.path.startsWith('/student/practice') || route.path.startsWith('/student/targets/'))
// 依赖 route.path 使其在路由变化时重新读取本地缓存
const role = computed(() => {
  void route.path
  return getRole()
})
const authed = computed(() => !!getToken() && !isLogin.value)

const themeIcon = computed(() => {
  if (theme.value === 'dark') return 'mdi:weather-night'
  if (theme.value === 'light') return 'mdi:weather-sunny'
  return 'mdi:theme-light-dark'
})

const themeTitle = computed(() => {
  if (theme.value === 'dark') return '暗黑模式'
  if (theme.value === 'light') return '明亮模式'
  return '跟随系统'
})

function homeFor(r) {
  if (r === 'STUDENT') return '/student'
  if (r === 'ADMIN') return '/admin'
  return '/teacher/classes'
}

function logout() {
  clearSession()
  router.push('/login')
}

onMounted(() => {
  applyTheme()
})
</script>

<style scoped>
.app-shell { min-height: 100vh; display: flex; flex-direction: column; }

.topbar {
  position: sticky;
  top: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 11px 28px;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border);
}

.dark .topbar {
  background: rgba(19, 24, 38, 0.86);
}

.brand { display: flex; align-items: center; gap: 10px; color: var(--text); }
.brand:hover { color: var(--text); }
.logo {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #3b82f6, #2563eb 55%, #1d4ed8);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: -0.02em;
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.3);
}
.brand-name { font-weight: 800; font-size: 16px; letter-spacing: -0.01em; }
.brand-tag {
  font-size: 12px;
  color: var(--muted);
  background: var(--panel-2);
  border: 1px solid var(--border);
  padding: 1px 9px;
  border-radius: 999px;
}

nav { display: flex; gap: 6px; }
nav a {
  color: var(--muted);
  font-weight: 500;
  padding: 7px 13px;
  border-radius: 9px;
  transition: color 0.15s ease, background 0.15s ease;
}
nav a:hover { color: var(--text); background: var(--panel-2); }
nav a.router-link-active { color: var(--accent); background: var(--accent-soft); }

.theme-toggle {
  width: 36px;
  height: 36px;
  padding: 0;
  display: grid;
  place-items: center;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 9px;
  color: var(--muted);
  transition: all 0.15s ease;
}
.theme-toggle:hover {
  background: var(--panel);
  color: var(--accent);
  border-color: var(--border-strong);
}

main {
  padding: 28px clamp(20px, 4vw, 48px) 40px;
  flex: 1;
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
}
main.full { padding: 0; max-width: none; }
main.workbench {
  padding: 0;
  max-width: none;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
