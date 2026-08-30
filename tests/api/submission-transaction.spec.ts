// @ts-check
import { test, expect } from '@playwright/test'
import { createHmac } from 'node:crypto'

/**
 * Task 6 提交事务与 Judge Gateway API 级验收（Playwright request，无需浏览器）。
 * 覆盖：提交幂等与 Outbox 派发 → 长轮询领取（载荷无数据区凭据）→
 * 逐用例加密拉取（错配熔断）→ 结果签名/幂等/旧版本拒绝 → SE 不扣次数 → 超限拒绝。
 * 针对 dev 服务（OJ 合成账号 + 内部令牌注册的联调 Agent），生产为 mTLS 通道。
 */

const INTERNAL_TOKEN = process.env.OJ_DEV_INTERNAL_TOKEN || 'dev-internal-only'

function uniq(prefix) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}`
}

function sign(secret, taskUuid, resultCode, score, timeMs, memKb, version) {
  const canonical = `${taskUuid}|${resultCode}|${score}|${timeMs}|${memKb}|${version}`
  return createHmac('sha256', secret).update(canonical).digest('hex')
}

const agentHeaders = (agentId, secret) => ({
  'X-Agent-Id': agentId,
  'X-Agent-Token': secret
})

// 队列按先后顺序派发：dev 库中可能残留历史运行的 PENDING 任务，
// 这里循环领取直到拿到目标提交的任务（无关任务任其租约过期回队）。
async function submitFor(request, key) {
  const sub = await request.post('/api/student/submissions', {
    data: {
      assignmentTargetId: targetId, problemId, language: 'CPP',
      code: 'int main(){return 0;}', idempotencyKey: key
    },
    headers: { Authorization: `Bearer ${studentToken}` }
  })
  expect(sub.status()).toBe(200)
  return (await sub.json()).submissionId
}

async function claimFor(request, agentId, secret, submissionId, { attempt = null } = {}) {
  for (let i = 0; i < 60; i++) {
    const claim = await request.post('/api/judge/v1/tasks/claim', {
      data: { waitSeconds: 1 }, headers: agentHeaders(agentId, secret)
    })
    if (claim.status() === 204) continue
    expect(claim.status()).toBe(200)
    const payload = await claim.json()
    if (payload.submissionId === submissionId && (attempt === null || payload.attempt === attempt)) {
      return payload
    }
  }
  throw new Error(`未领取到提交 ${submissionId} 的判题任务`)
}

let adminToken
let teacherToken
let studentToken
let classId
let targetId
let problemId
let agentSecret
let agent1Id
let agent2Id
let agent3Id
let runSuffix

test.describe('提交事务与判题网关', () => {
  test('准备组织、题库、作业与联调 Agent', async ({ request }) => {
    runSuffix = uniq('t6')
    const suffix = runSuffix
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
      code: `T-${suffix}`, name: '测试学期',
      startDate: '2026-01-01', endDate: '2026-12-31'
    })
    const termId = (await term.json()).id
    const course = await admin('/api/admin/courses', 'POST', {
      code: `C-${suffix}`, name: '程序设计', credit: 3
    })
    const courseId = (await course.json()).id
    const cls = await admin('/api/admin/teaching-classes', 'POST', {
      termId, courseId, code: `A-${suffix}`, name: '测试教学班'
    })
    classId = (await cls.json()).id
    const teacher = await admin('/api/admin/teachers', 'POST', { staffNo: `TNO-${suffix}`, name: '测试教师' })
    const teacherId = (await teacher.json()).id
    const student = await admin('/api/admin/students', 'POST', { studentNo: `SNO-${suffix}`, name: '测试学生' })
    const studentId = (await student.json()).id
    await admin('/api/admin/teacher-assignments', 'POST', {
      teachingClassId: classId, teacherId, role: 'PRIMARY'
    })
    await admin('/api/admin/enrollments', 'POST', { teachingClassId: classId, studentId, transfer: false })
    await admin('/api/admin/dev-accounts', 'POST', {
      role: 'TEACHER', loginName: `teach-${suffix}`, password: 'password123', teacherId, studentId: null
    })
    await admin('/api/admin/dev-accounts', 'POST', {
      role: 'STUDENT', loginName: `stud-${suffix}`, password: 'password123', teacherId: null, studentId
    })
    const tl = await request.post('/api/auth/login', {
      data: { loginName: `teach-${suffix}`, password: 'password123' }
    })
    teacherToken = (await tl.json()).token
    const sl = await request.post('/api/auth/login', {
      data: { loginName: `stud-${suffix}`, password: 'password123' }
    })
    studentToken = (await sl.json()).token

    // 题库与题目（样例 + 隐藏用例）
    const t = (path, method = 'POST', data) =>
      request[method.toLowerCase()](path, {
        data, headers: { Authorization: `Bearer ${teacherToken}` }
      })
    const bank = await t('/api/teacher/problem-banks', 'POST', { teachingClassId: classId, name: '默认题库' })
    const bankId = (await bank.json()).id
    const problem = await t('/api/teacher/problems', 'POST', {
      bankId, code: 'P1', title: 'A+B', description: '求和',
      languages: ['CPP'], maxScore: 100,
      testcases: [
        { orderNum: 1, sample: true, input: '1 2', expectedOutput: '3', score: 60 },
        { orderNum: 2, sample: false, input: '10 20', expectedOutput: '30', score: 40 }
      ]
    })
    problemId = (await problem.json()).id
    await t(`/api/teacher/problems/${problemId}/publish`, 'PUT')
    const assignment = await t('/api/teacher/assignments', 'POST', {
      title: '作业T6', mode: 'HOMEWORK', items: [{ problemId, weight: 100 }]
    })
    const assignmentId = (await assignment.json()).id
    const publish = await t(`/api/teacher/assignments/${assignmentId}/publish`, 'POST', {
      targets: [{
        teachingClassId: classId,
        publishAt: new Date(Date.now() - 3600_000).toISOString().slice(0, 19),
        deadline: new Date(Date.now() + 86400_000).toISOString().slice(0, 19),
        maxSubmissions: 5
      }]
    })
    targetId = (await publish.json())[0].id

    // 注册联调 Agent（dev 内部令牌；生产由 mTLS 身份替代）。
    // 每轮使用唯一 agentId：被熔断的 Agent 不会在后续轮次中复活。
    agent1Id = `agent-e2e-${runSuffix}`
    const reg = await request.post('/api/judge/v1/agents/register', {
      headers: { 'X-Internal-Token': INTERNAL_TOKEN },
      data: { agentId: agent1Id, displayName: 'E2E Agent' }
    })
    expect(reg.status()).toBe(200)
    agentSecret = (await reg.json()).secret
    expect(agentSecret).toBeTruthy()
  })

  test('提交创建任务并派发，载荷不含测试数据与凭据', async ({ request }) => {
    const submissionId = await submitFor(request, 'k-t6-1')
    const payload = await claimFor(request, agent1Id, agentSecret, submissionId)
    expect(payload.code).toBe('int main(){return 0;}')
    expect(payload.languageRuntime).toBe('gcc-13.3-c++20')
    expect(payload.testcaseRefs).toEqual([1, 2])
    // 载荷不含用例数据内容、数据库凭据或任何密钥
    const dump = JSON.stringify(payload)
    expect(dump).not.toContain('10 20')
    expect(dump).not.toContain('jdbc')
    expect(dump).not.toContain('password')

    // 未认证领取被拒
    const anon = await request.post('/api/judge/v1/tasks/claim', { data: {} })
    expect(anon.status()).toBe(401)
  })

  test('逐用例加密拉取；错配请求触发 P1 熔断', async ({ request }) => {
    const submissionId = await submitFor(request, 'k-t6-2')
    const { taskUuid } = await claimFor(request, agent1Id, agentSecret, submissionId)

    const ok = await request.post(`/api/judge/v1/tasks/${taskUuid}/testcases/1`, {
      headers: agentHeaders(agent1Id, agentSecret)
    })
    expect(ok.status()).toBe(200)
    const envelope = await ok.json()
    expect(envelope.algo).toBe('AES-256-GCM')
    expect(envelope.ciphertext.length).toBeGreaterThan(0)

    // 与当前任务不匹配的用例 → 403 + P1 熔断该 Agent
    const bad = await request.post(`/api/judge/v1/tasks/${taskUuid}/testcases/99`, {
      headers: agentHeaders(agent1Id, agentSecret)
    })
    expect(bad.status()).toBe(403)
    expect((await bad.json()).code).toBe('TESTCASE_MISMATCH')
    const after = await request.post('/api/judge/v1/tasks/claim', {
      data: { waitSeconds: 0 }, headers: agentHeaders(agent1Id, agentSecret)
    })
    expect(after.status()).toBe(403)
    expect((await after.json()).code).toBe('AGENT_SUSPENDED')
  })

  test('结果签名校验、幂等重复投递与旧版本拒绝', async ({ request }) => {
    // 熔断后使用新 Agent
    agent2Id = `agent-e2e-2-${runSuffix}`
    const reg = await request.post('/api/judge/v1/agents/register', {
      headers: { 'X-Internal-Token': INTERNAL_TOKEN },
      data: { agentId: agent2Id, displayName: 'E2E Agent 2' }
    })
    const secret2 = (await reg.json()).secret

    const submissionId = await submitFor(request, 'k-t6-3')
    const { taskUuid } = await claimFor(request, agent2Id, secret2, submissionId)

    // 篡改签名 → 401
    const badSig = await request.post(`/api/judge/v1/tasks/${taskUuid}/result`, {
      headers: agentHeaders(agent2Id, secret2),
      data: {
        resultCode: 'AC', normalizedScore: 100, totalTimeMs: 12, peakMemoryKb: 300,
        resultVersion: 1, snapshotVersion: null,
        testcases: [
          { order: 1, status: 'AC', score: 60, timeMs: 5, memoryKb: 100 },
          { order: 2, status: 'AC', score: 40, timeMs: 7, memoryKb: 120 }
        ],
        signature: 'deadbeef'
      }
    })
    expect(badSig.status()).toBe(401)

    // 正确签名 → 201；重复投递 → 200 duplicate；旧版本 → 409
    const good = await request.post(`/api/judge/v1/tasks/${taskUuid}/result`, {
      headers: agentHeaders(agent2Id, secret2),
      data: {
        resultCode: 'AC', normalizedScore: 100, totalTimeMs: 12, peakMemoryKb: 300,
        resultVersion: 1,
        testcases: [
          { order: 1, status: 'AC', score: 60, timeMs: 5, memoryKb: 100 },
          { order: 2, status: 'AC', score: 40, timeMs: 7, memoryKb: 120 }
        ],
        signature: sign(secret2, taskUuid, 'AC', '100.00', 12, 300, 1)
      }
    })
    expect(good.status()).toBe(201)

    const replay = await request.post(`/api/judge/v1/tasks/${taskUuid}/result`, {
      headers: agentHeaders(agent2Id, secret2),
      data: {
        resultCode: 'AC', normalizedScore: 100, totalTimeMs: 12, peakMemoryKb: 300,
        resultVersion: 1,
        testcases: [
          { order: 1, status: 'AC', score: 60, timeMs: 5, memoryKb: 100 },
          { order: 2, status: 'AC', score: 40, timeMs: 7, memoryKb: 120 }
        ],
        signature: sign(secret2, taskUuid, 'AC', '100.00', 12, 300, 1)
      }
    })
    expect(replay.status()).toBe(200)
    expect((await replay.json()).duplicate).toBe(true)

    const stale = await request.post(`/api/judge/v1/tasks/${taskUuid}/result`, {
      headers: agentHeaders(agent2Id, secret2),
      data: {
        resultCode: 'WA', normalizedScore: 40, totalTimeMs: 12, peakMemoryKb: 300,
        resultVersion: 1,
        testcases: [
          { order: 1, status: 'AC', score: 60, timeMs: 5, memoryKb: 100 },
          { order: 2, status: 'WA', score: 40, timeMs: 7, memoryKb: 120 }
        ],
        signature: sign(secret2, taskUuid, 'WA', '40.00', 12, 300, 1)
      }
    })
    expect(stale.status()).toBe(409)

    // 学生视角最终状态 AC
    const mine = await request.get(`/api/student/submissions?assignmentTargetId=${targetId}`, {
      headers: { Authorization: `Bearer ${studentToken}` }
    })
    const rows = await mine.json()
    const judged = rows.find((r) => r.idempotencyKey === 'k-t6-3')
    expect(judged.judgeStatus).toBe('AC')
  })

  test('SE 自动重试不消耗提交次数', async ({ request }) => {
    agent3Id = `agent-e2e-3-${runSuffix}`
    const reg = await request.post('/api/judge/v1/agents/register', {
      headers: { 'X-Internal-Token': INTERNAL_TOKEN },
      data: { agentId: agent3Id, displayName: 'E2E Agent 3' }
    })
    const secret3 = (await reg.json()).secret

    const submissionId = await submitFor(request, 'k-t6-4')
    const { taskUuid } = await claimFor(request, agent3Id, secret3, submissionId)

    const se = await request.post(`/api/judge/v1/tasks/${taskUuid}/result`, {
      headers: agentHeaders(agent3Id, secret3),
      data: {
        resultCode: 'SE', normalizedScore: 0, totalTimeMs: 1, peakMemoryKb: 10,
        resultVersion: 1,
        testcases: [
          { order: 1, status: 'SE', score: 0, timeMs: 0, memoryKb: 0 },
          { order: 2, status: 'SE', score: 0, timeMs: 0, memoryKb: 0 }
        ],
        signature: sign(secret3, taskUuid, 'SE', '0.00', 1, 10, 1)
      }
    })
    expect(se.status()).toBe(201)

    // 自动重试生成 attempt 2 任务
    const retry = await claimFor(request, agent3Id, secret3, submissionId, { attempt: 2 })
    expect(retry.attempt).toBe(2)
    expect(retry.taskUuid).not.toBe(taskUuid)
  })

  test('超出最大提交次数被拒绝', async ({ request }) => {
    // 单独发布一个 maxSubmissions=1 的目标班级
    const t = (path, method = 'POST', data) =>
      request[method.toLowerCase()](path, {
        data, headers: { Authorization: `Bearer ${teacherToken}` }
      })
    const assignment = await t('/api/teacher/assignments', 'POST', {
      title: '限次作业', mode: 'HOMEWORK', items: [{ problemId, weight: 100 }]
    })
    const assignmentId = (await assignment.json()).id
    const publish = await t(`/api/teacher/assignments/${assignmentId}/publish`, 'POST', {
      targets: [{
        teachingClassId: classId,
        publishAt: new Date(Date.now() - 3600_000).toISOString().slice(0, 19),
        deadline: new Date(Date.now() + 86400_000).toISOString().slice(0, 19),
        maxSubmissions: 1
      }]
    })
    const limitedTargetId = (await publish.json())[0].id

    const s = (path, data) =>
      request.post(path, { data, headers: { Authorization: `Bearer ${studentToken}` } })
    const first = await s('/api/student/submissions', {
      assignmentTargetId: limitedTargetId, problemId, language: 'CPP',
      code: 'int main(){return 0;}', idempotencyKey: 'k-limit-1'
    })
    expect(first.status()).toBe(200)
    const second = await s('/api/student/submissions', {
      assignmentTargetId: limitedTargetId, problemId, language: 'CPP',
      code: 'int main(){return 0;}', idempotencyKey: 'k-limit-2'
    })
    expect(second.status()).toBe(409)
    expect((await second.json()).code).toBe('SUBMISSION_LIMIT_EXCEEDED')
  })
})
