import { api, getToken, clearToken } from './api'

const ROLE_KEY = 'oj_role'

export function getRole() {
  return localStorage.getItem(ROLE_KEY) || ''
}

export function setRole(role) {
  localStorage.setItem(ROLE_KEY, role || '')
}

export function clearRole() {
  localStorage.removeItem(ROLE_KEY)
}

export async function refreshRole() {
  const profile = await api('/identity/me')
  setRole(profile.role)
  return profile.role
}

export function clearSession() {
  clearToken()
  clearRole()
}
