import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/api',
  timeout: 60000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.OJ_API_BASE_URL || 'http://localhost:8080'
  }
})
