/**
 * 图标子集生成脚本
 * -----------------------------------------------------------------------------
 * 背景：
 *   项目使用 `<Icon icon="mdi:xxx" />` 的字符串写法。@iconify/vue 在图标未注册时
 *   会向 https://api.iconify.design 发起运行时请求，导致内网/离线环境图标全部丢失。
 *
 * 做法：
 *   扫描 src/ 下全部 .vue / .js 中出现的 `mdi:xxx` 字面量，从 @iconify-json/mdi
 *   中抽取对应图标，生成 src/icons/mdi-subset.json。
 *   直接 `import { icons } from '@iconify-json/mdi'` 会把整套图标（约 1.4 MB）打进
 *   产物且无法被 tree-shaking 剔除，因此改为构建期抽取子集。
 *
 * 用法：
 *   npm --prefix app/web run icons
 *
 * 注意：
 *   新增图标后必须重新执行本脚本，否则该图标不会渲染。
 *   脚本会打印未在图标集中找到的名称，并以非零码退出。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const WEB_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC_DIR = path.join(WEB_ROOT, 'src')
const OUT_FILE = path.join(SRC_DIR, 'icons', 'mdi-subset.json')

// 递归收集待扫描文件
function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else if (/\.(vue|js)$/.test(entry.name)) out.push(full)
  }
  return out
}

/**
 * 图标注册模块自身文档中会出现 `mdi:xxx` 这类占位示例，
 * 它们不是真实用法，扫描时必须排除，否则会因"图标不存在"而中断构建。
 */
const SKIP_DIRS = [path.join(SRC_DIR, 'icons')]
const isSkipped = (file) => SKIP_DIRS.some((d) => file.startsWith(d + path.sep))

// 匹配字符串字面量中的图标名，例如 icon="mdi:check" 或 'mdi:check'。
// 额外排除占位样式（xxx / yyy / example）以免文档示例被当作真实依赖。
const ICON_RE = /["'`]((?:mdi):[a-z0-9-]+)["'`]/g
const PLACEHOLDER_RE = /^mdi:(x+|y+|z+|example|name|icon)$/

const found = new Set()
for (const file of walk(SRC_DIR)) {
  if (isSkipped(file)) continue
  const text = fs.readFileSync(file, 'utf8')
  for (const m of text.matchAll(ICON_RE)) {
    if (PLACEHOLDER_RE.test(m[1])) continue
    found.add(m[1])
  }
}

if (found.size === 0) {
  console.error('[gen-icons] 未扫描到任何图标，请检查扫描规则是否失效')
  process.exit(1)
}

// 从 @iconify-json/mdi 读取图标数据
const mdi = require('@iconify-json/mdi/icons.json')

const subset = {}
const missing = []
for (const full of [...found].sort()) {
  const name = full.slice('mdi:'.length)
  const data = mdi.icons[name]
  if (!data) { missing.push(full); continue }
  subset[name] = data
}

if (missing.length) {
  console.error(`[gen-icons] 以下图标在 @iconify-json/mdi 中不存在：\n  ${missing.join('\n  ')}`)
  process.exit(1)
}

const payload = {
  // 保留前缀与来源信息，便于追踪
  prefix: 'mdi',
  icons: subset,
  width: mdi.width,
  height: mdi.height
}

fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true })
fs.writeFileSync(OUT_FILE, JSON.stringify(payload) + '\n', 'utf8')

const kb = (Buffer.byteLength(JSON.stringify(payload)) / 1024).toFixed(1)
console.log(`[gen-icons] 已写入 ${path.relative(WEB_ROOT, OUT_FILE)}：${Object.keys(subset).length} 个图标，${kb} KB`)
