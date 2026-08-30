<template>
  <div class="landing">
    <div class="bg-deco" aria-hidden="true"></div>

    <header class="landing-top">
      <div class="brand">
        <span class="logo">OJ</span>
        <span class="brand-name">SYLU-OJ</span>
        <span class="brand-tag">在线判题系统</span>
      </div>
      <nav class="top-links">
        <a href="#" @click.prevent>产品特性</a>
        <a href="#" @click.prevent>帮助中心</a>
        <a href="#" @click.prevent>联系我们</a>
      </nav>
    </header>

    <div class="landing-body">
      <section class="hero">
        <span class="badge">教师端 · 内测版</span>
        <h1>为教学而生的<br /><span class="grad">在线判题平台</span></h1>
        <p class="lead">
          题目管理、组卷发布、自动评测与成绩分析一站式完成。
          把重复的批改工作交给机器，把更多时间还给学生。
        </p>
        <ul class="features">
          <li>
            <span class="f-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h7l-1 8 10-12h-7l1-8z" /></svg>
            </span>
            <div class="f-text">
              <strong>秒级自动判题</strong>
              <span>提交即刻评测，编译错误与用例结果实时反馈</span>
            </div>
          </li>
          <li>
            <span class="f-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /></svg>
            </span>
            <div class="f-text">
              <strong>安全沙箱运行</strong>
              <span>受限环境隔离执行代码，资源与网络严格管控</span>
            </div>
          </li>
          <li>
            <span class="f-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20V10" /><path d="M10 20V4" /><path d="M16 20v-7" /><path d="M22 20H2" /></svg>
            </span>
            <div class="f-text">
              <strong>成绩分析导出</strong>
              <span>多维统计班级表现，一键导出 XLSX / CSV</span>
            </div>
          </li>
        </ul>
        <p class="langs">支持 C · C++ · Java · Python 多语言评测</p>
      </section>

      <section class="login-side">
        <div class="card login-card">
          <h2 class="card-title">登录教师端</h2>
          <p class="muted card-sub">内测阶段使用开发/内测合成账号登录</p>
          <form @submit.prevent="submit">
            <label>
              <span>账号</span>
              <input v-model="loginName" placeholder="请输入账号" autocomplete="username" />
            </label>
            <label>
              <span>密码</span>
              <input v-model="password" type="password" placeholder="请输入密码" autocomplete="current-password" />
            </label>
            <button type="submit" class="submit" :disabled="loading">
              <span v-if="loading" class="spinner"></span>
              {{ loading ? '登录中…' : '登 录' }}
            </button>
          </form>
          <div v-if="error" class="err-alert">{{ error }}</div>
          <p class="foot-note">正式环境将接入学校教务账号统一登录</p>
        </div>
      </section>
    </div>

    <footer class="landing-foot">© 2026 SYLU-OJ · 在线判题平台</footer>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'
import { refreshRole } from '../auth'

const router = useRouter()
const loginName = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    await login(loginName.value, password.value)
    const role = await refreshRole()
    router.push(role === 'STUDENT' ? '/student' : '/teacher/classes')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.landing {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  position: relative;
}

.bg-deco {
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background:
    radial-gradient(720px 440px at 86% 8%, rgba(37, 99, 235, 0.10), transparent 62%),
    radial-gradient(640px 420px at 4% 92%, rgba(56, 189, 248, 0.10), transparent 58%),
    radial-gradient(560px 340px at 46% 46%, rgba(99, 102, 241, 0.05), transparent 60%);
}

.landing-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1120px;
  width: 100%;
  margin: 0 auto;
  padding: 20px 24px;
}

.brand { display: flex; align-items: center; gap: 10px; }
.logo {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: linear-gradient(135deg, #3b82f6, #2563eb 55%, #1d4ed8);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: -0.02em;
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.32);
}
.brand-name { font-weight: 800; font-size: 17px; letter-spacing: -0.01em; }
.brand-tag {
  font-size: 12px;
  color: var(--muted);
  background: #f1f5f9;
  border: 1px solid var(--border);
  padding: 2px 9px;
  border-radius: 999px;
}

.top-links { display: flex; gap: 26px; }
.top-links a { color: var(--muted); font-weight: 500; font-size: 14px; }
.top-links a:hover { color: var(--text); }

.landing-body {
  flex: 1;
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 56px;
  align-items: center;
  max-width: 1120px;
  width: 100%;
  margin: 0 auto;
  padding: 16px 24px 40px;
}

.badge {
  display: inline-block;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12.5px;
  font-weight: 600;
  padding: 5px 13px;
  border-radius: 999px;
  border: 1px solid #dbe7ff;
}

.hero h1 {
  font-size: clamp(32px, 4.4vw, 50px);
  line-height: 1.18;
  letter-spacing: -0.02em;
  margin: 18px 0 16px;
}
.grad {
  background: linear-gradient(90deg, #2563eb, #0ea5e9);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.lead {
  font-size: 16.5px;
  color: var(--muted);
  line-height: 1.85;
  max-width: 480px;
  margin: 0 0 30px;
}

.features { list-style: none; margin: 0; padding: 0; display: grid; gap: 18px; }
.features li { display: flex; gap: 14px; align-items: flex-start; }
.f-icon {
  flex: none;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: var(--accent-soft);
  color: var(--accent);
  display: grid;
  place-items: center;
}
.f-icon svg { width: 19px; height: 19px; }
.f-text { display: flex; flex-direction: column; line-height: 1.5; }
.f-text strong { font-size: 14.5px; }
.f-text span { font-size: 13px; color: var(--muted); }

.langs { margin: 30px 0 0; font-size: 13px; color: var(--muted); letter-spacing: 0.02em; }

.login-side { display: flex; justify-content: center; }
.login-card {
  width: 100%;
  max-width: 400px;
  padding: 34px 32px 26px;
  border-radius: 16px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
  position: relative;
  overflow: hidden;
  margin-bottom: 0;
}
.login-card::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: linear-gradient(90deg, #2563eb, #0ea5e9);
}

.card-title { margin: 0 0 4px; font-size: 21px; }
.card-sub { margin: 0 0 22px; font-size: 13px; }

form { display: flex; flex-direction: column; gap: 16px; }
form label { display: flex; flex-direction: column; gap: 6px; }
form label span { font-size: 13px; font-weight: 600; }
form input { width: 100%; padding: 10px 13px; }

.submit {
  width: 100%;
  padding: 11px;
  font-size: 15px;
  margin-top: 6px;
  border-radius: 10px;
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  border-radius: 50%;
  display: inline-block;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.err-alert {
  margin-top: 14px;
  background: var(--danger-soft);
  color: var(--danger);
  border: 1px solid #fecaca;
  border-radius: 10px;
  padding: 10px 13px;
  font-size: 13.5px;
}

.foot-note {
  margin: 20px 0 0;
  padding-top: 15px;
  border-top: 1px dashed var(--border-strong);
  font-size: 12.5px;
  color: var(--muted);
  text-align: center;
}

.landing-foot {
  text-align: center;
  padding: 18px 20px 22px;
  color: var(--muted);
  font-size: 12.5px;
}

@media (max-width: 920px) {
  .landing-body { grid-template-columns: 1fr; gap: 36px; padding-top: 28px; }
  .top-links { display: none; }
  .lead { max-width: none; }
  .features { gap: 14px; }
}
</style>
