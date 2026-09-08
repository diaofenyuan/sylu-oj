import { test } from 'node:test'
import assert from 'node:assert/strict'
import { summarizeSubmissions } from './submissionSummary.js'

test('后续错误提交不抹掉已通过进度', () => {
  const { bestScore, status, latest } = summarizeSubmissions([
    { submissionId: 1, judgeStatus: 'AC', normalizedScore: 100 },
    { submissionId: 2, judgeStatus: 'WA', normalizedScore: 20 }
  ])
  assert.equal(bestScore, 100)
  assert.equal(status, 'AC')
  assert.equal(latest.judgeStatus, 'WA')
})
test('历史部分分与最新待判状态同时保留', () => {
  const result = summarizeSubmissions([
    { submissionId: 1, judgeStatus: 'WA', normalizedScore: 65 },
    { submissionId: 2, judgeStatus: 'PD' }
  ])
  assert.equal(result.bestScore, 65)
  assert.equal(result.status, 'PD')
})
test('相同时间按提交编号排序，且不修改传入数组', () => {
  const submissions = [{ submissionId: 12, submittedAt: '2026-09-08T10:00:00' }, { submissionId: 11, submittedAt: '2026-09-08T10:00:00' }]
  assert.equal(summarizeSubmissions(submissions).latest.submissionId, 12)
  assert.equal(submissions[0].submissionId, 12)
})
test('没有记录时显示未开始和零分', () => {
  const result = summarizeSubmissions([])
  assert.equal(result.status, 'UNATTEMPTED')
  assert.equal(result.bestScore, 0)
  assert.equal(result.latest, null)
})
