const BASE = '/api'
const TOKEN_KEY = 'oj_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export async function api(path, { method = 'GET', body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const t = token || getToken()
  if (t) headers['Authorization'] = 'Bearer ' + t
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  })
  if (res.status === 401) {
    clearToken()
    throw new Error('未认证或登录已过期')
  }
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  if (!res.ok) {
    const msg = data && data.message ? data.message : ('请求失败 ' + res.status)
    throw new Error(msg)
  }
  return data
}

export async function login(loginName, password) {
  const data = await api('/auth/login', { method: 'POST', body: { loginName, password }, token: null })
  setToken(data.token)
  return data
}
