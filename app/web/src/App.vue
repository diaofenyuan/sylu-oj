<template>
  <div class="app-shell" :class="{ 'is-workbench': isWorkbench }">
    <a class="skip-link" href="#main-content">跳转到主要内容</a>
    <header v-if="!isLogin" class="topbar">
      <router-link :to="homeFor(role)" class="brand" aria-label="SYLU-OJ 首页">
        <span class="logo">OJ</span>
        <span class="brand-name">SYLU<span class="brand-suffix"> / OJ</span></span>
        <span class="brand-tag">{{ role === 'STUDENT' ? '学生端' : role === 'ADMIN' ? '管理端' : '教师端' }}</span>
      </router-link>
      <nav v-if="authed" aria-label="主导航">
        <router-link v-for="item in navigation" :key="item.path" :to="item.path" :class="{ selected: item.active }" :aria-current="item.active ? 'page' : undefined">
          {{ item.label }}
        </router-link>
      </nav>
      <div class="header-actions">
        <ThemeToggle />
        <button v-if="authed" class="logout-button" @click="logout" aria-label="退出登录" title="退出登录">
          <Icon icon="mdi:logout-variant" aria-hidden="true" /><span>退出</span>
        </button>
      </div>
    </header>
    <main id="main-content" tabindex="-1" :class="{ full: isLogin, workbench: isWorkbench }"><router-view /></main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getToken } from './api'
import { getRole, clearSession } from './auth'
import ThemeToggle from './components/ThemeToggle.vue'
import { useTheme } from './composables/useTheme'

const router = useRouter()
const route = useRoute()
useTheme()
const isLogin = computed(() => route.path === '/login' || route.path === '/')
const isWorkbench = computed(() => route.path.startsWith('/student/practice') || route.path.startsWith('/student/targets/'))
const role = computed(() => { void route.path; return getRole() })
const authed = computed(() => !!getToken() && !isLogin.value)
const navigation = computed(() => {
  const path = route.path
  if (role.value === 'STUDENT') return [
    { path: '/student', label: '我的作业', active: path === '/student' || path.startsWith('/student/targets/') },
    { path: '/student/practice', label: '刷题中心', active: path === '/student/practice' }
  ]
  return [
    ...(role.value === 'ADMIN' ? [{ path: '/admin', label: '管理控制台', active: path === '/admin' }] : []),
    { path: '/teacher/classes', label: '授课班级', active: path.startsWith('/teacher/classes') },
    { path: '/teacher/assignment', label: '组卷发布', active: path === '/teacher/assignment' },
    { path: '/teacher/assignments', label: '作业管理', active: path === '/teacher/assignments' || path.startsWith('/teacher/analytics/') }
  ]
})

function homeFor(value) { return value === 'STUDENT' ? '/student' : value === 'ADMIN' ? '/admin' : '/teacher/classes' }
function logout() { clearSession(); router.push('/login') }
</script>

<style scoped>
.app-shell { min-height: 100dvh; display: flex; flex-direction: column; }
.topbar { position: sticky; top: 0; z-index: 50; display: flex; align-items: center; gap: 40px; min-height: 76px; padding: 0 clamp(24px, 4vw, 64px); background: var(--panel); border-bottom: 1px solid var(--border); }
.brand { display: flex; align-items: center; gap: 11px; flex-shrink: 0; color: var(--text); white-space: nowrap; }
.logo { display: grid; place-items: center; position: relative; width: 36px; height: 36px; border-radius: 7px; background: var(--text); color: var(--panel); font-size: 14px; font-weight: 750; letter-spacing: -1px; }
.brand-name { font-size: 18px; font-weight: 700; letter-spacing: 0.01em; }
.brand-suffix { color: var(--muted); font-weight: 450; }
.brand-tag { color: var(--muted); font-size: 12px; border-left: 1px solid var(--border-strong); padding-left: 12px; margin-left: 3px; }
nav { display: flex; align-self: stretch; gap: 24px; }
nav a { position: relative; display: flex; align-items: center; color: var(--muted); font-size: 14px; font-weight: 550; white-space: nowrap; padding: 0 2px; }
nav a:hover, nav a.selected { color: var(--accent); }
nav a.selected::after { content: ''; position: absolute; bottom: 0; height: 3px; border-radius: 3px 3px 0 0; background: var(--accent); left: 0; right: 0; }
.header-actions { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.logout-button { background: transparent; color: var(--muted); padding: 9px 10px; font-weight: 500; }
.logout-button:hover { background: var(--panel-2); color: var(--text); }
main { flex: 1; width: 100%; max-width: 1328px; margin: 0 auto; padding: 42px clamp(24px, 4vw, 56px) 56px; min-width: 0; }
main:focus { outline: none; }
main.full { padding: 0; max-width: none; }
main.workbench { padding: 0; max-width: none; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.is-workbench { height: 100dvh; min-height: 0; }
.is-workbench .topbar { flex-shrink: 0; }
.skip-link { position: fixed; top: -100px; left: 16px; z-index: 200; padding: 10px 16px; background: var(--panel); border: 1px solid var(--accent); border-radius: 8px; }
.skip-link:focus { top: 12px; }
@media (max-width: 1100px) { .topbar { gap: 24px; } nav { gap: 18px; } .brand-tag { display: none; } }
@media (max-width: 900px) {
  .topbar { flex-wrap: wrap; gap: 0 16px; padding: 12px 20px 0; }
  nav { order: 2; flex-basis: 100%; min-width: 0; overflow-x: auto; gap: 26px; scrollbar-width: none; }
  nav a { min-height: 48px; }
  .header-actions { gap: 4px; }
  .logout-button span { display: none; }
  main { padding: 28px 20px 40px; }
  .is-workbench { height: auto; min-height: 100dvh; }
}
</style>
