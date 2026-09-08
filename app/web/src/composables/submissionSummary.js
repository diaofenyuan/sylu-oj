export function summarizeSubmissions(submissions) {
  const ordered = [...submissions].sort((a, b) =>
    String(a.submittedAt ?? '').localeCompare(String(b.submittedAt ?? ''))
    || Number(a.submissionId) - Number(b.submissionId))
  const latest = ordered.at(-1) ?? null
  const bestScore = ordered.reduce((best, submission) =>
    Math.max(best, submission.judgeStatus === 'AC' ? 100 : Number(submission.normalizedScore ?? 0)), 0)
  // 题目进度采用历史最好成绩；执行结果和提交记录仍展示每一次的真实状态。
  return { ordered, latest, bestScore, status: bestScore >= 100 ? 'AC' : latest?.judgeStatus ?? 'UNATTEMPTED' }
}
