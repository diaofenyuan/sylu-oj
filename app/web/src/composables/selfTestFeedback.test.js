import { test } from 'node:test'
import assert from 'node:assert/strict'
import { selfTestFeedback } from './selfTestFeedback.js'

const finished = { phase: 'FINISHED', exitCode: 0, output: '3\r\n' }
test('样例对比容忍行尾空白，并区分内容不一致', () => {
  assert.equal(selfTestFeedback(finished, '3  \n').text, '样例通过')
  assert.equal(selfTestFeedback(finished, '4').text, '与样例输出不一致')
})
test('自定义输入没有标准答案时使用中性提示', () => {
  const result = selfTestFeedback(finished)
  assert.equal(result.text, '运行完成（自定义输入）')
  assert.equal(result.failed, false)
  assert.equal(result.comparable, false)
})
test('空字符串标准答案仍参与比较', () => {
  assert.equal(selfTestFeedback({ ...finished, output: '' }, '').text, '样例通过')
  assert.equal(selfTestFeedback(finished, '').failed, true)
})
test('非零退出码不能因为输出匹配而显示通过', () => {
  const result = selfTestFeedback({ ...finished, exitCode: 1 }, '3')
  assert.match(result.text, /运行异常/)
  assert.equal(result.comparable, false)
})
for (const [payload, text] of [
  [{ phase: 'REQUEST_ERROR' }, '自测请求失败'],
  [{ phase: 'COMPILE_ERROR' }, '编译失败'],
  [{ ...finished, timedOut: true }, '运行超时'],
  [{ phase: 'PENDING' }, '运行未完成']
]) {
  test(text, () => assert.equal(selfTestFeedback(payload, '3').text, text))
}
