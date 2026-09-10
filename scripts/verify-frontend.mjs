/**
 * 前端校验编排
 * -----------------------------------------------------------------------------
 * 把「启动开发服务器 → 等待就绪 → 跑 UI 冒烟 → 关闭服务器」收敛为一条命令，
 * 本地与 CI 共用，避免把进程编排写进 CI 配置里。
 *
 * 用法：
 *   node scripts/verify-frontend.mjs            # 默认端口 5178
 *   PORT=5199 node scripts/verify-frontend.mjs  # 指定端口
 *   BASE_URL=http://127.0.0.1:5200 node scripts/verify-frontend.mjs  # 复用已启动的服务
 *
 * 退出码：冒烟断言失败为 1，环境准备失败为 2。
 */
import { spawn } from 'node:child_process'
import path from 'node:path'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PORT = Number(process.env.PORT || 5178)
const HOST = '127.0.0.1'
// 提供 BASE_URL 时直接复用外部已启动的服务（CI 中可按需拆分步骤）
const EXTERNAL_BASE = process.env.BASE_URL || ''
const BASE = EXTERNAL_BASE || `http://${HOST}:${PORT}`

const WEB_DIR = path.join(ROOT, 'app', 'web')

function log(msg) {
  console.log(`[verify] ${msg}`)
}

/** 轮询等待服务可访问；用 HTTP 状态而非端口探测，确保 Vite 已完成初始化 */
async function waitForServer(timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    // 进程已退出（例如前置的图标生成失败）时立即失败，不空等超时
    if (serverExited) {
      throw new Error(`开发服务器进程提前退出（退出码 ${serverExitCode}）`)
    }
    try {
      const res = await fetch(`${BASE}/login`, { redirect: 'manual' })
      if (res.status > 0) return true
    } catch {
      // 尚未监听，继续等待
    }
    await new Promise((r) => setTimeout(r, 400))
  }
  return false
}

function run(command, args, options = {}) {
  return new Promise((resolve) => {
    const child = spawn(command, args, { stdio: 'inherit', cwd: ROOT, ...options })
    child.on('close', (code) => resolve(code ?? 1))
    child.on('error', () => resolve(1))
  })
}

let server = null
let exitCode = 0
// 供 waitForServer 判断服务器是否已异常退出
let serverExited = false
let serverExitCode = null

async function cleanup() {
  if (!server || server.killed) return
  // 先尝试优雅终止，超时后强制结束，避免 CI 上残留进程
  const pid = server.pid
  server.kill('SIGTERM')
  await new Promise((r) => setTimeout(r, 1500))
  try {
    if (process.platform === 'win32') {
      spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' })
    } else {
      process.kill(pid, 'SIGKILL')
    }
  } catch {
    // 进程可能已退出
  }
  log('开发服务器已关闭')
}

try {
  if (!EXTERNAL_BASE) {
    if (!fs.existsSync(path.join(WEB_DIR, 'node_modules'))) {
      console.error('[verify] app/web/node_modules 不存在，请先执行：npm --prefix app/web install')
      process.exit(2)
    }

    log(`启动开发服务器（端口 ${PORT}）`)
    const isWindows = process.platform === 'win32'
    // Windows 上 Node 出于安全考虑不允许直接 spawn .cmd，需经由 shell；
    // 关闭 shell 时会抛 EINVAL。此处参数不含空格，经 shell 拼接是安全的。
    server = spawn(
      isWindows ? 'npm.cmd' : 'npm',
      ['--prefix', 'app/web', 'run', 'dev', '--', '--port', String(PORT), '--host', HOST],
      { cwd: ROOT, shell: isWindows, stdio: ['ignore', 'pipe', 'pipe'] }
    )
    // 仅保留错误输出，避免 Vite 的正常日志淹没冒烟结果
    server.stdout.on('data', () => {})
    server.stderr.on('data', (chunk) => process.stderr.write(chunk))
    server.on('error', (e) => {
      console.error('[verify] 开发服务器启动失败:', e.message)
    })
    server.on('exit', (code) => {
      serverExited = true
      serverExitCode = code
    })

    if (!(await waitForServer())) {
      console.error(`[verify] 等待 ${BASE} 就绪超时`)
      await cleanup()
      process.exit(2)
    }
    log('开发服务器就绪')
  } else {
    log(`复用外部服务：${BASE}`)
  }

  log('运行 UI 冒烟断言')
  const smokeCode = await run(process.execPath, [path.join(ROOT, 'scripts', 'ui-smoke.mjs')], {
    env: { ...process.env, BASE_URL: BASE }
  })
  if (smokeCode !== 0) exitCode = 1
} catch (e) {
  console.error('[verify] 执行异常:', e)
  exitCode = 2
} finally {
  await cleanup()
}

log(exitCode === 0 ? '全部通过' : '存在失败项')
process.exit(exitCode)
