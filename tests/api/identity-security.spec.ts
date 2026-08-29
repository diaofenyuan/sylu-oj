// @ts-check
import { test, expect } from '@playwright/test'
import crypto from 'node:crypto'

/**
 * Task 5 教务网页登录适配器与会话安全 API 级验收（Playwright request，无需浏览器）。
 *
 * 安全边界：本套件只使用模拟教务提供方（/internal/idp/**）签发的合成票据与
 * 合成学号/工号，任何请求不携带真实教务密码；生产验收由账号持有人现场完成。
 * 生产"无本地降级"由配置强制（prod profile：local-accounts-enabled=false、
 * enforce-exclusive=true），已在 JUnit（TeachingLoginFallbackTest）中验证。
 */

const INTERNAL_TOKEN = process.env.OJ_DEV_INTERNAL_TOKEN || 'dev-internal-only'
const CALLBACK = 'http://localhost:8080/api/identity/login/callback'

function uniq(prefix) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}`
}

function parseRefreshCookie(setCookieHeader) {
  if (!setCookieHeader) return null
  const match = setCookieHeader.match(/oj_refresh_token=([^;]+)/)
  return match ? match[1] : null
}

// ---------------- RFC 6238 TOTP（管理员双因子验证用，仅本地计算） ----------------

function base32Decode(str) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = 0
  let value = 0
  const out = []
  for (const c of str.replace(/=+$/, '')) {
    const idx = alphabet.indexOf(c.toUpperCase())
    if (idx === -1) continue
    value = (value << 5) | idx
    bits += 5
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(out)
}

function totpCode(base32Secret) {
  const key = base32Decode(base32Secret)
  const counter = Math.floor(Date.now() / 1000 / 30)
  const buf = Buffer.alloc(8)
  buf.writeBigUInt64BE(BigInt(counter))
  const hmac = crypto.createHmac('sha1', key).update(buf).digest()
  const offset = hmac[hmac.length - 1] & 0x0f
  const code = (((hmac[offset] & 0x7f) << 24) | (hmac[offset + 1] << 16)
    | (hmac[offset + 2] << 8) | hmac[offset + 3]) % 1000000
  return code.toString().padStart(6, '0')
}

// ---------------- 共享状态 ----------------

let adminToken
let teacherStaffNo
let studentNo
let teacherName
let studentName

test.describe('教务登录适配器与会话安全', () => {
  test('管理员建立组织（合成学号/工号建档）', async ({ request }) => {
    const suffix = uniq('id')
    const login = await request.post('/api/auth/login', {
      data: { loginName: 'devadmin', password: process.env.OJ_DEV_ADMIN_PASSWORD || 'devadmin' }
    })
    expect(login.status()).toBe(200)
    adminToken = (await login.json()).token

    const admin = (path, method = 'POST', data) =>
      request[method.toLowerCase()](path, {
        data,
        headers: { Authorization: `Bearer ${adminToken}` }
      })

    const term = await admin('/api/admin/terms', 'POST', {
      code: `T-${suffix}`, name: '身份学期', startDate: '2026-01-01', endDate: '2026-12-31'
    })
    expect(term.status()).toBe(200)
    const termId = (await term.json()).id
    const course = await admin('/api/admin/courses', 'POST', { code: `C-${suffix}`, name: '程序设计', credit: 3 })
    const courseId = (await course.json()).id
    const cls = await admin('/api/admin/teaching-classes', 'POST', {
      termId, courseId, code: `A-${suffix}`, name: '身份教学班'
    })
    const classId = (await cls.json()).id

    teacherStaffNo = `TNO-${suffix}`
    studentNo = `SNO-${suffix}`
    teacherName = '身份测试教师'
    studentName = '身份测试学生'
    const teacher = await admin('/api/admin/teachers', 'POST', { staffNo: teacherStaffNo, name: teacherName })
    const teacherId = (await teacher.json()).id
    const student = await admin('/api/admin/students', 'POST', { studentNo, name: studentName })
    const studentId = (await student.json()).id
    await admin('/api/admin/teacher-assignments', 'POST', { teachingClassId: classId, teacherId, role: 'PRIMARY' })
    await admin('/api/admin/enrollments', 'POST', { teachingClassId: classId, studentId, transfer: false })

    // 防御性重置：无论上一轮套件在哪一步中断，都恢复到可用基线状态
    const internal = (path, data) => request.post(path, {
      data, headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    await internal('/internal/idp/set-available', { available: true })
    await internal('/internal/idp/set-page-version', { version: 'v1' })
    await internal('/internal/idp/set-captcha-required', { required: false })
    await internal('/internal/idp/reset-admin-totp', {})
    const status = await admin('/api/admin/identities/adapter-status', 'GET')
    if ((await status.json()).status === 'HALTED') {
      await admin('/api/admin/identities/adapter/resume')
    }
  })

  test('教师经教务适配器登录并加载 PRIMARY 授课关系', async ({ request }) => {
    const ticketRes = await request.post('/internal/idp/issue-ticket', {
      data: { externalNo: teacherStaffNo, name: teacherName, type: 'STAFF' },
      headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    expect(ticketRes.status()).toBe(200)
    const ticket = (await ticketRes.json()).ticket

    const start = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    expect(start.status()).toBe(200)
    const startBody = await start.json()
    expect(startBody.state).toBeTruthy()
    expect(startBody.loginPageUrl).toContain('jxw.sylu.edu.cn')

    const callback = await request.post('/api/identity/login/callback', {
      data: { state: startBody.state, ticket, callbackUrl: CALLBACK }
    })
    expect(callback.status()).toBe(200)
    const body = await callback.json()
    expect(body.role).toBe('TEACHER')
    expect(body.externalNo).toBe(teacherStaffNo)
    expect(body.token).toBeTruthy()
    expect(body.expiresInMinutes).toBe(10)
    const teacherToken = body.token

    const me = await request.get('/api/identity/me', {
      headers: { Authorization: `Bearer ${teacherToken}` }
    })
    expect(me.status()).toBe(200)
    const profile = await me.json()
    expect(profile.externalNo).toBe(teacherStaffNo)
    expect(profile.teachingRelations.length).toBe(1)
    expect(profile.teachingRelations[0].role).toBe('PRIMARY')
  })

  test('学生经教务适配器登录并加载选课归属', async ({ request }) => {
    const ticketRes = await request.post('/internal/idp/issue-ticket', {
      data: { externalNo: studentNo, name: studentName, type: 'STUDENT' },
      headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    const ticket = (await ticketRes.json()).ticket
    const start = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    const { state } = await start.json()
    const callback = await request.post('/api/identity/login/callback', {
      data: { state, ticket, callbackUrl: CALLBACK }
    })
    expect(callback.status()).toBe(200)
    const body = await callback.json()
    expect(body.role).toBe('STUDENT')
    const studentToken = body.token

    const me = await request.get('/api/identity/me', {
      headers: { Authorization: `Bearer ${studentToken}` }
    })
    const profile = await me.json()
    expect(profile.enrollment).toBeTruthy()
    expect(profile.teachingRelations.length).toBe(0)
  })

  test('一次性 state 与票据：重放被拒绝', async ({ request }) => {
    const start = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    const { state } = await start.json()
    const t1 = await request.post('/internal/idp/issue-ticket', {
      data: { externalNo: studentNo, name: studentName, type: 'STUDENT' },
      headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    const ticket = (await t1.json()).ticket

    const first = await request.post('/api/identity/login/callback', {
      data: { state, ticket, callbackUrl: CALLBACK }
    })
    expect(first.status()).toBe(200)

    // 同一 state 重放
    const replay = await request.post('/api/identity/login/callback', {
      data: { state, ticket, callbackUrl: CALLBACK }
    })
    expect(replay.status()).toBe(401)
    expect((await replay.json()).code).toBe('LOGIN_STATE_INVALID')

    // 同一票据重放（新 state）
    const start2 = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    const replayTicket = await request.post('/api/identity/login/callback', {
      data: { state: (await start2.json()).state, ticket, callbackUrl: CALLBACK }
    })
    expect(replayTicket.status()).toBe(401)
    expect((await replayTicket.json()).code).toBe('TICKET_REPLAYED')
  })

  test('回调地址精确白名单：非法与前缀变体均被拒', async ({ request }) => {
    const bad = await request.get('/api/identity/login/start?callbackUrl=https://evil.example/cb')
    expect(bad.status()).toBe(400)
    expect((await bad.json()).code).toBe('CALLBACK_NOT_ALLOWED')

    const prefix = await request.get(
      `/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK + '/extra')}`)
    expect(prefix.status()).toBe(400)
    expect((await prefix.json()).code).toBe('CALLBACK_NOT_ALLOWED')
  })

  test('教务不可用：拒绝新登录而非降级', async ({ request }) => {
    await request.post('/internal/idp/set-available', {
      data: { available: false }, headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    const start = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    expect(start.status()).toBe(503)
    expect((await start.json()).code).toBe('IDP_UNAVAILABLE')
    await request.post('/internal/idp/set-available', {
      data: { available: true }, headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
  })

  test('页面结构变化：适配器熔断，管理员核查后恢复', async ({ request }) => {
    const admin = (path, method = 'POST', data) =>
      method === 'GET'
        ? request.get(path, { headers: { Authorization: `Bearer ${adminToken}` } })
        : request.post(path, { data, headers: { Authorization: `Bearer ${adminToken}` } })

    await request.post('/internal/idp/set-page-version', {
      data: { version: 'v2' }, headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    const halted = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    expect(halted.status()).toBe(503)
    expect((await halted.json()).code).toBe('ADAPTER_HALTED')

    const status = await admin('/api/admin/identities/adapter-status', 'GET')
    expect(status.status()).toBe(200)
    expect((await status.json()).status).toBe('HALTED')

    // 恢复前先把页面版本还原（模拟人工核查确认），再以新指纹为基线恢复
    await request.post('/internal/idp/set-page-version', {
      data: { version: 'v1' }, headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    const resume = await admin('/api/admin/identities/adapter/resume')
    expect(resume.status()).toBe(200)
    const ok = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    expect(ok.status()).toBe(200)
  })

  test('验证码出现：熔断且不绕过', async ({ request }) => {
    const admin = (path, method = 'POST', data) =>
      method === 'GET'
        ? request.get(path, { headers: { Authorization: `Bearer ${adminToken}` } })
        : request.post(path, { data, headers: { Authorization: `Bearer ${adminToken}` } })

    await request.post('/internal/idp/set-captcha-required', {
      data: { required: true }, headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    const halted = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
    expect(halted.status()).toBe(503)
    expect((await halted.json()).code).toBe('ADAPTER_HALTED')
    const status = await admin('/api/admin/identities/adapter-status', 'GET')
    expect((await status.json()).haltedReason).toBe('CAPTCHA')

    await request.post('/internal/idp/set-captcha-required', {
      data: { required: false }, headers: { 'X-Internal-Token': INTERNAL_TOKEN }
    })
    await admin('/api/admin/identities/adapter/resume')
  })

  test('刷新令牌单次轮换：重用触发整链撤销', async ({ playwright }) => {
    // 独立上下文隔离 Cookie
    const ctx = await playwright.request.newContext({ baseURL: 'http://localhost:8080' })

    const t = await issueTicketAndStart(ctx, studentNo, studentName, 'STUDENT')
    const first = await ctx.post('/api/identity/login/callback', {
      data: { state: t.state, ticket: t.ticket, callbackUrl: CALLBACK }
    })
    expect(first.status()).toBe(200)
    const refresh1 = parseRefreshCookie(first.headers()['set-cookie'])
    expect(refresh1).toBeTruthy()

    const rotated = await ctx.post('/api/identity/refresh', {
      headers: { Cookie: `oj_refresh_token=${refresh1}` }
    })
    expect(rotated.status()).toBe(200)
    const refresh2 = parseRefreshCookie(rotated.headers()['set-cookie'])
    expect(refresh2).toBeTruthy()
    expect(refresh2).not.toBe(refresh1)

    // 重用旧刷新令牌 → 整链撤销
    const reuse = await ctx.post('/api/identity/refresh', {
      headers: { Cookie: `oj_refresh_token=${refresh1}` }
    })
    expect(reuse.status()).toBe(401)
    expect((await reuse.json()).code).toBe('REFRESH_REUSED')

    const afterReuse = await ctx.post('/api/identity/refresh', {
      headers: { Cookie: `oj_refresh_token=${refresh2}` }
    })
    expect(afterReuse.status()).toBe(401)
    expect((await afterReuse.json()).code).toBe('REFRESH_INVALID')

    await ctx.dispose()
  })

  test('管理员登录强制 TOTP 双因子', async ({ request }) => {
    const adminNo = 'ADM-0001'
    const issue = async () => {
      const r = await request.post('/internal/idp/issue-ticket', {
        data: { externalNo: adminNo, name: '管理员', type: 'STAFF' },
        headers: { 'X-Internal-Token': INTERNAL_TOKEN }
      })
      return (await r.json()).ticket
    }
    const startOnce = async () => {
      const s = await request.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
      return (await s.json()).state
    }

    // 首次登录：要求先绑定双因子，不签发会话
    const first = await request.post('/api/identity/login/callback', {
      data: { state: await startOnce(), ticket: await issue(), callbackUrl: CALLBACK }
    })
    expect(first.status()).toBe(403)
    const firstBody = await first.json()
    expect(firstBody.code).toBe('MFA_ENROLLMENT_REQUIRED')
    expect(firstBody.enrollToken).toBeTruthy()

    // 绑定 TOTP
    const enroll = await request.post('/api/identity/totp/enroll', {
      data: { enrollToken: firstBody.enrollToken }
    })
    expect(enroll.status()).toBe(200)
    const { base32Secret, otpauthUri } = await enroll.json()
    expect(base32Secret).toBeTruthy()
    expect(otpauthUri).toContain('otpauth://totp/')

    const confirm = await request.post('/api/identity/totp/confirm', {
      data: { enrollToken: firstBody.enrollToken, code: totpCode(base32Secret) }
    })
    expect(confirm.status()).toBe(200)

    // 未携带验证码 → 拒绝
    const missing = await request.post('/api/identity/login/callback', {
      data: { state: await startOnce(), ticket: await issue(), callbackUrl: CALLBACK }
    })
    expect(missing.status()).toBe(401)
    expect((await missing.json()).code).toBe('MFA_REQUIRED')

    // 错误验证码 → 拒绝
    const wrong = await request.post('/api/identity/login/callback', {
      data: { state: await startOnce(), ticket: await issue(), callbackUrl: CALLBACK, totpCode: '000000' }
    })
    expect(wrong.status()).toBe(401)
    expect((await wrong.json()).code).toBe('MFA_FAILED')

    // 正确验证码 → 登录成功
    const ok = await request.post('/api/identity/login/callback', {
      data: { state: await startOnce(), ticket: await issue(), callbackUrl: CALLBACK, totpCode: totpCode(base32Secret) }
    })
    expect(ok.status()).toBe(200)
    expect((await ok.json()).role).toBe('ADMIN')
  })

  test('登出终止本地会话', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: 'http://localhost:8080' })
    const t = await issueTicketAndStart(ctx, teacherStaffNo, teacherName, 'STAFF')
    const login = await ctx.post('/api/identity/login/callback', {
      data: { state: t.state, ticket: t.ticket, callbackUrl: CALLBACK }
    })
    expect(login.status()).toBe(200)
    const { token } = await login.json()
    const refresh = parseRefreshCookie(login.headers()['set-cookie'])

    const logout = await ctx.post('/api/identity/logout', {
      headers: { Authorization: `Bearer ${token}`, Cookie: `oj_refresh_token=${refresh}` }
    })
    expect(logout.status()).toBe(200)

    const me = await ctx.get('/api/identity/me', { headers: { Authorization: `Bearer ${token}` } })
    expect(me.status()).toBe(401)
    const refreshAfter = await ctx.post('/api/identity/refresh', {
      headers: { Cookie: `oj_refresh_token=${refresh}` }
    })
    expect(refreshAfter.status()).toBe(401)
    await ctx.dispose()
  })
})

/** 辅助：为合成学号/工号签发模拟教务票据并发起登录。 */
async function issueTicketAndStart(ctx, externalNo, name, type) {
  const ticketRes = await ctx.post('/internal/idp/issue-ticket', {
    data: { externalNo, name, type },
    headers: { 'X-Internal-Token': INTERNAL_TOKEN }
  })
  if (ticketRes.status() !== 200) {
    throw new Error(`issue-ticket failed: ${ticketRes.status()} ${await ticketRes.text()}`)
  }
  const ticket = (await ticketRes.json()).ticket
  const start = await ctx.get(`/api/identity/login/start?callbackUrl=${encodeURIComponent(CALLBACK)}`)
  if (start.status() !== 200) {
    throw new Error(`login/start failed: ${start.status()} ${await start.text()}`)
  }
  const { state } = await start.json()
  return { ticket, state }
}
