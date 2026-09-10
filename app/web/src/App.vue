<template>
  <div class="app-shell">
    <RouteProgress />
    <ToastHost />

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
      <button class="theme-toggle" @click="toggleTheme" :title="themeTitle" :aria-label="themeTitle">
        <Icon :icon="themeIcon" />
      </button>
      <button v-if="authed" class="secondary" @click="logout">退出登录</button>
    </header>

    <main :class="{ full: isLogin, workbench: isWorkbench }">
      <router-view v-slot="{ Component, route: current }">
        <transition :name="transitionName" mode="out-in">
          <component :is="Component" :key="current.path" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getToken } from './api'
import { getRole, clearSession } from './auth'
import { useTheme } from './composables/useTheme'
import RouteProgress from './components/ui/RouteProgress.vue'

const router = useRouter()
const route = useRoute()
const { theme, toggleTheme, applyTheme } = useTheme()

const isLogin = computed(() => route.path === '/login' || route.path === '/')
const isWorkbench = computed(() =>
  route.path.startsWith('/student/practice') || route.path.startsWith('/student/targets/'))

// 工作台为全屏布局，使用纯淡入淡出避免位移抖动；其余页面使用淡入上移
const transitionName = computed(() => (isWorkbench.value ? 'page-fade' : 'page-slide'))

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

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text);
  transition: opacity var(--dur-fast) var(--ease-out);
}
.brand:hover { color: var(--text); opacity: 0.86; }
.brand:active { transform: scale(0.98); }

.logo {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #3b82f6, #2563eb 55%, #1d4ed8);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: var(--fs-xs);
  font-weight: 800;
  letter-spacing: -0.02em;
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.3);
  transition: box-shadow var(--dur-base) var(--ease-out), transform var(--dur-base) var(--ease-spring);
}
.brand:hover .logo {
  transform: rotate(-6deg) scale(1.05);
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.42);
}

.brand-name { font-weight: 800; font-size: var(--fs-lg); letter-spacing: -0.01em; }
.brand-tag {
  font-size: var(--fs-xs);
  color: var(--muted);
  background: var(--panel-2);
  border: 1px solid var(--border);
  padding: 1px 9px;
  border-radius: 999px;
}

nav { display: flex; gap: 6px; }
nav a {
  position: relative;
  color: var(--muted);
  font-weight: 500;
  padding: 7px 13px;
  border-radius: 9px;
  transition: color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out);
}
/* 激活项下划线：由中心缓入展开，强化当前页面归属感 */
nav a::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 3px;
  width: 16px;
  height: 2px;
  border-radius: 2px;
  background: var(--accent);
  opacity: 0;
  transform: translate(-50%, 4px) scaleX(0.4);
  transition: opacity var(--dur-base) var(--ease-out), transform var(--dur-base) var(--ease-out);
}
nav a:hover { color: var(--text); background: var(--panel-2); }
nav a.router-link-active { color: var(--accent); background: var(--accent-soft); }
nav a.router-link-active::after { opacity: 1; transform: translate(-50%, 0) scaleX(1); }

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
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out),
              border-color var(--dur-fast) var(--ease-out), transform var(--dur-base) var(--ease-spring);
}
.theme-toggle:hover {
  background: var(--panel);
  color: var(--accent);
  border-color: var(--border-strong);
  transform: rotate(-14deg) scale(1.06);
}
.theme-toggle:active { transform: rotate(-14deg) scale(0.94); }

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

@media (max-width: 820px) {
  .topbar { gap: var(--space-3); padding: 10px 16px; flex-wrap: wrap; }
  .brand-tag { display: none; }
  nav { order: 3; width: 100%; overflow-x: auto; padding-bottom: 2px; }
}
</style>
