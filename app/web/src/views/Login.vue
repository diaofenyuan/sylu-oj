<template>
  <div class="landing">
    <header class="landing-top">
      <div class="brand"><span class="logo">OJ</span><span>SYLU<span class="brand-suffix"> / OJ</span></span></div>
      <div class="top-actions"><span class="platform-name">在线判题平台</span><ThemeToggle /></div>
    </header>
    <div class="landing-body">
      <section class="hero" aria-labelledby="welcome-title">
        <h1 id="welcome-title">让每一次练习，<br />都有进步。</h1>
        <p class="lead">从第一行代码，到独立解决问题。<br />在这里，专注思考，让反馈陪伴学习。</p>
        <div class="features">
          <div><span class="feature-number">01</span><div><h3>练习有方向</h3><p>班级作业与自主刷题，学习节奏由你掌握。</p></div></div>
          <div><span class="feature-number">02</span><div><h3>提交有反馈</h3><p>在线编写、自测与评测，每一步都有清晰结果。</p></div></div>
          <div><span class="feature-number">03</span><div><h3>教学更轻松</h3><p>管理题库、布置作业、分析成绩，一处完成。</p></div></div>
        </div>
        <div class="languages"><span>多语言，同样专注</span><div>C <span>/</span> C++ <span>/</span> Java <span>/</span> Python</div></div>
      </section>
      <section class="login-side" aria-labelledby="login-title">
        <div class="login-card">
          <h2 id="login-title">欢迎回来</h2>
          <p class="card-sub">登录账号，继续你的学习与教学。</p>
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
    <footer class="landing-foot">SYLU-OJ · 专注编程，让学习发生</footer>
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
.landing-top { display: flex; align-items: center; justify-content: space-between; width: 100%; max-width: 1344px; margin: 0 auto; padding: 28px 48px; }
.brand { display: flex; align-items: center; gap: 12px; font-size: 20px; font-weight: 700; }
.logo { width: 38px; height: 38px; display: grid; place-items: center; background: var(--button-bg); color: #fff; border-radius: 11px; font-size: 15px; letter-spacing: -1px; }
.brand-suffix { color: var(--muted); font-weight: 450; }
.top-actions { display: flex; align-items: center; gap: 20px; }
.platform-name { color: var(--muted); font-size: 13px; }
.landing-body { flex: 1; width: 100%; max-width: 1344px; padding: 8px 48px 24px; margin: 0 auto; display: grid; grid-template-columns: 1.08fr 1fr; gap: 72px; align-items: stretch; }
.hero { background: #152b50; color: #f5f8ff; border-radius: 22px; padding: clamp(36px, 4vw, 60px); display: flex; flex-direction: column; }
.hero h1 { margin: 0 0 20px; font-size: clamp(34px, 3.2vw, 48px); line-height: 1.42; letter-spacing: -0.045em; font-weight: 650; }
.lead { color: #becce2; font-size: 15px; line-height: 1.9; margin: 0 0 36px; }
.features { display: grid; gap: 22px; }
.features > div { display: flex; align-items: flex-start; gap: 18px; }
.feature-number { font: 13px/1.8 Consolas, monospace; color: #9fbbee; padding-top: 1px; }
.features h3 { font-size: 15px; font-weight: 550; margin: 0 0 5px; }
.features p { color: #becce2; font-size: 13px; line-height: 1.7; margin: 0; }
.languages { border-top: 1px solid #3b4f6e; margin-top: 38px; padding-top: 22px; display: flex; flex-direction: column; gap: 8px; }
.languages > span { color: #becce2; font-size: 12px; }
.languages > div { font: 15px/1.6 Consolas, monospace; }
.languages div span { color: #768caa; margin: 0 10px; }
.login-side { display: flex; align-items: center; justify-content: center; }
.login-card { width: 100%; max-width: 370px; padding: 32px 0; }
.login-card h2 { font-size: 30px; margin: 0 0 10px; letter-spacing: -0.03em; }
.card-sub { color: var(--muted); margin: 0 0 36px; font-size: 14px; }
form { display: flex; flex-direction: column; }
form label { display: block; font-size: 14px; font-weight: 600; margin-bottom: 9px; }
form input { width: 100%; min-height: 50px; background: var(--bg); padding: 13px 15px; }
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
@media (max-width: 1000px) { .landing-body { gap: 40px; padding-inline: 32px; } .landing-top { padding-inline: 32px; } .hero { padding: 36px; } }
@media (max-width: 760px) {
  .landing-top { padding: 20px 24px; }
  .platform-name { display: none; }
  .landing-body { display: flex; flex-direction: column; gap: 24px; padding: 16px 24px 0; }
  /* 手机先展示登录操作，避免用户滑过宣传内容才能进入系统。 */
  .login-side { order: -1; }
  .login-card { max-width: 420px; padding: 12px 0 24px; }
  .login-card h2 { font-size: 28px; }
  .card-sub { margin-bottom: 28px; }
  .hero { padding: 28px; border-radius: 16px; }
  .hero h1 { font-size: 30px; }
  .hero .lead { font-size: 14px; margin-bottom: 24px; }
  .features { gap: 18px; }
  .languages { margin-top: 26px; }
}
</style>
