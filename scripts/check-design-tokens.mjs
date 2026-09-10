/**
 * 设计令牌守卫（棘轮机制）
 * -----------------------------------------------------------------------------
 * 目的：防止间距、字号、颜色继续出现新的硬编码值，让设计系统逐步收敛而非持续发散。
 *
 * 为什么不直接要求"零硬编码"：
 *   代码库中存在大量合理的栅格外取值 —— 1~3px 的描边、46px 的头像、48/64px 的空状态
 *   图标、10px 的微标签等。强行归一到少数令牌只会扭曲设计，而不是改善它。
 *
 * 因此采用棘轮（ratchet）策略：
 *   把当前各文件的硬编码数量记录为基线，只允许减少、不允许增加。
 *   确需新增时，必须显式执行 `--update` 更新基线，使增量在代码评审中可见。
 *
 * 用法：
 *   node scripts/check-design-tokens.mjs           # 校验（超出基线则非零退出）
 *   node scripts/check-design-tokens.mjs --update  # 更新基线
 *   node scripts/check-design-tokens.mjs --report  # 仅打印明细，不判定
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'app/web/src')
const BASELINE_FILE = path.join(ROOT, 'scripts/design-token-baseline.json')

// 4px 基准栅格：命中这些取值的 spacing 视为已令牌化
const SPACE_SCALE = new Set([4, 8, 12, 16, 20, 24, 32, 40])
// 已定义的排版令牌取值
const FONT_SCALE = new Set([10, 11, 12, 13, 14, 15, 16, 18, 20, 24, 32])

const SPACING_RE = /\b(padding|margin|gap)(-top|-right|-bottom|-left|-inline|-block)?\s*:\s*(\d+)px/g
const FONT_RE = /\bfont-size\s*:\s*(\d+)px/g
// 十六进制颜色；token 定义块内的不算（那是令牌自身的取值）
const COLOR_RE = /#[0-9a-fA-F]{3,8}\b/g

function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else if (/\.(vue|css)$/.test(entry.name)) out.push(full)
  }
  return out
}

/** 统计单个文件的三类硬编码数量 */
function measure(file) {
  const raw = fs.readFileSync(file, 'utf8')

  // 剔除令牌定义块（:root / .dark）后再统计颜色，否则会把令牌取值本身算作硬编码
  const withoutTokenBlocks = raw
    .replace(/:root\s*\{[\s\S]*?\n\}/g, '')
    .replace(/(^|\n)\.dark\s*\{[\s\S]*?\n\}/g, '\n')

  let offGridSpacing = 0
  for (const m of raw.matchAll(SPACING_RE)) {
    if (!SPACE_SCALE.has(Number(m[3]))) offGridSpacing += 1
  }

  let hardcodedFont = 0
  for (const m of raw.matchAll(FONT_RE)) {
    if (!FONT_SCALE.has(Number(m[1]))) hardcodedFont += 1
  }

  const hardcodedColor = (withoutTokenBlocks.match(COLOR_RE) ?? []).length

  return { offGridSpacing, hardcodedFont, hardcodedColor }
}

function collect() {
  const result = {}
  for (const file of walk(SRC)) {
    const stats = measure(file)
    const rel = path.relative(ROOT, file).replace(/\\/g, '/')
    if (stats.offGridSpacing || stats.hardcodedFont || stats.hardcodedColor) {
      result[rel] = stats
    }
  }
  return result
}

const current = collect()
const mode = process.argv.includes('--update') ? 'update'
  : process.argv.includes('--report') ? 'report' : 'check'

const KEYS = ['offGridSpacing', 'hardcodedFont', 'hardcodedColor']
const LABELS = { offGridSpacing: '栅格外间距', hardcodedFont: '硬编码字号', hardcodedColor: '硬编码颜色' }

function totals(map) {
  const t = { offGridSpacing: 0, hardcodedFont: 0, hardcodedColor: 0 }
  for (const stats of Object.values(map)) {
    for (const k of KEYS) t[k] += stats[k]
  }
  return t
}

if (mode === 'update') {
  fs.writeFileSync(BASELINE_FILE, JSON.stringify(current, null, 2) + '\n', 'utf8')
  const t = totals(current)
  console.log('已更新基线 scripts/design-token-baseline.json')
  console.log(`  文件 ${Object.keys(current).length} 个｜栅格外间距 ${t.offGridSpacing}｜硬编码字号 ${t.hardcodedFont}｜硬编码颜色 ${t.hardcodedColor}`)
  process.exit(0)
}

if (mode === 'report') {
  const t = totals(current)
  console.log('当前硬编码统计：')
  for (const [file, stats] of Object.entries(current).sort()) {
    console.log(`  ${file}`)
    for (const k of KEYS) if (stats[k]) console.log(`      ${LABELS[k]}: ${stats[k]}`)
  }
  console.log(`合计：${KEYS.map((k) => `${LABELS[k]} ${t[k]}`).join('｜')}`)
  process.exit(0)
}

if (!fs.existsSync(BASELINE_FILE)) {
  console.error('缺少基线文件，请先执行：node scripts/check-design-tokens.mjs --update')
  process.exit(2)
}

const baseline = JSON.parse(fs.readFileSync(BASELINE_FILE, 'utf8'))
const regressions = []

for (const [file, stats] of Object.entries(current)) {
  const base = baseline[file]
  for (const k of KEYS) {
    const before = base ? base[k] : 0
    if (stats[k] > before) {
      regressions.push({ file, key: k, before, after: stats[k] })
    }
  }
}

const before = totals(baseline)
const after = totals(current)
console.log('设计令牌棘轮检查')
console.log(`  栅格外间距  ${before.offGridSpacing} → ${after.offGridSpacing}`)
console.log(`  硬编码字号  ${before.hardcodedFont} → ${after.hardcodedFont}`)
console.log(`  硬编码颜色  ${before.hardcodedColor} → ${after.hardcodedColor}`)

if (regressions.length) {
  console.error('\n检测到硬编码增量（不允许）：')
  for (const r of regressions) {
    console.error(`  ${r.file} · ${LABELS[r.key]}：${r.before} → ${r.after}`)
  }
  console.error('\n请改用设计令牌（间距 --space-1..8、排版 --fs-*、图表 --chart-*）。')
  console.error('确需新增时执行：node scripts/check-design-tokens.mjs --update')
  process.exit(1)
}

console.log('\n通过：未出现新的硬编码值。')
