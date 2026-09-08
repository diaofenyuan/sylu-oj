<template>
  <div class="landing">
    <header class="landing-top">
      <div class="brand"><span class="logo">OJ</span><span>SYLU<span class="brand-suffix"> / OJ</span></span></div>
      <div class="top-actions"><span class="platform-name">在线判题平台</span><ThemeToggle /></div>
    </header>
    <div class="landing-body">
      <section class="hero" aria-labelledby="welcome-title">
        <p class="hero-label">SYLU-OJ · 在线判题平台</p>
        <h1 id="welcome-title">程序设计<br />在线教学平台</h1>
        <p class="lead">课程作业、在线编程与成绩管理。<br />为日常教学与自主练习提供统一入口。</p>
        <dl class="features">
          <div><dt>学生</dt><dd>完成课程作业，练习编程题，查看评测结果。</dd></div>
          <div><dt>教师</dt><dd>维护班级题库，布置作业，查看学习情况。</dd></div>
        </dl>
        <div class="languages"><span>支持的编程语言</span><div>C <span>/</span> C++ <span>/</span> Java <span>/</span> Python</div></div>
      </section>
      <section class="login-side" aria-labelledby="login-title">
        <div class="login-card">
          <h2 id="login-title">账号登录</h2>
          <p class="card-sub">请使用学校分配的账号登录。</p>
          <form @submit.prevent="submit" :aria-busy="loading">
            <label for="login-name">账号</label>
            <input id="login-name" v-model.trim="loginName" placeholder="请输入学校分配的账号" autocomplete="username" required :disabled="loading" :aria-describedby="error ? 'login-error' : undefined" />
            <div class="password-label"><label for="login-password">密码</label></div>
            <div class="password-field">
              <input id="login-password" v-model="password" :type="showPassword ? 'text' : 'password'" placeholder="请输入密码" autocomplete="current-password" required :disabled="loading" :aria-describedby="error ? 'login-error' : undefined" />
              <button type="button" class="password-toggle" @click="showPassword = !showPassword" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" :disabled="loading">
                <Icon :icon="showPassword ? 'mdi:eye-off-outline' : 'mdi:eye-outline'" aria-hidden="true" />
              </button>
            </div>
            <div v-if="error" id="login-error" class="err-alert" role="alert">{{ error }}</div>
            <button type="submit" class="submit" :disabled="loading"><Icon v-if="loading" icon="mdi:loading" class="spin-icon" aria-hidden="true" />{{ loading ? '正在登录…' : '登录' }}<Icon v-if="!loading" icon="mdi:arrow-right" aria-hidden="true" /></button>
          </form>
          <p class="role-note"><Icon icon="mdi:shield-check-outline" aria-hidden="true" />根据账号身份，自动进入对应工作区</p>
          <details class="login-help">
            <summary>登录遇到问题？</summary>
            <p>请使用学校分配的账号，确认账号和密码输入正确。如需开通账号或重置密码，请联系任课教师或平台管理员。</p>
          </details>
        </div>
      </section>
    </div>
    <footer class="landing-foot">SYLU-OJ · 程序设计教学与实践</footer>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'
import { refreshRole } from '../auth'
import ThemeToggle from '../components/ThemeToggle.vue'

const router = useRouter()
const loginName = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')

async function submit() {
  if (loading.value) return
  if (!loginName.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await login(loginName.value, password.value)
    const role = await refreshRole()
    router.push(role === 'STUDENT' ? '/student' : role === 'ADMIN' ? '/admin' : '/teacher/classes')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.landing { min-height: 100dvh; display: flex; flex-direction: column; background: var(--panel); }
.landing-top { display: flex; align-items: center; justify-content: space-between; width: 100%; max-width: 1248px; margin: 0 auto; padding: 28px 48px; border-bottom: 1px solid var(--border); }
.brand { display: flex; align-items: center; gap: 12px; font-size: 20px; font-weight: 700; }
.logo { width: 38px; height: 38px; display: grid; place-items: center; background: var(--text); color: var(--panel); border-radius: 7px; font-size: 15px; letter-spacing: -1px; }
.brand-suffix { color: var(--muted); font-weight: 450; }
.top-actions { display: flex; align-items: center; gap: 20px; }
.platform-name { color: var(--muted); font-size: 13px; }
.landing-body { flex: 1; width: 100%; max-width: 1248px; padding: 64px 48px; margin: 0 auto; display: grid; grid-template-columns: 1.15fr 1fr; gap: 64px; align-items: center; }
.hero { padding: 12px 0; display: flex; flex-direction: column; }
.hero-label { color: var(--accent); font-size: 12px; font-weight: 600; letter-spacing: 0.06em; margin: 0 0 24px; }
.hero h1 { margin: 0 0 24px; font-size: clamp(36px, 3.5vw, 48px); line-height: 1.4; letter-spacing: -0.025em; font-weight: 600; }
.lead { color: var(--muted); font-size: 15px; line-height: 1.9; margin: 0 0 36px; }
.features { margin: 0; border-top: 1px solid var(--border); }
.features > div { display: flex; gap: 24px; padding: 17px 0; border-bottom: 1px solid var(--border); }
.features dt { flex-shrink: 0; font-size: 13px; font-weight: 600; }
.features dd { color: var(--muted); font-size: 13px; line-height: 1.7; margin: 0; }
.languages { margin-top: 28px; display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px; }
.languages > span { color: var(--muted); font-size: 12px; }
.languages > div { font: 13px/1.6 Consolas, monospace; }
.languages div span { color: var(--border-strong); margin: 0 10px; }
.login-side { display: flex; align-items: center; justify-content: flex-end; border-left: 1px solid var(--border); padding-left: 56px; }
.login-card { width: 100%; max-width: 360px; padding: 20px 0; }
.login-card h2 { font-size: 26px; margin: 0 0 10px; letter-spacing: -0.03em; }
.card-sub { color: var(--muted); margin: 0 0 36px; font-size: 14px; }
form { display: flex; flex-direction: column; }
form label { display: block; font-size: 14px; font-weight: 600; margin-bottom: 9px; }
form input { width: 100%; min-height: 50px; background: var(--panel); padding: 13px 15px; }
.password-label { margin-top: 22px; }
.password-field { position: relative; }
.password-field input { padding-right: 50px; }
.password-toggle { position: absolute; right: 5px; top: 5px; min-height: 40px; width: 40px; padding: 0; background: transparent; color: var(--muted); }
.password-toggle:hover:not(:disabled) { background: var(--panel-2); color: var(--text); }
.submit { width: 100%; min-height: 50px; margin-top: 28px; justify-content: center; position: relative; }
.submit > svg:last-child:not(.spin-icon) { position: absolute; right: 18px; }
.role-note { display: flex; align-items: center; justify-content: center; gap: 7px; font-size: 12px; color: var(--muted); margin: 16px 0 32px; }
.role-note svg { width: 16px; height: 16px; }
.login-help { border-top: 1px solid var(--border); padding-top: 20px; color: var(--muted); font-size: 13px; }
.login-help summary { cursor: pointer; width: fit-content; padding: 4px 0; }
.login-help p { line-height: 1.8; margin: 12px 0 0; }
.err-alert { margin-top: 16px; padding: 12px 14px; border-radius: 8px; background: var(--danger-soft); color: var(--danger); font-size: 13px; }
.landing-foot { padding: 18px 20px 24px; text-align: center; color: var(--muted); font-size: 12px; }
@media (max-width: 1000px) {
  .landing-body { gap: 36px; padding: 48px 32px; }
  .landing-top { padding-inline: 32px; }
  .login-side { padding-left: 36px; }
}
@media (max-width: 760px) {
  .landing-top { padding: 20px 24px; }
  .platform-name { display: none; }
  .landing-body { display: flex; flex-direction: column; gap: 32px; padding: 28px 24px 0; align-items: stretch; }
  /* 手机先展示登录操作，介绍放到表单之后。 */
  .login-side { order: -1; border: 0; padding: 0; justify-content: center; }
  .login-card { max-width: 420px; padding: 8px 0 16px; }
  .card-sub { margin-bottom: 28px; }
  .hero { padding: 28px 0; border-top: 1px solid var(--border); }
  .hero-label { margin-bottom: 16px; }
  .hero h1 { font-size: 30px; }
  .hero .lead { font-size: 14px; margin-bottom: 24px; }
}
</style>
