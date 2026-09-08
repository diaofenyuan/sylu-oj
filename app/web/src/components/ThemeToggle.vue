<template>
  <button type="button" class="theme-toggle secondary" :title="label" :aria-label="label" @click="toggleTheme">
    <Icon :icon="icons[theme]" aria-hidden="true" />
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { useTheme } from '../composables/useTheme'

const { theme, toggleTheme } = useTheme()
const icons = { light: 'mdi:white-balance-sunny', dark: 'mdi:weather-night', auto: 'mdi:theme-light-dark' }
const names = { light: '浅色', dark: '深色', auto: '跟随系统' }
const label = computed(() => `当前${names[theme.value]}，切换为${names[theme.value === 'light' ? 'dark' : theme.value === 'dark' ? 'auto' : 'light']}`)
</script>

<style scoped>
.theme-toggle { width: 40px; height: 40px; padding: 0; flex-shrink: 0; }
@media (max-width: 768px) { .theme-toggle { width: 44px; height: 44px; } }
</style>
