// @ts-check
import { test, expect } from '@playwright/test'

/**
 * Task 4 教师工作流 API 级验收（Playwright request，无需浏览器）。
 * 覆盖：管理员组织维护 → 教师组卷发布 → 学生提交 → 判题结果 → 成绩分析 → 成绩导出。
 * 针对 dev 服务（OJ 合成账号 + 内部判题联调接口），生产由 Task 5/6 替换认证与判题通道。
 */

const INTERNAL_TOKEN = process.env.OJ_DEV_INTERNAL_TOKEN || 'dev-internal-only'

function uniq(prefix) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}`
}

let adminToken
let teacherToken
let studentToken
let teacherId
let studentId
let classId
let targetId
let problemId

test.describe('教师工作流', () => {
  test('管理员维护组织并创建合成账号', async ({ request }) => {
    const suffix = uniq('t')
    // 1. 管理员登录（bootstrap admin）
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

    // 2. 学期/课程/教学班
    const term = await admin('/api/admin/terms', 'POST', {
      code: `T-${suffix}`, name: '测试学期',
      startDate: '2026-01-01', endDate: '2026-12-31'
    })
    expect(term.status()).toBe(200)
    const termId = (await term.json()).id

    const course = await admin('/api/admin/courses', 'POST', {
      code: `C-${suffix}`, name: '程序设计', credit: 3
    })
    const courseId = (await course.json()).id

    const cls = await admin('/api/admin/teaching-classes', 'POST', {
      termId, courseId, code: `A-${suffix}`, name: '测试教学班'
    })
    classId = (await cls.json()).id

    // 3. 教师与学生
    const teacher = await admin('/api/admin/teachers', 'POST', { staffNo: `TNO-${suffix}`, name: '测试教师' })
    teacherId = (await teacher.json()).id
    const student = await admin('/api/admin/students', 'POST', { studentNo: `SNO-${suffix}`, name: '测试学生' })
    studentId = (await student.json()).id

    // 4. 授课与选课
    await admin('/api/admin/teacher-assignments', 'POST', {
      teachingClassId: classId, teacherId, role: 'PRIMARY'
    })
    await admin('/api/admin/enrollments', 'POST', { teachingClassId: classId, studentId, transfer: false })

    // 5. 合成账号
    const ta = await admin('/api/admin/dev-accounts', 'POST', {
      role: 'TEACHER', loginName: `teach-${suffix}`, password: 'password123', teacherId, studentId: null
    })
    expect(ta.status()).toBe(200)
    const sa = await admin('/api/admin/dev-accounts', 'POST', {
      role: 'STUDENT', loginName: `stud-${suffix}`, password: 'password123', teacherId: null, studentId
    })
    expect(sa.status()).toBe(200)

    // 6. 教师/学生登录
    const tl = await request.post('/api/auth/login', {
      data: { loginName: `teach-${suffix}`, password: 'password123' }
    })
    teacherToken = (await tl.json()).token
    const sl = await request.post('/api/auth/login', {
      data: { loginName: `stud-${suffix}`, password: 'password123' }
    })
    studentToken = (await sl.json()).token
    expect(teacherToken).toBeTruthy()
    expect(studentToken).toBeTruthy()
  })

  test('教师建题库、组卷并发布；权重和不为 100 被拒', async ({ request }) => {
    const t = (path, method = 'POST', data) =>
      request[method.toLowerCase()](path, {
        data, headers: { Authorization: `Bearer ${teacherToken}` }
      })

    // 建题库
    const bank = await t('/api/teacher/problem-banks', 'POST', { teachingClassId: classId, name: '默认题库' })
    const bankId = (await bank.json()).id

    // 建题（含样例与隐藏用例）
    const problem = await t('/api/teacher/problems', 'POST', {
      bankId, code: 'P1', title: 'A+B', description: '求和',
      languages: ['C', 'CPP'], maxScore: 100,
      testcases: [
        { orderNum: 1, sample: true, input: '1 2', expectedOutput: '3', score: 50 },
        { orderNum: 2, sample: false, input: '10 20', expectedOutput: '30', score: 50 }
      ]
    })
    expect(problem.status()).toBe(200)
    problemId = (await problem.json()).id
    await t(`/api/teacher/problems/${problemId}/publish`, 'PUT')

    // 权重和不为 100 → 拒绝
    const bad = await t('/api/teacher/assignments', 'POST', {
      title: '作业', mode: 'HOMEWORK', items: [{ problemId, weight: 60 }]
    })
    expect(bad.status()).toBe(409)

    // 权重和为 100 → 成功
    const good = await t('/api/teacher/assignments', 'POST', {
      title: '作业', mode: 'HOMEWORK', items: [{ problemId, weight: 100 }]
    })
    expect(good.status()).toBe(200)
    const assignmentId = (await good.json()).id

    // 发布到目标班级
    const publish = await t(`/api/teacher/assignments/${assignmentId}/publish`, 'POST', {
      targets: [{
        teachingClassId: classId,
        publishAt: new Date(Date.now() - 3600_000).toISOString().slice(0, 19),
        deadline: new Date(Date.now() + 86400_000).toISOString().slice(0, 19),
        maxSubmissions: 5
      }]
    })
    expect(publish.status()).toBe(200)
    targetId = (await publish.json())[0].id
  })

  test('学生提交并注入判题结果，成绩分析与导出正确', async ({ request }) => {
    const s = (path, method = 'POST', data) =>
      request[method.toLowerCase()](path, {
        data, headers: { Authorization: `Bearer ${studentToken}` }
      })

    // 学生提交
    const sub = await s('/api/student/submissions', 'POST', {
      assignmentTargetId: targetId, problemId, language: 'CPP',
      code: 'int main(){return 0;}', idempotencyKey: 'k-1'
    })
    expect(sub.status()).toBe(200)
    const submissionId = (await sub.json()).submissionId

    // 幂等重放返回同一提交
    const replay = await s('/api/student/submissions', 'POST', {
      assignmentTargetId: targetId, problemId, language: 'CPP',
      code: 'int main(){return 0;}', idempotencyKey: 'k-1'
    })
    expect((await replay.json()).submissionId).toBe(submissionId)

    // 内部判题接口注入结果（dev-only）
    const judge = await request.post('/internal/judge/results', {
      headers: { 'X-Internal-Token': INTERNAL_TOKEN },
      data: { submissionId, resultCode: 'AC', normalizedScore: 100, totalTimeMs: 12, peakMemoryKb: 300 }
    })
    expect(judge.status()).toBe(200)

    // 教师查看分析
    const analytics = await request.get(`/api/teacher/analytics/targets/${targetId}`, {
      headers: { Authorization: `Bearer ${teacherToken}` }
    })
    expect(analytics.status()).toBe(200)
    const body = await analytics.json()
    expect(body.rows.length).toBeGreaterThan(0)
    expect(body.rows[0].totalScore).toBe(100)

    // 导出并下载
    const t = (path, method = 'POST', data) =>
      request[method.toLowerCase()](path, {
        data, headers: { Authorization: `Bearer ${teacherToken}` }
      })
    const exportTask = await t('/api/teacher/exports', 'POST', {
      assignmentTargetId: targetId, format: 'CSV', filterStudentNo: null, filterNameKeyword: null
    })
    const exportId = (await exportTask.json()).taskId

    // 轮询状态至 READY
    let status = ''
    for (let i = 0; i < 20; i++) {
      const st = await request.get(`/api/teacher/exports/${exportId}`, {
        headers: { Authorization: `Bearer ${teacherToken}` }
      })
      status = (await st.json()).status
      if (status === 'READY' || status === 'FAILED') break
      await new Promise((r) => setTimeout(r, 500))
    }
    expect(status).toBe('READY')

    // 单次下载
    const tok = await t(`/api/teacher/exports/${exportId}/download-token`, 'POST', {})
    const token = (await tok.json()).token
    const download = await request.get(`/api/teacher/exports/download?token=${token}`, {
      headers: { Authorization: `Bearer ${teacherToken}` }
    })
    expect(download.status()).toBe(200)
    expect(download.headers()['content-disposition']).toContain('.zip')

    // 第二次下载被拒（单次授权）
    const second = await request.get(`/api/teacher/exports/download?token=${token}`, {
      headers: { Authorization: `Bearer ${teacherToken}` }
    })
    expect(second.status()).toBe(403)
  })

  test('未认证访问被拒绝', async ({ request }) => {
    const res = await request.get('/api/teacher/classes')
    expect(res.status()).toBe(401)
  })
})
