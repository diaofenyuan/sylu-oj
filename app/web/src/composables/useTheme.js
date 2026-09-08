import { ref, onMounted, onBeforeUnmount } from 'vue'

// 所有入口共用主题状态，避免登录页和工作区切换后图标与实际主题不同步。
const saved = localStorage.getItem('oj-theme')
const theme = ref(['light', 'dark', 'auto'].includes(saved) ? saved : 'auto')
const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')

function applyTheme() {
  document.documentElement.classList.toggle('dark', theme.value === 'dark' || (theme.value === 'auto' && mediaQuery.matches))
}

function setTheme(value) {
  if (!['light', 'dark', 'auto'].includes(value)) return
  theme.value = value
  localStorage.setItem('oj-theme', value)
  applyTheme()
}

export function useTheme() {
  const onSystemChange = () => { if (theme.value === 'auto') applyTheme() }
  applyTheme()
  onMounted(() => mediaQuery.addEventListener('change', onSystemChange))
  onBeforeUnmount(() => mediaQuery.removeEventListener('change', onSystemChange))
  return {
    theme, applyTheme, setTheme,
    toggleTheme: () => setTheme(theme.value === 'light' ? 'dark' : theme.value === 'dark' ? 'auto' : 'light')
  }
}
