import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { useGradeExport } from './useGradeExport.js'

let scope
let exporter
let requests
let downloads
let respond
const originalFetch = globalThis.fetch
const originalDocument = globalThis.document
const originalStorage = globalThis.localStorage

beforeEach(() => {
  requests = []
  downloads = []
  globalThis.localStorage = { getItem: () => 'teacher-token', removeItem() {} }
  globalThis.document = {
    body: { appendChild() {} },
    createElement: () => ({ click() { downloads.push(this.download) }, remove() {} })
  }
  globalThis.fetch = async (url, options) => {
    requests.push({ url, ...options })
    return respond(url, options)
  }
  scope = effectScope()
  exporter = scope.run(() => useGradeExport())
})

afterEach(() => {
  scope.stop()
  globalThis.fetch = originalFetch
  if (originalDocument === undefined) delete globalThis.document
  else globalThis.document = originalDocument
  if (originalStorage === undefined) delete globalThis.localStorage
  else globalThis.localStorage = originalStorage
})

const body = { assignmentTargetId: 3, format: 'XLSX' }
const json = (data, status = 200) => new Response(JSON.stringify(data), { status })

test('文件下载携带登录令牌，重复下载重新申请一次性授权', async () => {
  let issued = 0
  respond = (url) => {
    if (url.endsWith('/download-token')) return json({ token: `one use/${++issued}` })
    if (url.includes('/download?')) return new Response('xlsx-content')
    return json({ taskId: 9, status: 'READY' })
  }
  await exporter.exportGrades(body)
  assert.equal(exporter.canDownload.value, true)
  await exporter.download()
  await exporter.download()
  assert.equal(issued, 2)
  assert.deepEqual(downloads, ['grades-9.xlsx', 'grades-9.xlsx'])
  assert.ok(requests.every(r => r.headers.Authorization === 'Bearer teacher-token'))
  assert.ok(requests.some(r => r.url.includes('token=one%20use%2F1')))
})

test('生成过程中阻止重复导出，切换目标后忽略旧响应', async () => {
  let resolve
  respond = () => new Promise(done => { resolve = done })
  const pending = exporter.exportGrades(body)
  await exporter.exportGrades(body)
  assert.equal(requests.length, 1)
  assert.equal(exporter.exporting.value, true)
  exporter.reset()
  resolve(json({ taskId: 9, status: 'READY' }))
  await pending
  assert.equal(exporter.canDownload.value, false)
  assert.equal(exporter.exporting.value, false)
  assert.equal(requests.length, 1)
})

test('导出失败有提示并允许重新导出', async () => {
  respond = () => json({ message: '当前班级无导出权限' }, 403)
  await exporter.exportGrades(body)
  assert.equal(exporter.exportError.value, '当前班级无导出权限')
  assert.equal(exporter.exporting.value, false)
  respond = () => json({ taskId: 10, status: 'READY' })
  await exporter.exportGrades({ ...body, format: 'CSV' })
  assert.equal(exporter.exportError.value, '')
  assert.equal(exporter.canDownload.value, true)
})

test('下载错误不会作为文件保存，重试时重新申请授权', async () => {
  let failed = true
  respond = (url) => {
    if (url.endsWith('/download-token')) return json({ token: 'single-use' })
    if (url.includes('/download?')) return failed ? json({ message: '下载令牌已过期' }, 400) : new Response('zip')
    return json({ taskId: 10, status: 'READY' })
  }
  await exporter.exportGrades({ ...body, format: 'CSV' })
  await exporter.download()
  assert.equal(exporter.exportError.value, '下载令牌已过期')
  assert.equal(exporter.downloading.value, false)
  assert.deepEqual(downloads, [])
  failed = false
  await exporter.download()
  assert.deepEqual(downloads, ['grades-10.zip'])
  assert.equal(requests.filter(r => r.url.endsWith('/download-token')).length, 2)
})

test('卸载页面后清理轮询，不继续请求状态', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  respond = () => json({ taskId: 9, status: 'QUEUED' })
  await exporter.exportGrades(body)
  const count = requests.length
  scope.stop()
  t.mock.timers.tick(3000)
  assert.equal(requests.length, count)
})

test('服务端生成失败后停止等待并显示重试提示', async () => {
  respond = () => json({ taskId: 9, status: 'FAILED' })
  await exporter.exportGrades(body)
  assert.equal(exporter.exporting.value, false)
  assert.equal(exporter.canDownload.value, false)
  assert.match(exporter.exportError.value, /导出失败/)
})
