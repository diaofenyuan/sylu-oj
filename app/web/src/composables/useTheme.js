import { ref, watch } from 'vue'

export function useTheme() {
  const theme = ref(localStorage.getItem('oj-theme') || 'auto')
  
  const applyTheme = () => {
    const root = document.documentElement
    
    if (theme.value === 'dark') {
      root.classList.add('dark')
    } else if (theme.value === 'light') {
      root.classList.remove('dark')
    } else {
      // auto 模式：跟随系统
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
      if (prefersDark) {
        root.classList.add('dark')
      } else {
        root.classList.remove('dark')
      }
    }
  }
  
  const setTheme = (newTheme) => {
    theme.value = newTheme
    localStorage.setItem('oj-theme', newTheme)
    applyTheme()
  }
  
  const toggleTheme = () => {
    const next = theme.value === 'light' ? 'dark' : theme.value === 'dark' ? 'auto' : 'light'
    setTheme(next)
  }
  
  // 监听系统主题变化（auto模式下）
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  mediaQuery.addEventListener('change', () => {
    if (theme.value === 'auto') {
      applyTheme()
    }
  })
  
  return {
    theme,
    setTheme,
    toggleTheme,
    applyTheme
  }
}
