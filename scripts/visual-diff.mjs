/**
 * 截图回归比对
 * -----------------------------------------------------------------------------
 * 用途：在做"应等价"的样式改动（如设计令牌替换）时，验证渲染结果是否真的没有变化。
 *
 * 为什么需要容差：
 *   截图并非逐字节确定 —— 代码编辑器的光标闪烁、过渡动画的取帧时刻、抗锯齿等因素
 *   都会带来微小差异。因此按"差异像素占比"判定，而不是比对文件哈希。
 *
 * 用法：
 *   node scripts/visual-diff.mjs --snapshot          # 把当前截图固化为基线
 *   node scripts/visual-diff.mjs                     # 与基线比对（默认容差 0.5%）
 *   node scripts/visual-diff.mjs <基线> <对比> <容差%>
 *
 * 输出：差异超限的图片写入 var/shots-diff/，便于定位
 * 退出码：存在超限项时为 1
 */
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'

const SNAPSHOT = process.argv.includes('--snapshot')
const args = process.argv.slice(2).filter((a) => !a.startsWith('--'))
const [baseDir, targetDir, toleranceArg] = args
const BASELINE = baseDir || 'var/shots-baseline'
const TARGET = targetDir || 'var/shots'
const TOLERANCE = Number(toleranceArg ?? 0.5) // 允许的差异像素占比（%）
const DIFF_DIR = path.join(path.dirname(TARGET), 'shots-diff')

/**
 * 已知含非确定性渲染元素的截图，仅供人工参考，不参与通过/失败判定：
 *   - CodeMirror 光标闪烁（workbench / problem-bank）
 *   - 过渡动画的取帧时刻（toast / overlay）
 *   - 入场动效的触发时机（login）
 *   - 加载态截图：抓取时机取决于接口延迟与渲染速度（loading-*）
 * 实测同代码两次运行的噪声上限约 0.34%，故默认容差取 0.5%。
 */
const NONDETERMINISTIC = [
  /^state-toast\.png$/,
  /^teacher-problem-bank\.png$/,
  /^student-practice-(workbench|mobile)\.png$/,
  /^login-dark\.png$/,
  /^a11y-overlay-open\.png$/,
  /^a11y-table-sorted\.png$/,
  /^student-contest\.png$/,
  /^loading-.*\.png$/
]

const isNoisy = (name) => NONDETERMINISTIC.some((re) => re.test(name))

if (!fs.existsSync(TARGET)) {
  console.error(`对比目录不存在：${TARGET}（请先运行 node scripts/ui-smoke.mjs 生成截图）`)
  process.exit(2)
}

if (SNAPSHOT) {
  fs.rmSync(BASELINE, { recursive: true, force: true })
  fs.cpSync(TARGET, BASELINE, { recursive: true })
  const count = fs.readdirSync(BASELINE).filter((f) => f.endsWith('.png')).length
  console.log(`已将 ${count} 张截图固化为基线：${BASELINE}`)
  process.exit(0)
}

if (!fs.existsSync(BASELINE)) {
  console.error(`基线目录不存在：${BASELINE}（先用 --snapshot 建立基线）`)
  process.exit(2)
}

fs.mkdirSync(DIFF_DIR, { recursive: true })

const files = fs.readdirSync(BASELINE).filter((f) => f.endsWith('.png'))
if (!files.length) {
  console.error(`${BASELINE} 中没有 PNG 文件`)
  process.exit(2)
}

// 基线中不存在但对比目录中新增的截图：属信息项，不计入回归
const added = fs.readdirSync(TARGET)
  .filter((f) => f.endsWith('.png') && !files.includes(f))

const results = []

for (const file of files) {
  const basePath = path.join(BASELINE, file)
  const targetPath = path.join(TARGET, file)

  if (!fs.existsSync(targetPath)) {
    results.push({ file, status: 'missing', ratio: Infinity })
    continue
  }
  const base = PNG.sync.read(fs.readFileSync(basePath))
  const target = PNG.sync.read(fs.readFileSync(targetPath))

  if (base.width !== target.width || base.height !== target.height) {
    results.push({
      file,
      status: 'size-changed',
      ratio: Infinity,
      detail: `基线 ${base.width}x${base.height} → 现在 ${target.width}x${target.height}`
    })
    continue
  }

  const output = new PNG({ width: base.width, height: base.height })
  const diffPixels = pixelmatch(
    base.data, target.data, output.data,
    base.width, base.height,
    { threshold: 0.1, includeAA: false }
  )

  const total = base.width * base.height
  const ratio = (diffPixels / total) * 100

  if (ratio > TOLERANCE) {
    fs.writeFileSync(path.join(DIFF_DIR, file), PNG.sync.write(output))
  }

  results.push({
    file,
    status: ratio > TOLERANCE ? (isNoisy(file) ? 'noisy' : 'changed') : 'ok',
    ratio,
    diffPixels,
    total,
    noisy: isNoisy(file)
  })
}

const changed = results.filter((r) => r.status === 'changed')
const noisy = results.filter((r) => r.status === 'noisy')

console.log('文件'.padEnd(34) + '差异像素'.padStart(10) + '  占比')
console.log('-'.repeat(56))
for (const r of results.sort((a, b) => (b.ratio ?? 0) - (a.ratio ?? 0))) {
  const ratioText = r.ratio === Infinity ? '—' : `${r.ratio.toFixed(3)}%`
  const mark = r.status === 'ok' ? '✓' : r.status === 'noisy' ? '~' : '✗'
  const note = r.status === 'noisy' ? '  (已知非确定)' : ''
  console.log(`${mark} ${r.file.padEnd(32)}${String(r.diffPixels ?? '—').padStart(8)}  ${ratioText}${note}`)
}

console.log('-'.repeat(56))
console.log(`共 ${results.length} 张，容差 ${TOLERANCE}%；超限 ${changed.length} 张，其中已知非确定 ${noisy.length} 张`)
if (added.length) {
  console.log(`新增截图 ${added.length} 张（未与基线比对）：${added.join(', ')}`)
}
if (changed.length) {
  console.log(`差异图已输出到 ${DIFF_DIR}`)
  console.log('注意：需人工确认差异是否来自本次改动，而非动效取帧。')
}

process.exit(changed.length ? 1 : 0)
