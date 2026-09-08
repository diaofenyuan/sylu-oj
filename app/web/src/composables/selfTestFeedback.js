export function normalizeOutput(text) {
  return String(text ?? '').split('\n').map(line => line.replace(/\s+$/, '')).join('\n').replace(/\n+$/, '')
}

export function selfTestFeedback(result, expected) {
  if (!result) return null
  const failure = text => ({ text, className: 'chip-bad', failed: true, comparable: false })
  if (result.phase === 'REQUEST_ERROR') return failure('自测请求失败')
  if (result.phase === 'COMPILE_ERROR' || result.compileError) return failure('编译失败')
  if (result.phase === 'TIMEOUT' || result.timedOut) return failure('运行超时')
  if (result.exitCode != null && result.exitCode !== 0) return failure(`运行异常（退出码 ${result.exitCode}）`)
  if (result.phase !== 'FINISHED') return failure('运行未完成')
  // 没有标准答案的自定义输入只能确认运行完成，不能推断答案正确性。
  if (expected == null) return { text: '运行完成（自定义输入）', className: 'chip-muted', failed: false, comparable: false }
  const passed = normalizeOutput(expected) === normalizeOutput(result.output)
  return { text: passed ? '样例通过' : '与样例输出不一致', className: passed ? 'chip-ok' : 'chip-bad', failed: !passed, comparable: true }
}
