/**
 * 前端 UI 冒烟验收
 * -----------------------------------------------------------------------------
 * 在不依赖后端的情况下，对全部路由做浏览器级回归。断言只针对**与实现无关的属性**，
 * 因此不随组件重构而失效：
 *   1. 页面渲染无控制台错误
 *   2. 主内容区确实渲染出内容（非空白）
 *   3. 图标全部正常渲染（空壳说明离线图标集合缺项）
 *   4. 窄屏无横向溢出
 *   5. 暗色主题在应用挂载前生效（无首屏闪白）
 *
 * 全程阻断一切外部请求，因此同时验证了「内网/离线可用」。
 *
 * 前置：启动开发服务器（默认 5178）。推荐直接执行 `npm run verify:frontend`，
 *       该命令会自动完成启停与断言。
 *
 * 输出：var/shots/*.png 截图；存在失败项时以非零码退出。
 */
import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5178'
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT = path.join(ROOT, 'var', 'shots')
fs.mkdirSync(OUT, { recursive: true })

// 仅允许本地请求；其余一律拦截，用于证明静态资源自包含
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost'])

// ---------------------------------------------------------------- 模拟数据

const CLASSES = [
  { teachingClassId: 1, name: '软件工程 2026 级 1 班', code: 'SE2026-01', role: 'PRIMARY' },
  { teachingClassId: 2, name: '软件工程 2026 级 2 班', code: 'SE2026-02', role: 'ASSISTANT' }
]

const PROBLEMS = [
  { id: 11, problemId: 11, code: 'A1001', title: '两数之和', languages: ['CPP', 'PYTHON'], status: 'PUBLISHED', version: 3, orderNum: 1, timeLimitMs: 10000, memoryLimitMb: 256, bestScore: 100, difficulty: 'EASY' },
  { id: 12, problemId: 12, code: 'A1002', title: '最长上升子序列', languages: ['CPP', 'JAVA'], status: 'PUBLISHED', version: 1, orderNum: 2, timeLimitMs: 10000, memoryLimitMb: 256, bestScore: 0, difficulty: 'MEDIUM' },
  { id: 13, problemId: 13, code: 'A1003', title: '最小生成树', languages: ['CPP'], status: 'DRAFT', version: 1, orderNum: 3, timeLimitMs: 10000, memoryLimitMb: 256, bestScore: 0, difficulty: 'HARD' }
]

const ASSIGNMENTS = [
  {
    id: 21, title: '第 3 章 线性表练习', mode: 'HOMEWORK', status: 'PUBLISHED',
    targets: [{ id: 1, teachingClassId: 1, publishAt: '2026-09-01T08:00', deadline: '2026-09-20T23:59', window: 'OPEN', maxSubmissions: 5, status: 'PUBLISHED' }]
  },
  {
    id: 22, title: '期中上机考试', mode: 'EXAM', status: 'PUBLISHED',
    targets: [{ id: 2, teachingClassId: 2, publishAt: '2026-09-08T09:00', deadline: '2026-09-08T11:00', window: 'OPEN', maxSubmissions: 1, status: 'PUBLISHED' }],
    approvals: [{ id: 7, action: 'CHANGE_TARGET_RULES', status: 'PENDING', reason: '延长考试时间 10 分钟' }]
  }
]

const STUDENT_ASSIGNMENTS = [
  { targetId: 1, title: '第 3 章 线性表练习', mode: 'HOMEWORK', publishAt: '2026-09-01T08:00', deadline: '2026-09-20T23:59', window: 'OPEN', attemptCount: 2, maxSubmissions: 5, courseName: '数据结构' },
  { targetId: 2, title: '期中上机考试', mode: 'EXAM', publishAt: '2026-09-08T09:00', deadline: '2026-09-08T11:00', window: 'CLOSED', attemptCount: 1, maxSubmissions: 1, courseName: '数据结构' }
]

const ANALYTICS_ROWS = [
  { studentId: 1, studentNo: '20260001', name: '张三', totalScore: 96, passRate: 96, submissionCount: 12, statusDistribution: { AC: 11, WA: 1 }, rank: 1 },
  { studentId: 2, studentNo: '20260002', name: '李四', totalScore: 88, passRate: 82, submissionCount: 15, statusDistribution: { AC: 9, WA: 4, TLE: 2 }, rank: 2 },
  { studentId: 3, studentNo: '20260003', name: '王五', totalScore: 74, passRate: 65, submissionCount: 21, statusDistribution: { AC: 7, WA: 9, CE: 5 }, rank: 3 },
  { studentId: 4, studentNo: '20260004', name: '赵六', totalScore: 62, passRate: 48, submissionCount: 24, statusDistribution: { AC: 5, WA: 12, RE: 7 }, rank: 4 }
]

function mockFor(url, method) {
  const p = new URL(url).pathname.replace(/^\/api/, '')
  if (p === '/identity/me') return { role: 'TEACHER', name: '陈老师' }
  if (p === '/teacher/classes') return CLASSES
  if (p === '/teacher/assignments') return ASSIGNMENTS
  if (p === '/teacher/problem-banks') return [{ id: 5, name: '默认题库' }]
  if (p === '/teacher/problems') return PROBLEMS
  if (/^\/teacher\/classes\/\d+\/problem-banks$/.test(p)) return [{ id: 5, name: '默认题库' }]
  if (p.startsWith('/teacher/analytics/targets/')) {
    return { rows: ANALYTICS_ROWS, classStatusDistribution: { AC: 32, WA: 26, TLE: 4, CE: 5, RE: 7 } }
  }
  if (p === '/teacher/exams/21/approvals' || p === '/teacher/exams/22/approvals') return []
  if (p === '/student/assignments') return STUDENT_ASSIGNMENTS
  if (p === '/student/practice/problems') return PROBLEMS
  if (/^\/student\/practice\/problems\/\d+$/.test(p)) {
    return {
      problemId: Number(p.split('/').pop()),
      code: 'A1002', title: '最长上升子序列', languages: ['CPP', 'PYTHON', 'JAVA'],
      description: '给定一个长度为 n 的整数序列，求其最长严格上升子序列的长度。\n\n输入\n第一行包含整数 n (1 ≤ n ≤ 1000)。第二行包含 n 个整数。\n\n输出\n输出一个整数，表示最长上升子序列的长度。',
      timeLimitMs: 10000, memoryLimitMb: 256, bestScore: 60, status: 'WA',
      samples: [
        { orderNum: 1, input: '6\n1 7 3 5 9 4', expectedOutput: '4' },
        { orderNum: 2, input: '3\n3 2 1', expectedOutput: '1' }
      ],
      testcases: []
    }
  }
  if (p === '/student/submissions') return []
  if (/^\/student\/targets\/\d+\/problems$/.test(p)) return PROBLEMS
  if (/^\/student\/problems\/\d+\/leaderboard/.test(p)) return []
  if (p === '/admin/terms') return [{ id: 1, code: '2026S1', name: '2026 春季学期', startDate: '2026-03-01', endDate: '2026-07-15', status: 'ACTIVE' }]
  if (p === '/admin/majors') return [{ id: 1, code: 'SE', name: '软件工程' }]
  if (p === '/admin/courses') return [{ id: 1, code: 'CS101', name: '程序设计基础', credit: 3 }]
  if (p === '/admin/teaching-classes') return [{ id: 1, code: 'SE2026-01', name: '软工 1 班', termId: 1, courseId: 1 }]
  if (p === '/admin/teachers') return [{ id: 1, staffNo: 'T2026001', name: '陈老师' }]
  if (p === '/admin/students') return [{ id: 1, studentNo: '20260001', name: '张三' }]
  if (p === '/admin/enrollments') return []
  if (p === '/admin/teacher-assignments') return []
  if (p === '/admin/accounts') return [{ id: 1, loginName: 'admin', role: 'ADMIN', status: 'ACTIVE', createdAt: '2026-08-01T10:00:00' }]
  if (p.startsWith('/admin/audit-events')) return [{ id: 1, createdAt: '2026-09-09T14:22:00', actorType: 'ADMIN', actorId: '1', action: 'CREATE_TERM', targetType: 'TERM', targetId: '1' }]
  return method === 'GET' ? [] : {}
}

// ---------------------------------------------------------------- 基础设施

/**
 * 安装路由拦截：放行本地资源，阻断一切外部请求。
 * @param {number} latencyMs 人为延迟 /api 响应，用于观察加载态与布局稳定性
 */
async function installRoutes(context, latencyMs = 0) {
  await context.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (!LOCAL_HOSTS.has(url.hostname)) return route.abort()
    if (url.pathname.startsWith('/api/')) {
      if (latencyMs > 0) await new Promise((r) => setTimeout(r, latencyMs))
      const body = mockFor(request.url(), request.method())
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body ?? {}) })
    }
    return route.continue()
  })
}

async function makeContext(browser, { width = 1440, height = 900, theme = 'dark', role = 'TEACHER', noAuth = false }) {
  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: 2,
    colorScheme: theme === 'dark' ? 'dark' : 'light'
  })
  await context.addInitScript((payload) => {
    try {
      if (!payload.noAuth) {
        localStorage.setItem('oj_token', 'mock-token')
        localStorage.setItem('oj_role', payload.role)
      }
      localStorage.setItem('oj-theme', payload.theme)
    } catch {}
  }, { role, noAuth, theme })
  return context
}

const assertions = []

function record(name, ok, detail = '') {
  assertions.push({ name, ok, detail })
  console.log(`  ${ok ? '✓' : '✗'} ${name}${ok ? '' : ` — ${detail}`}`)
}

/** 单个检查项抛异常时记为失败并继续，避免一次超时让整个套件崩溃、丢失其余结论 */
async function guard(label, fn) {
  try {
    return await fn()
  } catch (e) {
    record(`${label} · 执行异常`, false, String(e?.message || e).split('\n')[0].slice(0, 200))
    return null
  }
}

// ---------------------------------------------------------------- 页面检查

async function smokePage(browser, name, { path: routePath, role = 'TEACHER', width = 1440, height = 900, theme = 'dark', noAuth = false, waitMs = 1400 }) {
  const context = await makeContext(browser, { width, height, theme, role, noAuth })
  await installRoutes(context)

  const errors = []
  const page = await context.newPage()
  page.on('pageerror', (e) => errors.push(String(e)))
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

  await page.goto(`${BASE}${routePath}`, { waitUntil: 'load' })
  await page.waitForTimeout(waitMs)

  const metrics = await page.evaluate(() => {
    const main = document.querySelector('main')
    const svgs = Array.from(document.querySelectorAll('svg.iconify'))
    return {
      mainHeight: Math.round(main?.getBoundingClientRect().height ?? 0),
      mainTextLength: (main?.textContent ?? '').trim().length,
      iconsTotal: svgs.length,
      iconsEmpty: svgs.filter((el) => el.children.length === 0).length,
      overflowX: document.documentElement.scrollWidth - window.innerWidth
    }
  })

  await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true })
  await context.close()

  record(`${name} · 无控制台错误`, errors.length === 0, errors.slice(0, 2).join(' | '))
  record(`${name} · 主内容已渲染`, metrics.mainHeight > 100 && metrics.mainTextLength > 10,
    `高度=${metrics.mainHeight} 文本长度=${metrics.mainTextLength}`)
  record(`${name} · 图标无空壳`, metrics.iconsEmpty === 0,
    `${metrics.iconsEmpty}/${metrics.iconsTotal} 为空`)

  return { name, path: routePath, ...metrics, errors: errors.slice(0, 3) }
}

/** 窄屏横向溢出：常见的响应式缺陷，且与实现无关 */
async function checkMobileOverflow(browser) {
  for (const [label, routePath, role] of [['student-home', '/student', 'STUDENT'], ['teacher-classes', '/teacher/classes', 'TEACHER']]) {
    const context = await makeContext(browser, { width: 390, height: 844, role })
    await installRoutes(context)
    const page = await context.newPage()
    await page.goto(`${BASE}${routePath}`, { waitUntil: 'load' })
    await page.waitForTimeout(1200)
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    await page.screenshot({ path: path.join(OUT, `mobile-${label}.png`), fullPage: true })
    await context.close()
    record(`窄屏 · ${label} 无横向溢出`, overflow <= 1, `溢出 ${overflow}px`)
  }
}

/**
 * 首帧主题：用 MutationObserver 捕获 <html> 首次写入 class 的时刻，
 * 若此刻已是 dark 且 #app 仍为空，说明主题由 index.html 的同步内联脚本设置。
 * （不能用 DOMContentLoaded 观测：module 脚本先于该事件执行，届时应用已挂载。）
 */
async function checkThemeBeforeMount(browser) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark' })
  await context.addInitScript(() => {
    const probe = { attached: false, hasDarkAtFirstWrite: null, appMountedAtFirstWrite: null }
    window.__themeProbe = probe
    try {
      new MutationObserver((records) => {
        for (const rec of records) {
          if (rec.attributeName !== 'class') continue
          if (!rec.target || rec.target.nodeType !== 1 || rec.target.tagName !== 'HTML') continue
          if (probe.hasDarkAtFirstWrite === null) {
            probe.hasDarkAtFirstWrite = document.documentElement.classList.contains('dark')
            probe.appMountedAtFirstWrite = (document.querySelector('#app')?.children.length ?? 0) > 0
          }
        }
      }).observe(document, { attributes: true, subtree: true, attributeFilter: ['class'] })
      probe.attached = true
    } catch (e) {
      probe.error = String(e)
    }
  })
  await context.addInitScript(() => {
    try { localStorage.setItem('oj-theme', 'dark') } catch {}
  })
  await installRoutes(context)

  const page = await context.newPage()
  await page.goto(`${BASE}/login`, { waitUntil: 'load' })
  await page.waitForTimeout(300)
  const probe = await page.evaluate(() => window.__themeProbe)
  await context.close()

  const ok = probe.attached && probe.hasDarkAtFirstWrite === true && probe.appMountedAtFirstWrite === false
  record('主题 · 挂载前已应用（无闪白）', ok,
    `attached=${probe.attached} 首写时 dark=${probe.hasDarkAtFirstWrite} appMounted=${probe.appMountedAtFirstWrite}`)
}

// ---------------------------------------------------------------- 执行

// --disable-dev-shm-usage：容器/CI 中 /dev/shm 通常仅 64MB，连续创建上下文并截整页图易导致 Chromium 崩溃
const browser = await chromium.launch({ args: ['--disable-dev-shm-usage'] })

// 预热：Vite 首次访问可能因依赖预构建而整页刷新，先空跑一次避免污染首个用例
{
  const warm = await makeContext(browser, { noAuth: true })
  await installRoutes(warm)
  const warmPage = await warm.newPage()
  await warmPage.goto(`${BASE}/login`, { waitUntil: 'load' })
  await warmPage.waitForTimeout(1200)
  await warm.close()
}

const PAGE_CASES = [
  ['login-desktop', { path: '/login', noAuth: true }],
  ['login-mobile', { path: '/login', noAuth: true, width: 390, height: 844 }],
  ['login-light', { path: '/login', noAuth: true, theme: 'light' }],
  ['student-home', { path: '/student', role: 'STUDENT' }],
  ['student-practice', { path: '/student/practice', role: 'STUDENT', waitMs: 2600 }],
  ['student-contest', { path: '/student/contest/1', role: 'STUDENT' }],
  ['teacher-classes', { path: '/teacher/classes' }],
  ['teacher-problem-bank', { path: '/teacher/classes/1/problems' }],
  ['teacher-assignments', { path: '/teacher/assignments' }],
  ['teacher-analytics', { path: '/teacher/analytics/1' }],
  ['teacher-analytics-classic', { path: '/teacher/analytics/1/classic' }],
  ['teacher-assignment-editor', { path: '/teacher/assignment' }],
  ['admin', { path: '/admin', role: 'ADMIN' }]
]

console.log('· 页面冒烟（外部请求全部阻断）')
const results = []
for (const [name, options] of PAGE_CASES) {
  const result = await guard(name, () => smokePage(browser, name, options))
  if (result) results.push(result)
}

console.log('· 窄屏适配')
await guard('窄屏', () => checkMobileOverflow(browser))

console.log('· 首帧主题')
await guard('主题', () => checkThemeBeforeMount(browser))

await browser.close()

// 汇总：图标离线集合的覆盖度（空壳为 0 且总量合理，说明集合完整）
const totalIcons = results.reduce((sum, r) => sum + r.iconsTotal, 0)
record('全局 · 图标渲染总量', totalIcons >= 40, `共渲染 ${totalIcons} 个图标，低于预期`)

const failed = assertions.filter((a) => !a.ok)
console.log('\n====== 结果 ======')
console.log(`断言通过 ${assertions.length - failed.length}/${assertions.length}`)
if (failed.length) {
  console.log('失败项：')
  for (const f of failed) console.log(`  - ${f.name}: ${f.detail}`)
}
if (process.env.VERBOSE) console.log(JSON.stringify(results, null, 2))

process.exit(failed.length ? 1 : 0)
