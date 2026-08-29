<template>
  <div class="login-wrap">
    <div class="card login-card">
      <h2>登录（开发/内测合成账号）</h2>
      <p class="muted">正式环境使用教务账号登录（Task 5 接入）</p>
      <form @submit.prevent="submit">
        <input v-model="loginName" placeholder="账号" autocomplete="username" />
        <input v-model="password" type="password" placeholder="密码" autocomplete="current-password" />
        <button type="submit" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
      </form>
      <p v-if="error" class="err">{{ error }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'

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
    router.push('/teacher/classes')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap { display: flex; justify-content: center; padding-top: 80px; }
.login-card { width: 360px; }
form { display: flex; flex-direction: column; gap: 12px; margin-top: 8px; }
.err { color: var(--danger); }
</style>
