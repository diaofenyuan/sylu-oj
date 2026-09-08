import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createDraftStore, draftKey } from './draftStore.js'

const context = { userId: 1, mode: 'practice', targetId: null, problemId: 8, language: 'CPP' }
function storage() {
  const data = new Map()
  return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value) }
}

test('草稿按账号、模式、作业、题目和语言隔离', () => {
  const store = createDraftStore(() => storage())
  store.save(context, 'my code')
  assert.equal(store.read(context), 'my code')
  for (const changed of [{ userId: 2 }, { mode: 'assignment' }, { targetId: 9 }, { problemId: 9 }, { language: 'JAVA' }]) {
    assert.notEqual(draftKey(context), draftKey({ ...context, ...changed }))
    assert.equal(store.read({ ...context, ...changed }), null)
  }
})

test('主动清空的草稿刷新后仍为空字符串', () => {
  const disk = storage()
  createDraftStore(() => disk).save(context, '')
  assert.equal(createDraftStore(() => disk).read(context), '')
})

test('存储失败时提示错误，并在页面内保留未落盘草稿', () => {
  let errors = 0
  const store = createDraftStore(() => { throw new Error('Storage unavailable') }, () => errors++)
  assert.equal(store.read(context), null)
  assert.equal(store.save(context, 'unsaved code'), false)
  assert.equal(store.read(context), 'unsaved code')
  assert.equal(errors, 2)
})

test('旧版草稿仅通过显式恢复入口读取，保留原数据', () => {
  const disk = storage()
  disk.setItem('oj-practice-draft-8-CPP', 'old code')
  const store = createDraftStore(() => disk)
  assert.equal(store.read(context), null)
  assert.equal(store.readLegacy(8, 'CPP'), 'old code')
  assert.equal(disk.getItem('oj-practice-draft-8-CPP'), 'old code')
})
