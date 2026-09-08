export function draftKey({ userId, mode, targetId, problemId, language }) {
  return `oj-draft-v2:${userId}:${mode}:${targetId ?? 'practice'}:${problemId}:${language}`
}

export function createDraftStore(getStorage, onError = () => {}) {
  // 存储额度不足时仍在当前页面保留草稿，避免切题后丢失尚未落盘的代码。
  const memory = new Map()
  return {
    read(context) {
      const key = draftKey(context)
      if (memory.has(key)) return memory.get(key)
      try {
        return getStorage().getItem(key)
      } catch {
        onError()
        return null
      }
    },
    save(context, code) {
      const key = draftKey(context)
      memory.set(key, code)
      try {
        getStorage().setItem(key, code)
        return true
      } catch {
        onError()
        return false
      }
    },
    readLegacy(problemId, language) {
      // 旧键没有账号与作业归属，只允许用户明确选择恢复，不自动混入新草稿。
      try {
        const storage = getStorage()
        return storage.getItem(`oj-practice-draft-${problemId}-${language}`)
          ?? storage.getItem(`oj-practice-draft-${problemId}`)
      } catch {
        onError()
        return null
      }
    }
  }
}
