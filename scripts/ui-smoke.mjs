/**
 * 前端 UI 冒烟验收脚本
 * -----------------------------------------------------------------------------
 * 用途：在不依赖后端的情况下，对关键页面做回归检查，覆盖：
 *   1. 页面渲染无控制台错误
 *   2. 入场动效（v-reveal）全部生效
 *   3. 图标完全离线渲染（默认拦截一切外部请求，模拟内网/离线环境）
 *   4. 加载失败时呈现可重试的错误态，而非误导性的空态
 *   5. 暗色主题在应用挂载前即已应用（无首屏闪白）
 *
 * 前置：
 *   1. 启动前端开发服务器（默认 5178）：npm --prefix app/web run dev -- --port 5178
 *   2. 使用本仓库已安装的 Playwright 与 Chromium（勿另装，避免版本不匹配）
 *
 * 运行：
 *   node scripts/ui-smoke.mjs
 *   可通过环境变量 BASE_URL 覆盖目标地址。
 *
 * 输出：var/shots/*.png 截图 + 控制台结果；存在失败项时以非零码退出。
 */
import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5178'
// 截图固定输出到仓库根目录的 var/shots，避免受调用时工作目录影响
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT = path.join(ROOT, 'var', 'shots')
fs.mkdirSync(OUT, { recursive: true })

// 仅允许本地请求；其余一律拦截，用于证明图标等资源不依赖外部 CDN
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost'])

// ---------------------------------------------------------------- mock 数据

const CLASSES = [
  { teachingClassId: 1, name: '软件工程 2026 级 1 班', code: 'SE2026-01', role: 'PRIMARY' },
  { teachingClassId: 2, name: '软件工程 2026 级 2 班', code: 'SE2026-02', role: 'ASSISTANT' },
  { teachingClassId: 3, name: '计算机科学与技术 3 班', code: 'CS2026-03', role: 'PRIMARY' }
]

const PROBLEMS = [
  { id: 11, code: 'A1001', title: '两数之和', languages: ['CPP', 'PYTHON'], status: 'PUBLISHED', version: 3 },
  { id: 12, code: 'A1002', title: '最长上升子序列', languages: ['CPP', 'JAVA'], status: 'PUBLISHED', version: 1 },
  { id: 13, code: 'A1003', title: '最小生成树', languages: ['CPP'], status: 'DRAFT', version: 1 }
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
  { targetId: 2, title: '第 3 章 线性表练习', mode: 'HOMEWORK', publishAt: '2026-09-01T08:00', deadline: '2026-09-20T23:59', window: 'OPEN', attemptCount: 2, maxSubmissions: 5 },
  { targetId: 3, title: '期中上机考试', mode: 'EXAM', publishAt: '2026-09-08T09:00', deadline: '2026-09-08T11:00', window: 'CLOSED', attemptCount: 1, maxSubmissions: 1 }
]

const ANALYTICS_ROWS = [
  { studentId: 1, studentNo: '20260001', name: '张三', totalScore: 96, passRate: 96, submissionCount: 12, statusDistribution: { AC: 11, WA: 1 }, rank: 1 },
  { studentId: 2, studentNo: '20260002', name: '李四', totalScore: 88, passRate: 82, submissionCount: 15, statusDistribution: { AC: 9, WA: 4, TLE: 2 }, rank: 2 },
  { studentId: 3, studentNo: '20260003', name: '王五', totalScore: 74, passRate: 65, submissionCount: 21, statusDistribution: { AC: 7, WA: 9, CE: 5 }, rank: 3 },
  { studentId: 4, studentNo: '20260004', name: '赵六', totalScore: 62, passRate: 48, submissionCount: 24, statusDistribution: { AC: 5, WA: 12, RE: 7 }, rank: 4 }
]

function mockFor(url, method) {
  const p = new URL(url).pathname.replace(/^\/api/, '')
  if (p === '/identity/me') return { role: 'TEACHER' }
  if (p === '/teacher/classes') return CLASSES
  if (p === '/teacher/assignments') return ASSIGNMENTS
  if (p === '/teacher/problem-banks') return [{ id: 5, name: '默认题库' }]
  if (p === '/teacher/problems') return PROBLEMS
  if (p.startsWith('/teacher/analytics/targets/')) {
    return { rows: ANALYTICS_ROWS, classStatusDistribution: { AC: 32, WA: 26, TLE: 4, CE: 5, RE: 7 } }
  }
  if (p === '/student/assignments') return STUDENT_ASSIGNMENTS
  if (p === '/student/practice/problems') {
    return PROBLEMS.map((x, i) => ({ ...x, problemId: x.id, orderNum: i + 1, bestScore: i === 0 ? 100 : 0, status: i === 0 ? 'AC' : 'UNATTEMPTED' }))
  }
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
  if (/^\/student\/targets\/\d+\/problems$/.test(p)) {
    return PROBLEMS.map((x, i) => ({ ...x, problemId: x.id, orderNum: i + 1, timeLimitMs: 10000, memoryLimitMb: 256 }))
  }
  if (p === '/admin/terms') return [{ id: 1, code: '2026S1', name: '2026 春季学期', startDate: '2026-03-01', endDate: '2026-07-15', status: 'ACTIVE' }]
  if (p === '/admin/majors') return [{ id: 1, code: 'SE', name: '软件工程' }]
  if (p === '/admin/courses') return [{ id: 1, code: 'CS101', name: '程序设计基础', credit: 3 }]
  if (p === '/admin/teaching-classes') return [{ id: 1, code: 'SE2026-01', name: '软工 1 班', termId: 1, courseId: 1 }]
  if (p === '/admin/teachers') return [{ id: 1, staffNo: 'T2026001', name: '陈老师' }]
  if (p === '/admin/students') return [{ id: 1, studentNo: '20260001', name: '张三' }]
  if (p === '/admin/accounts') return [{ id: 1, loginName: 'admin', role: 'ADMIN', status: 'ACTIVE', createdAt: '2026-08-01T10:00:00' }]
  if (p.startsWith('/admin/audit-events')) return [{ id: 1, createdAt: '2026-09-09T14:22:00', actorType: 'ADMIN', actorId: '1', action: 'CREATE_TERM', targetType: 'TERM', targetId: '1' }]
  return method === 'GET' ? [] : {}
}

// ---------------------------------------------------------------- 工具函数

/**
 * 安装统一的路由拦截：放行本地资源，阻断一切外部请求。
 * 由此可在离线/内网条件下验证图标等静态资源是否自包含。
 * @param {object} context Playwright BrowserContext
 * @param {(url: string, method: string) => any} [responder] 自定义 /api 响应；
 *   返回 `{ __http: { status, body } }` 可指定状态码，否则按 200 返回该值。
 *   注意不要把领域字段 `status` 当作状态码 —— 业务数据里大量存在该字段。
 * @param {number} [latencyMs] 人为延迟 /api 响应，用于观察加载态（骨架屏）与数据到达时的布局偏移
 */
async function installRoutes(context, responder = mockFor, latencyMs = 0) {
  await context.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())

    if (!LOCAL_HOSTS.has(url.hostname)) {
      // 外部请求一律阻断：命中即说明存在未被离线化的资源依赖
      return route.abort()
    }
    if (url.pathname.startsWith('/api/')) {
      if (latencyMs > 0) await new Promise((r) => setTimeout(r, latencyMs))
      const result = responder(request.url(), request.method())
      const custom = result && typeof result === 'object' && result.__http
      const status = custom ? (result.__http.status ?? 200) : 200
      const body = custom ? result.__http.body : result
      return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body ?? {}) })
    }
    return route.continue()
  })
}

async function makeContext(browser, { width, height, theme, role, noAuth }) {
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

/**
 * 滚动整页，让折叠区之外的 v-reveal 元素也有机会进入视口。
 * 不做这一步会把"尚未滚动到"误判为"入场动效失效"。
 */
async function settleReveals(page) {
  await page.evaluate(async () => {
    const step = Math.max(240, Math.floor(window.innerHeight * 0.8))
    for (let y = 0; y < document.documentElement.scrollHeight; y += step) {
      window.scrollTo(0, y)
      await new Promise((r) => setTimeout(r, 60))
    }
    window.scrollTo(0, document.documentElement.scrollHeight)
  })
  await page.waitForTimeout(700)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.waitForTimeout(150)
}

/** 页面冒烟：渲染无错、入场动效、图标离线渲染 */
async function smokePage(browser, name, { path: routePath, role = 'TEACHER', width = 1440, height = 900, theme = 'dark', waitMs = 1500, noAuth = false }) {
  const context = await makeContext(browser, { width, height, theme, role, noAuth })
  await installRoutes(context)

  const errors = []
  const page = await context.newPage()
  page.on('pageerror', (e) => errors.push(String(e)))
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

  await page.goto(`${BASE}${routePath}`, { waitUntil: 'load' })
  await page.waitForTimeout(waitMs)
  await settleReveals(page)

  const metrics = await page.evaluate(() => {
    const reveal = Array.from(document.querySelectorAll('.reveal'))
    const svgs = Array.from(document.querySelectorAll('svg.iconify'))
    return {
      revealTotal: reveal.length,
      revealHidden: reveal.filter((el) => !el.classList.contains('is-visible')).length,
      iconsTotal: svgs.length,
      iconsEmpty: svgs.filter((el) => el.children.length === 0).length
    }
  })

  await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true })
  await context.close()

  record(`${name} · 无控制台错误`, errors.length === 0, errors.slice(0, 2).join(' | '))
  record(`${name} · 入场动效生效`, metrics.revealHidden === 0, `${metrics.revealHidden}/${metrics.revealTotal} 未显示`)
  // 登录页不含图标组件，此处只校验"已渲染的图标没有空壳"，
  // 全局图标覆盖度由下方的汇总断言保证
  record(`${name} · 无空图标`, metrics.iconsEmpty === 0,
    `${metrics.iconsEmpty}/${metrics.iconsTotal} 为空`)

  return { name, path: routePath, ...metrics, errors: errors.slice(0, 3) }
}

/**
 * 加载稳定性（CLS）
 * -----------------------------------------------------------------------------
 * 必须给接口加人为延迟，否则骨架屏来不及渲染就已被真实内容替换，测出的 CLS 恒为 0，
 * 结论没有意义。这里延迟 400ms，模拟校内网/真实后端的响应时间。
 *
 * 同时校验「加载期间确实出现了骨架屏」—— 否则 CLS 好看只是因为压根没有加载态。
 */
async function checkLoadStability(browser) {
  const API_LATENCY = 400
  const CLS_LIMIT = 0.1
  // 加载态与完成态的内容高度比上限。实测各页在 1.00~1.35 之间，
  // 取 1.5 留出余量，同时能拦住"骨架体量明显不符"的回归（曾达 2.28）。
  const HEIGHT_RATIO_LIMIT = 1.5

  const pages = [
    { name: 'student-home', path: '/student', role: 'STUDENT' },
    { name: 'teacher-classes', path: '/teacher/classes', role: 'TEACHER' },
    { name: 'teacher-analytics', path: '/teacher/analytics/1', role: 'TEACHER' },
    { name: 'teacher-analytics-classic', path: '/teacher/analytics/1/classic', role: 'TEACHER' },
    { name: 'teacher-problem-bank', path: '/teacher/classes/1/problems', role: 'TEACHER' },
    { name: 'teacher-assignments', path: '/teacher/assignments', role: 'TEACHER' },
    { name: 'admin-org', path: '/admin', role: 'ADMIN' }
  ]

  const measured = []

  for (const { name, path: routePath, role } of pages) {
    const context = await makeContext(browser, { width: 1440, height: 900, theme: 'dark', role })
    await installRoutes(context, mockFor, API_LATENCY)

    const page = await context.newPage()
    await page.addInitScript(() => {
      window.__cls = 0
      try {
        new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if (!entry.hadRecentInput) window.__cls += entry.value
          }
        }).observe({ type: 'layout-shift', buffered: true })
      } catch {
        window.__cls = null
      }
    })

    await page.goto(`${BASE}${routePath}`, { waitUntil: 'load' })

    // 数据到达前应能看到骨架屏，同时记录此时的内容高度
    let skeletonSeen = 0
    let skeletonHeight = 0
    try {
      await page.waitForSelector('.skeleton', { state: 'visible', timeout: 1500 })
      skeletonSeen = await page.locator('.skeleton').count()
      // 用内容容器而非 documentElement.scrollHeight：后者会被视口高度兜底，
      // 内容不足一屏时恒等于视口高，无法反映骨架屏的真实体量
      skeletonHeight = await page.evaluate(() =>
        Math.round(document.querySelector('main')?.getBoundingClientRect().height ?? 0))
      // 在骨架可见时截图，作为加载态的实际留档
      await page.screenshot({ path: path.join(OUT, `loading-${name}.png`), fullPage: true })
    } catch {
      skeletonSeen = 0
    }

    // 等待数据渲染完成并稳定，再读取 CLS 与最终内容高度
    await page.waitForSelector('.skeleton', { state: 'detached', timeout: 5000 }).catch(() => {})
    await page.waitForTimeout(800)

    const { cls, finalHeight } = await page.evaluate(() => ({
      cls: window.__cls,
      finalHeight: Math.round(document.querySelector('main')?.getBoundingClientRect().height ?? 0)
    }))

    await context.close()

    record(`加载态 · ${name} 期间有骨架屏`, skeletonSeen > 0, `骨架元素数=${skeletonSeen}`)
    record(`加载态 · ${name} 数据到达无跳动`, cls !== null && cls < CLS_LIMIT,
      `CLS=${cls === null ? '未采集' : Number(cls.toFixed(4))}（阈值 ${CLS_LIMIT}）`)

    // 页面高度比：骨架屏与真实内容体量越接近，加载完成时页面越长越不明显。
    // CLS 只能捕捉"已有元素被推动"，而加载态下方没有元素时恒为 0，故用高度比补充。
    const heightRatio = skeletonHeight > 0 && finalHeight > 0
      ? Math.max(skeletonHeight, finalHeight) / Math.min(skeletonHeight, finalHeight)
      : null
    record(`加载态 · ${name} 骨架体量接近内容`, heightRatio !== null && heightRatio <= HEIGHT_RATIO_LIMIT,
      `加载高=${skeletonHeight} 完成高=${finalHeight} 比值=${heightRatio === null ? '—' : heightRatio.toFixed(2)}`)

    measured.push({
      name,
      cls: cls === null ? null : Number(cls.toFixed(4)),
      heightRatio: heightRatio === null ? null : Number(heightRatio.toFixed(2))
    })
  }

  // 打印实测值：断言只反映"是否达标"，数值变化才能反映改进幅度
  console.log('  CLS 实测：' + measured.map((m) => `${m.name}=${m.cls ?? '—'}`).join('  '))
  console.log('  高度比实测：' + measured.map((m) => `${m.name}=${m.heightRatio ?? '—'}`).join('  '))
}

/**
 * 验证加载失败时呈现可重试的错误态，而非误导性的空态。
 * 首次 /student/assignments 返回 500，重试后返回正常数据。
 */
async function checkErrorRetry(browser) {
  const context = await makeContext(browser, { width: 1280, height: 800, theme: 'dark', role: 'STUDENT' })
  let calls = 0
  await installRoutes(context, (url, method) => {
    if (new URL(url).pathname === '/api/student/assignments') {
      calls += 1
      // 首次失败，重试成功后应恢复内容
      if (calls === 1) return { __http: { status: 500, body: { message: '服务暂时不可用' } } }
      return STUDENT_ASSIGNMENTS
    }
    return mockFor(url, method)
  })

  const page = await context.newPage()
  await page.goto(`${BASE}/student`, { waitUntil: 'load' })
  // 等待三种终态之一出现，避免固定等待在负载较高时过早断言
  await page.waitForSelector('.error-state, .empty-state, .card', { timeout: 8000 }).catch(() => {})
  // 再等入场动画结束，使截图稳定（ErrorState 本身带淡入位移动画）
  await page.waitForTimeout(700)

  const errorCount = await page.locator('.error-state').count()
  const emptyCount = await page.locator('.empty-state').count()
  record('失败态 · 展示错误而非空态', errorCount === 1 && emptyCount === 0,
    `error=${errorCount} empty=${emptyCount}`)
  await page.screenshot({ path: path.join(OUT, 'state-error-retry.png'), fullPage: true })

  if (errorCount === 1) {
    await page.locator('.error-state button').click()
    await page.waitForTimeout(900)
    const cards = await page.locator('.card').count()
    const stillError = await page.locator('.error-state').count()
    record('失败态 · 重试后恢复内容', cards > 0 && stillError === 0, `card=${cards} error=${stillError}`)
    await page.screenshot({ path: path.join(OUT, 'state-error-recovered.png'), fullPage: true })
  } else {
    record('失败态 · 重试后恢复内容', false, '未出现错误态，无法验证重试')
  }

  await context.close()
}

/**
 * 验证暗色主题在应用挂载前就已应用（消除首屏闪白）。
 *
 * 观测点说明：`<script type="module">` 默认为 defer 且先于 DOMContentLoaded 执行，
 * 因此"DOMContentLoaded 时应用尚未挂载"不成立。这里改用 MutationObserver 捕获
 * <html> 的 class 首次被写入的瞬间：若此刻已是 dark 且 #app 仍为空，
 * 即可证明主题由 index.html 的同步内联脚本设置，而非等待 Vue 挂载后才生效。
 */
async function checkThemeBeforeMount(browser) {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    colorScheme: 'dark'
  })

  // 先注册探针，必须早于页面自身的任何脚本。
  // 注意：addInitScript 执行时 documentElement 可能尚未创建，
  // 因此观察 document 根节点并用 subtree 捕获 <html> 的 class 写入。
  await context.addInitScript(() => {
    const probe = { attached: false, mutations: 0, hasDarkAtFirstWrite: null, appMountedAtFirstWrite: null }
    window.__themeProbe = probe
    try {
      const observer = new MutationObserver((records) => {
        for (const rec of records) {
          // 只关心 <html> 自身的 class 变化
          if (rec.attributeName !== 'class') continue
          if (!rec.target || rec.target.nodeType !== 1 || rec.target.tagName !== 'HTML') continue
          if (probe.mutations === 0) {
            probe.hasDarkAtFirstWrite = document.documentElement.classList.contains('dark')
            probe.appMountedAtFirstWrite = (document.querySelector('#app')?.children.length ?? 0) > 0
          }
          probe.mutations += 1
        }
      })
      observer.observe(document, { attributes: true, subtree: true, attributeFilter: ['class'] })
      probe.attached = true
    } catch (e) {
      probe.error = String(e)
    }
  })

  // 写入暗色偏好
  await context.addInitScript(() => {
    try {
      localStorage.setItem('oj-token', 'mock')
      localStorage.setItem('oj-theme', 'dark')
    } catch {}
  })

  await installRoutes(context)

  const page = await context.newPage()
  await page.goto(`${BASE}/login`, { waitUntil: 'load' })
  await page.waitForTimeout(300)

  const probe = await page.evaluate(() => window.__themeProbe)
  const ok = probe.hasDarkAtFirstWrite === true && probe.appMountedAtFirstWrite === false
  record('主题 · 挂载前已应用（无闪白）', ok,
    `首次写入 class 时 dark=${probe.hasDarkAtFirstWrite} appMounted=${probe.appMountedAtFirstWrite}`)

  await context.close()
}

/**
 * 验证统一通知链路：触发表单校验，确认 Toast 呈现且语义属性正确。
 * 同时覆盖"表单校验走就近/轻量提示"的既有约定。
 */
async function checkToast(browser) {
  const context = await makeContext(browser, { width: 1280, height: 860, theme: 'dark', role: 'TEACHER' })
  await installRoutes(context)

  const page = await context.newPage()
  await page.goto(`${BASE}/teacher/classes/1/problems`, { waitUntil: 'load' })
  await page.waitForTimeout(900)

  // 题号与题名留空直接提交，应触发校验提示
  await page.locator('.card button', { hasText: '创建题目' }).first().click()
  await page.waitForSelector('.toast', { timeout: 5000 }).catch(() => {})

  const toast = await page.locator('.toast').first()
  const count = await page.locator('.toast').count()
  const role = count ? await toast.getAttribute('role') : null
  const live = count ? await toast.getAttribute('aria-live') : null

  record('通知 · 校验提示可见', count >= 1, `toast=${count}`)
  record('通知 · 具备读屏语义', role === 'status' && live === 'polite', `role=${role} aria-live=${live}`)

  await page.screenshot({ path: path.join(OUT, 'state-toast.png'), fullPage: false })

  await context.close()
}

/**
 * 验证弹层的可访问性基座：对话框语义、焦点移入与循环、Esc 关闭、焦点归还、滚动锁定。
 * 使用工作台的「题目列表」抽屉作为被测对象。
 */
async function checkOverlayA11y(browser) {
  const context = await makeContext(browser, { width: 1440, height: 900, theme: 'dark', role: 'STUDENT' })
  await installRoutes(context)

  const page = await context.newPage()
  await page.goto(`${BASE}/student/practice`, { waitUntil: 'load' })
  await page.waitForTimeout(1600)

  const trigger = page.locator('.wb-topbar button', { hasText: '题目列表' }).first()
  await trigger.click()
  await page.waitForSelector('.overlay-panel[role="dialog"]', { timeout: 5000 }).catch(() => {})

  const opened = await page.evaluate(() => {
    const panel = document.querySelector('.overlay-panel[role="dialog"]')
    if (!panel) return { present: false }
    return {
      present: true,
      ariaModal: panel.getAttribute('aria-modal'),
      label: panel.getAttribute('aria-label'),
      focusInside: panel.contains(document.activeElement),
      bodyOverflow: getComputedStyle(document.body).overflow
    }
  })

  record('弹层 · 具备对话框语义', opened.present && opened.ariaModal === 'true' && !!opened.label,
    `present=${opened.present} aria-modal=${opened.ariaModal} label=${opened.label}`)
  record('弹层 · 打开后焦点移入', opened.focusInside === true, `focusInside=${opened.focusInside}`)
  record('弹层 · 打开时锁定页面滚动', opened.bodyOverflow === 'hidden', `overflow=${opened.bodyOverflow}`)

  // 焦点循环：聚焦最后一个可聚焦元素后按 Tab，应回到第一个
  const trapInfo = await page.evaluate(() => {
    const selector = 'a[href],button:not([disabled]),input:not([disabled]):not([type="hidden"]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'
    const panel = document.querySelector('.overlay-panel[role="dialog"]')
    const items = Array.from(panel.querySelectorAll(selector)).filter((el) => el.getClientRects().length > 0)
    items.forEach((el, i) => el.setAttribute('data-trap-idx', String(i)))
    if (items.length) items[items.length - 1].focus()
    return { count: items.length }
  })
  await page.keyboard.press('Tab')
  const afterTab = await page.evaluate(() => document.activeElement?.getAttribute('data-trap-idx'))
  record('弹层 · Tab 焦点循环不逃逸', trapInfo.count > 0 && afterTab === '0',
    `共 ${trapInfo.count} 个可聚焦项，末项 Tab 后落在 idx=${afterTab}`)

  await page.screenshot({ path: path.join(OUT, 'a11y-overlay-open.png'), fullPage: false })

  // Esc 关闭并归还焦点
  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)
  const closed = await page.evaluate(() => ({
    panelGone: document.querySelectorAll('.overlay-panel[role="dialog"]').length === 0,
    bodyOverflow: getComputedStyle(document.body).overflow,
    activeText: document.activeElement?.textContent?.trim() ?? ''
  }))
  record('弹层 · Esc 关闭', closed.panelGone, `panelGone=${closed.panelGone}`)
  record('弹层 · 关闭后恢复滚动', closed.bodyOverflow !== 'hidden', `overflow=${closed.bodyOverflow}`)
  record('弹层 · 焦点归还触发元素', closed.activeText.includes('题目列表'), `active="${closed.activeText}"`)

  // Ctrl+/ 应能打开并再次关闭快捷键帮助（该组合键不能被"弹层打开时让位"逻辑吞掉）
  await page.keyboard.press('Control+/')
  await page.waitForTimeout(400)
  const openedByShortcut = await page.locator('.overlay-panel[role="dialog"]').count()
  await page.keyboard.press('Control+/')
  await page.waitForTimeout(400)
  const closedByShortcut = await page.locator('.overlay-panel[role="dialog"]').count()
  record('弹层 · 组合键可开可关', openedByShortcut === 1 && closedByShortcut === 0,
    `开=${openedByShortcut} 关=${closedByShortcut}`)

  await page.screenshot({ path: path.join(OUT, 'a11y-overlay-closed.png'), fullPage: false })
  await context.close()
}

/**
 * 验证可排序表头的键盘可达性与排序状态播报。
 * 以「总分」列为例：Enter 一次升序、再一次降序，并校验首行确实随排序变化。
 */
async function checkTableA11y(browser) {
  const context = await makeContext(browser, { width: 1440, height: 900, theme: 'dark', role: 'TEACHER' })
  await installRoutes(context)

  const page = await context.newPage()
  await page.goto(`${BASE}/teacher/analytics/1`, { waitUntil: 'load' })
  await page.waitForSelector('th button.th-sort', { timeout: 8000 }).catch(() => {})

  const header = page.locator('th', { hasText: '总分' }).first()
  const button = header.locator('button.th-sort')
  const buttonCount = await page.locator('th button.th-sort').count()
  record('表格 · 表头为可聚焦按钮', buttonCount === 6, `th button 数量=${buttonCount}`)

  const firstScore = () => page.evaluate(() =>
    document.querySelector('tbody tr .score-badge')?.textContent?.trim() ?? '')

  await button.focus()
  await page.keyboard.press('Enter')
  await page.waitForTimeout(250)
  const ascState = await header.getAttribute('aria-sort')
  const ascFirst = await firstScore()

  await page.keyboard.press('Enter')
  await page.waitForTimeout(250)
  const descState = await header.getAttribute('aria-sort')
  const descFirst = await firstScore()

  record('表格 · 键盘触发布局排序', ascState === 'ascending' && ascFirst === '62',
    `aria-sort=${ascState} 首行=${ascFirst}`)
  record('表格 · 再次触发切换方向', descState === 'descending' && descFirst === '96',
    `aria-sort=${descState} 首行=${descFirst}`)

  const otherSort = await page.locator('th').nth(1).getAttribute('aria-sort')
  record('表格 · 未排序列标注 none', otherSort === 'none', `学号列 aria-sort=${otherSort}`)

  await page.screenshot({ path: path.join(OUT, 'a11y-table-sorted.png'), fullPage: true })
  await context.close()
}

/**
 * 验证工作台的语法包按需加载：
 *   默认语言（C++）应在进入时加载；未使用的语言（Python）在切换前不应产生任何请求，
 *   切换后才按需下发，且高亮确实生效。
 */
async function checkLazyLanguage(browser) {
  const context = await makeContext(browser, { width: 1440, height: 900, theme: 'dark', role: 'STUDENT' })
  await installRoutes(context)

  const page = await context.newPage()
  const langRequests = { cpp: 0, python: 0, java: 0 }
  page.on('request', (req) => {
    const url = req.url()
    if (url.includes('lang-cpp')) langRequests.cpp += 1
    else if (url.includes('lang-python')) langRequests.python += 1
    else if (url.includes('lang-java')) langRequests.java += 1
  })

  await page.goto(`${BASE}/student/practice`, { waitUntil: 'load' })
  await page.waitForSelector('.cm-content', { timeout: 15000 })
  await page.waitForTimeout(1200)

  const highlightSpans = () => page.evaluate(() =>
    document.querySelectorAll('.cm-content .cm-line span[class]').length)

  const spansBefore = await highlightSpans()
  record('语言包 · 默认语言已应用高亮', spansBefore > 0, `高亮 span=${spansBefore}`)
  record('语言包 · 默认语言按需加载', langRequests.cpp > 0, `lang-cpp 请求=${langRequests.cpp}`)
  record('语言包 · 未使用的语言不下发', langRequests.python === 0 && langRequests.java === 0,
    `python=${langRequests.python} java=${langRequests.java}`)

  // 切换到 Python，语法包应此时才被请求
  await page.locator('.code-toolbar select').selectOption('PYTHON')
  await page.waitForTimeout(1500)

  const spansAfter = await highlightSpans()
  record('语言包 · 切换后按需下发', langRequests.python > 0, `lang-python 请求=${langRequests.python}`)
  record('语言包 · 切换后高亮仍生效', spansAfter > 0, `高亮 span=${spansAfter}`)
  record('语言包 · Java 始终未被下载', langRequests.java === 0, `lang-java 请求=${langRequests.java}`)

  await page.screenshot({ path: path.join(OUT, 'lazy-language-python.png'), fullPage: false })
  await context.close()
}

/**
 * 验证工作台分栏宽度的键盘可操作性与持久化。
 * 学生按自己的读题习惯调整分栏后，不应在下次进入时被重置。
 */
async function checkWorkbenchSplitter(browser) {
  const context = await makeContext(browser, { width: 1440, height: 900, theme: 'dark', role: 'STUDENT' })
  await installRoutes(context)

  const page = await context.newPage()
  await page.goto(`${BASE}/student/practice`, { waitUntil: 'load' })
  await page.waitForSelector('.wb-splitter', { timeout: 15000 })
  await page.waitForTimeout(800)

  const splitter = page.locator('.wb-splitter')
  const widthOf = async () => Number(await splitter.getAttribute('aria-valuenow'))

  const start = await widthOf()
  await splitter.focus()
  await page.keyboard.press('ArrowLeft')
  await page.keyboard.press('ArrowLeft')
  await page.keyboard.press('ArrowLeft')
  const afterKeys = await widthOf()
  record('分栏 · 方向键可调整', afterKeys === start - 48, `${start} → ${afterKeys}（期望 ${start - 48}）`)

  // End / Home 应跳到两端
  await page.keyboard.press('End')
  const atMax = await widthOf()
  await page.keyboard.press('Home')
  const atMin = await widthOf()
  const min = Number(await splitter.getAttribute('aria-valuemin'))
  record('分栏 · Home/End 跳到两端', atMin === min && atMax > atMin, `min=${atMin} max=${atMax}`)

  // 设为确定值后刷新，验证持久化
  await page.keyboard.press('End')
  const chosen = await widthOf()
  await page.reload({ waitUntil: 'load' })
  await page.waitForSelector('.wb-splitter', { timeout: 15000 })
  await page.waitForTimeout(800)
  const restored = await widthOf()
  record('分栏 · 刷新后保持宽度', restored === chosen, `设定 ${chosen} → 刷新后 ${restored}`)

  await context.close()
}

// ---------------------------------------------------------------- 执行

// --disable-dev-shm-usage：容器/CI 中 /dev/shm 通常只有 64MB，
// 本脚本会连续创建多个上下文并截整页图，容易触发 Chromium 崩溃。
// 该参数让 Chromium 改用 /tmp，本地与 CI 均无副作用。
const browser = await chromium.launch({
  args: ['--disable-dev-shm-usage']
})

/**
 * 单个检查项抛出异常时记为失败并继续执行后续检查。
 * 否则一次超时会让整个套件崩溃，CI 只能看到一段堆栈而拿不到其余结论。
 */
async function guard(label, fn) {
  try {
    return await fn()
  } catch (e) {
    const firstLine = String(e?.message || e).split('\n')[0].slice(0, 200)
    record(`${label} · 执行异常`, false, firstLine)
    return null
  }
}

// 预热：Vite 首次访问可能因依赖预构建而整页刷新，先空跑一次避免污染首个用例
{
  const warm = await makeContext(browser, { width: 1280, height: 800, theme: 'dark', role: 'STUDENT', noAuth: true })
  await installRoutes(warm)
  const warmPage = await warm.newPage()
  await warmPage.goto(`${BASE}/login`, { waitUntil: 'load' })
  await warmPage.waitForTimeout(1200)
  await warm.close()
}

console.log('· 页面冒烟（外部请求全部阻断）')
// 页面清单集中声明，便于统一加保护与统计
const PAGE_CASES = [
  ['login-dark', { path: '/login', noAuth: true, height: 980 }],
  ['login-light', { path: '/login', noAuth: true, theme: 'light', height: 980 }],
  ['login-mobile', { path: '/login', noAuth: true, width: 390, height: 844 }],
  ['teacher-classes', { path: '/teacher/classes' }],
  ['teacher-assignments', { path: '/teacher/assignments' }],
  ['teacher-analytics', { path: '/teacher/analytics/1' }],
  ['teacher-analytics-classic', { path: '/teacher/analytics/1/classic' }],
  ['teacher-problem-bank', { path: '/teacher/classes/1/problems' }],
  ['student-home', { path: '/student', role: 'STUDENT' }],
  ['student-home-mobile', { path: '/student', role: 'STUDENT', width: 390, height: 844 }],
  ['student-practice-workbench', { path: '/student/practice', role: 'STUDENT' }],
  ['student-practice-mobile', { path: '/student/practice', role: 'STUDENT', width: 390, height: 844 }],
  ['student-contest', { path: '/student/contest/1', role: 'STUDENT' }],
  ['admin-org', { path: '/admin', role: 'ADMIN' }]
]

const results = []
for (const [name, options] of PAGE_CASES) {
  const result = await guard(`${name}`, () => smokePage(browser, name, options))
  if (result) results.push(result)
}

console.log('· 失败态与重试')
await guard('失败态', () => checkErrorRetry(browser))

console.log('· 通知链路')
await guard('通知', () => checkToast(browser))

console.log('· 弹层可访问性')
await guard('弹层', () => checkOverlayA11y(browser))

console.log('· 表格交互语义')
await guard('表格', () => checkTableA11y(browser))

console.log('· 语法包按需加载')
await guard('语言包', () => checkLazyLanguage(browser))

console.log('· 加载稳定性（接口延迟 400ms）')
await guard('加载态', () => checkLoadStability(browser))

console.log('· 工作台分栏')
await guard('分栏', () => checkWorkbenchSplitter(browser))

console.log('· 主题首帧')
await guard('主题', () => checkThemeBeforeMount(browser))

await browser.close()

// 全局汇总：确认离线注册确实覆盖了实际使用的图标（而非全部回退为空壳）
const totalIcons = results.reduce((sum, r) => sum + r.iconsTotal, 0)
record('全局 · 离线图标覆盖度', totalIcons >= 50, `共渲染 ${totalIcons} 个图标，低于预期`)

const failed = assertions.filter((a) => !a.ok)
console.log('\n====== 结果 ======')
console.log(`断言通过 ${assertions.length - failed.length}/${assertions.length}`)
if (failed.length) {
  console.log('失败项：')
  for (const f of failed) console.log(`  - ${f.name}: ${f.detail}`)
}
if (process.env.VERBOSE) console.log(JSON.stringify(results, null, 2))

process.exit(failed.length ? 1 : 0)
