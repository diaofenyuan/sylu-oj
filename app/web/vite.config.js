import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 语言语法包按需加载后的分包命名
 * -----------------------------------------------------------------------------
 * 三套 lezer 语法包由 ProblemWorkbench 动态 import，默认会产出难以辨认的
 * index-<hash>.js。这里给出语义化命名，便于排查与利用浏览器缓存。
 *
 * 注意：只对「语法包专属」的依赖分组（@lezer/cpp、@lezer/python、@lezer/java）。
 * @lezer/common、@lezer/lr、@lezer/highlight 被编辑器内核与各语言包共用，
 * 若强行归入某一语言分组，内核会反向依赖该分组，反而重新变成首屏必需。
 */
const LANG_CHUNKS = [
  { name: 'lang-cpp', match: ['@codemirror/lang-cpp', '@lezer/cpp'] },
  { name: 'lang-python', match: ['@codemirror/lang-python', '@lezer/python'] },
  { name: 'lang-java', match: ['@codemirror/lang-java', '@lezer/java'] }
]

export default defineConfig({
  plugins: [vue()],
  optimizeDeps: {
    /**
     * 预声明按需加载的语言语法包。
     * 它们由 ProblemWorkbench 动态 import，Vite 默认要到运行时才发现，
     * 于是会临时重新预构建并强制整页刷新 —— 表现为"首次访问页面被重载"，
     * 既影响开发体验，也会让 UI 回归脚本在首个页面偶发失败。
     * 预声明后服务器启动时即完成预构建，不再触发运行时重载；不影响生产构建分包。
     */
    include: ['@codemirror/lang-cpp', '@codemirror/lang-python', '@codemirror/lang-java']
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          const normalized = id.replace(/\\/g, '/')
          for (const { name, match } of LANG_CHUNKS) {
            if (match.some((m) => normalized.includes(`/${m}/`))) return name
          }
          return undefined
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
