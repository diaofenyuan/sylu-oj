<template>
  <div class="oj-workbench">
    <!-- 顶部工具条 -->
    <div class="wb-topbar">
      <button class="tb-btn" @click="drawer = true">☰ 题目列表</button>
      <div class="tb-title">
        <strong>{{ selected ? displayCode(selected) + ' ' + selected.title : title || (isAssignment ? '作业题目' : '刷题中心') }}</strong>
        <span v-if="selected" class="chip" :class="stateClass(selected.status)">{{ stateText(selected.status) }}</span>
      </div>
      <div class="tb-nav">
        <button class="tb-btn" :disabled="!hasPrev" @click="step(-1)">‹ 上一题</button>
        <button class="tb-btn" :disabled="!hasNext" @click="step(1)">下一题 ›</button>
      </div>
      <div class="tb-progress">
        <strong>{{ passedCount }}</strong>/<span>{{ problems.length }}</span>
        <small>已通过</small>
      </div>
    </div>

    <!-- 双栏主体 -->
    <div class="wb-body">
      <!-- 左栏:题面 -->
      <section class="wb-left" :style="{ width: leftWidth + 'px' }">
        <div v-if="isAssignment && meta" class="assign-meta">
          <strong v-if="title" class="assign-title">{{ title }}</strong>
          <span class="chip" :class="meta.mode === 'EXAM' ? 'chip-warn' : 'chip-primary'">
            {{ meta.mode === 'EXAM' ? '正式考试' : '普通作业' }}
          </span>
          <span class="chip" :class="winClass(meta.window)">{{ winLabel(meta.window) }}</span>
          <span class="meta-item" v-if="meta.publishAt">发布：{{ fmtTime(meta.publishAt).slice(0, 16) }}</span>
          <span class="meta-item" v-if="meta.deadline">截止：{{ fmtTime(meta.deadline).slice(0, 16) }}</span>
          <span class="meta-item" v-if="meta.maxSubmissions">已提交 {{ meta.attemptCount ?? 0 }}/{{ meta.maxSubmissions }} 次</span>
          <span v-if="meta.window === 'CLOSED'" class="meta-item closed-tip">已收卷，禁止提交（可查看题目与成绩）</span>
        </div>
        <template v-if="selected">
          <header class="pr-head">
            <div class="pr-title">
              <h3>{{ selected.title }}</h3>
            </div>
            <div class="pr-meta">
              <span v-if="selected.difficulty" class="chip" :class="diffChipClass(selected.difficulty)">{{ difficultyLabel(selected.difficulty) }}</span>
              <span class="meta-item">时间限制:{{ Math.round(selected.timeLimitMs / 1000) }}s</span>
              <span class="meta-item">空间限制:{{ selected.memoryLimitMb }}M</span>
              <span class="meta-item">最佳:{{ selected.bestScore }} 分</span>
            </div>
          </header>

          <div class="pr-scroll">
            <div class="pr-desc">{{ selected.description }}</div>

            <div v-if="selected.samples?.length" class="pr-samples">
              <div v-for="sample in selected.samples" :key="sample.orderNum" class="sample-box">
                <div class="sample-head">
                  <strong>示例 {{ sample.orderNum }}</strong>
                  <span class="spacer"></span>
                  <button class="mini-btn" @click="copyText(sample.input, '输入已复制')">复制输入</button>
                  <button v-if="sample.expectedOutput" class="mini-btn" @click="copyText(sample.expectedOutput, '输出已复制')">复制输出</button>
                </div>
                <div class="sample-io"><span>输入</span><pre>{{ sample.input }}</pre></div>
                <div class="sample-io" v-if="sample.expectedOutput"><span>输出</span><pre>{{ sample.expectedOutput }}</pre></div>
              </div>
            </div>

            <p class="pr-tip">提交后代码将进入隔离沙盒执行全部隐藏用例;自测运行不占提交次数。</p>
          </div>
        </template>
        <div v-else class="pr-empty">{{ loading ? '题目加载中…' : '从右上角「题目列表」选择一道题' }}</div>
      </section>

      <!-- 可拖拽分隔条 -->
      <div class="wb-splitter" @mousedown="startDrag"></div>

      <!-- 右栏:编辑器 + 结果面板 -->
      <section class="wb-right">
        <div class="code-toolbar">
          <span class="file-tab" :style="{ '--dot': langDot }">{{ fileName }}</span>
          <select v-model="language" aria-label="选择编程语言">
            <option v-for="item in selected?.languages || langs" :key="item" :value="item">{{ langName(item) }}</option>
          </select>
          <span class="mode-tag">ACM 模式 · stdin/stdout</span>
          <span class="spacer"></span>
          <button class="tb-btn" @click="resetCode">重置代码</button>
        </div>

        <div class="cm-wrap">
          <div ref="cmHost" class="cm-host"></div>
        </div>

        <!-- 底部结果面板 -->
        <div class="result-panel" :class="{ collapsed: !panelOpen }">
          <div class="rp-tabs" role="tablist">
            <button v-for="t in panels" :key="t.key" class="rp-tab"
                    :class="{ active: panel === t.key && panelOpen }" @click="openPanel(t.key)">
              {{ t.label }}
              <em v-if="t.key === 'submissions'" class="rp-badge">{{ submissions.length }}</em>
            </button>
            <span class="spacer"></span>
            <button class="mini-btn" @click="panelOpen = !panelOpen">{{ panelOpen ? '▾ 收起' : '▴ 展开' }}</button>
            <button class="run-btn" :disabled="running || !selected || !canSubmitNow" @click="runFromButton">
              {{ running ? '运行中…' : '自测运行' }}
            </button>
            <button class="submit-btn"
                    :disabled="submitting || !selected || !code.trim() || !canSubmitNow" @click="submit">
              {{ submitting ? '提交中…' : canSubmitNow ? '保存并提交' : '窗口未开放' }}
            </button>
          </div>

          <div v-if="panelOpen" class="rp-body">
            <!-- 执行结果 -->
            <template v-if="panel === 'result'">
              <div v-if="resultPhase === 'idle'" class="rp-idle">保存并提交之后,这里将会显示运行结果</div>
              <div v-else-if="resultPhase === 'pending'" class="rp-pending">
                <span class="spin"></span> 代码已送入安全沙盒,正在评测隐藏用例…
              </div>
              <template v-else>
                <div class="result-line">
                  <span class="chip" :class="stateClass(latestResult.status)">{{ stateText(latestResult.status) }}</span>
                  <span v-if="latestResult.score !== null" class="result-score">得分 <strong>{{ latestResult.score }}</strong>/100</span>
                  <span v-if="latestResult.timeMs !== null" class="muted">运行时间:{{ latestResult.timeMs }}ms</span>
                </div>
                <p v-if="latestResult.score !== null && latestResult.score < 100" class="muted result-hint">
                  未全部通过:可通过左侧样例对照输出,或用「自测运行」调试代码
                </p>
              </template>
            </template>

            <!-- 自测运行 -->
            <template v-else-if="panel === 'selftest'">
              <div class="selftest-grid">
                <div class="st-io">
                  <div class="st-label">自测输入 <small>(可粘贴样例输入)</small></div>
                  <textarea v-model="selfTestInput" rows="5" spellcheck="false" class="st-area mono"></textarea>
                </div>
                <div class="st-io">
                  <div class="st-label">
                    运行输出
                    <span v-if="selfTestResult">
                      <span class="chip" :class="selfTestPassed ? 'chip-ok' : 'chip-bad'">{{ selfTestPassed ? '通过' : '与期望输出不一致' }}</span>
                      <span class="muted" v-if="selfTestResult.timeUs != null">运行时间:{{ fmtUs(selfTestResult.timeUs) }}</span>
                      <span class="muted" v-if="selfTestResult.peakMemoryKb != null && selfTestResult.peakMemoryKb >= 0">运行内存:{{ fmtMem(selfTestResult.peakMemoryKb) }}</span>
                    </span>
                  </div>
                  <pre v-if="selfTestResult" class="st-area mono st-out" :class="{ bad: selfTestFailedPhase }">{{ selfTestOutput }}</pre>
                  <pre v-else class="st-area mono st-out dim">运行后显示输出</pre>
                </div>
              </div>
              <p v-if="selfTestResult?.compileError" class="st-err mono">{{ selfTestResult.compileError }}</p>
              <p v-else-if="selfTestResult?.stderr" class="st-err mono">{{ selfTestResult.stderr }}</p>
              <p v-if="selfTestResult?.timedOut" class="st-err">运行超时,请检查是否有死循环或阻塞输入</p>
            </template>

            <!-- 提交记录 -->
            <template v-else>
              <table v-if="submissions.length" class="sub-table">
                <thead><tr><th>#</th><th>状态</th><th>得分</th><th>语言</th><th>耗时</th><th>内存</th><th>提交时间</th></tr></thead>
                <tbody>
                  <tr v-for="s in submissions" :key="s.submissionId">
                    <td>{{ s.attemptNo }}</td>
                    <td><span class="chip" :class="stateClass(s.judgeStatus)">{{ stateText(s.judgeStatus) }}</span></td>
                    <td>{{ s.normalizedScore ?? '—' }}</td>
                    <td>{{ langName(s.language) }}</td>
                    <td>{{ s.totalTimeMs != null ? s.totalTimeMs + 'ms' : '—' }}</td>
                    <td>{{ s.peakMemoryKb != null && s.peakMemoryKb > 0 ? fmtMem(s.peakMemoryKb) : '—' }}</td>
                    <td class="muted">{{ fmtTime(s.submittedAt) }}</td>
                  </tr>
                </tbody>
              </table>
              <div v-else class="rp-idle">本题暂无提交记录</div>
            </template>
          </div>
        </div>
      </section>
    </div>

    <!-- 题目列表抽屉 -->
    <div v-if="drawer" class="drawer-mask" @click.self="drawer = false">
      <div class="drawer">
        <div class="drawer-head">
          <strong>题目列表</strong>
          <span class="spacer"></span>
          <span class="muted">{{ passedCount }}/{{ problems.length }} 已通过</span>
          <button class="mini-btn" @click="drawer = false">✕</button>
        </div>
        <div v-if="!isAssignment" class="level-strip">
          <button v-for="level in levels" :key="level.key" class="level-tab"
                  :class="{ active: difficulty === level.key }" @click="difficulty = level.key">
            {{ level.label }}<small>{{ levelPassed(level.key) }}/{{ levelCount(level.key) }}</small>
          </button>
          <button class="level-tab" :class="{ active: difficulty === '' }" @click="difficulty = ''">
            全部<small>{{ passedCount }}/{{ problems.length }}</small>
          </button>
        </div>
        <div class="list-toolbar">
          <input v-model.trim="keyword" placeholder="搜索题目" />
          <select v-model="statusFilter">
            <option value="ALL">全部状态</option>
            <option value="UNATTEMPTED">未开始</option>
            <option value="ATTEMPTED">已尝试</option>
            <option value="AC">已通过</option>
          </select>
        </div>
        <div class="drawer-list">
          <button v-for="problem in filteredProblems" :key="problem.problemId"
                  class="problem-row" :class="{ selected: selectedId === problem.problemId }"
                  @click="selectProblem(problem.problemId); drawer = false">
            <span class="problem-no">{{ displayCode(problem) }}</span>
            <span class="problem-title">{{ problem.title }}</span>
            <span class="problem-state" :class="stateClass(problem.status)">{{ stateText(problem.status) }}</span>
          </button>
          <div v-if="!filteredProblems.length" class="rp-idle">没有匹配的题目</div>
        </div>
      </div>
    </div>

    <div v-if="tipText" class="copy-tip">{{ tipText }}</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'

import {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection
} from '@codemirror/view'
import { EditorState, Compartment } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete'
import { indentOnInput, bracketMatching, syntaxHighlighting, HighlightStyle } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { cpp } from '@codemirror/lang-cpp'
import { python } from '@codemirror/lang-python'
import { java } from '@codemirror/lang-java'

const props = defineProps({
  mode: { type: String, default: 'practice' },
  targetId: { type: [Number, String], default: null },
  title: { type: String, default: '' },
  meta: { type: Object, default: null }
})
const isAssignment = computed(() => props.mode === 'assignment')
const canSubmitNow = computed(() =>
  !isAssignment.value || (props.meta && props.meta.window === 'OPEN'))

// VS Code Dark+ 风格的主题
const vscodeTheme = [
  EditorView.theme({
    '&': { color: '#d4d4d4', backgroundColor: '#1e1e1e', height: '100%', fontSize: '13.5px' },
    '.cm-content': { caretColor: '#aeafad', fontFamily: "'Cascadia Code', 'JetBrains Mono', Consolas, monospace" },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#aeafad' },
    '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground': {
      backgroundColor: '#264f7890'
    },
    '.cm-activeLine': { backgroundColor: '#2a2d2e' },
    '.cm-activeLineGutter': { backgroundColor: '#2a2d2e', color: '#c6c6c6' },
    '.cm-gutters': { backgroundColor: '#1e1e1e', color: '#858585', border: 'none', borderRight: '1px solid #333333' },
    '.cm-lineNumbers .cm-gutterElement': { padding: '0 9px 0 12px', minWidth: '38px' },
    '.cm-foldGutter': { color: '#858585' },
    '.cm-scroller': { lineHeight: '1.55', fontFamily: "'Cascadia Code', 'JetBrains Mono', Consolas, monospace" },
    '.cm-matchingBracket': { backgroundColor: '#3a3d4180', outline: '1px solid #85858580' },
    '.cm-selectionMatch': { backgroundColor: '#264f7880' },
    '.cm-tooltip': { border: '1px solid #454545', backgroundColor: '#252526', color: '#d4d4d4', fontFamily: "Consolas, monospace" },
    '.cm-tooltip-autocomplete > ul > li': { color: '#d4d4d4', fontFamily: "'Cascadia Code', Consolas, monospace" },
    '.cm-tooltip-autocomplete > ul > li[aria-selected]': { backgroundColor: '#04395e', color: '#fff' },
    '.cm-searchMatch': { backgroundColor: '#61321480', outline: '1px solid #f97918' },
    '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: '#1e6fa280' },
    '.cm-panels': { backgroundColor: '#252526', color: '#cccccc', borderTop: '1px solid #333333' },
    '.cm-panels.cm-panels-top': { borderBottom: '1px solid #333333' }
  }),
  syntaxHighlighting(HighlightStyle.define([
    { tag: tags.comment, color: '#6a9955', fontStyle: 'italic' },
    { tag: [tags.keyword, tags.modifier, tags.self, tags.special(tags.name), tags.definitionKeyword], color: '#569cd6' },
    { tag: [tags.controlKeyword, tags.moduleKeyword, tags.operatorKeyword], color: '#c586c0' },
    { tag: [tags.string, tags.character, tags.special(tags.string)], color: '#ce9178' },
    { tag: tags.regexp, color: '#d16969' },
    { tag: [tags.number, tags.bool, tags.null], color: '#b5cea8' },
    { tag: [tags.tagName, tags.typeName, tags.className, tags.namespace], color: '#4ec9b0' },
    { tag: [tags.definition(tags.variableName), tags.function(tags.variableName), tags.function(tags.propertyName)], color: '#dcdcaa' },
    { tag: [tags.variableName, tags.propertyName, tags.attributeName], color: '#9cdcfe' },
    { tag: [tags.labelName, tags.constant(tags.variableName)], color: '#b5cea8' },
    { tag: tags.macroName, color: '#c586c0' },
    { tag: tags.meta, color: '#ce9178' },
    { tag: [tags.punctuation, tags.operator, tags.derefOperator, tags.separator], color: '#d4d4d4' },
    { tag: tags.processingInstruction, color: '#c586c0' },
    { tag: tags.invalid, color: '#f44747' }
  ]))
]

const FILE_NAMES = { C: 'main.c', CPP: 'main.cpp', PYTHON: 'main.py', JAVA: 'Main.java' }
const LANG_DOTS = { C: '#519aba', CPP: '#519aba', PYTHON: '#3572a5', JAVA: '#b07219' }

const levels = [
  { key: 'EASY', label: '入门' },
  { key: 'BASIC', label: '基础' },
  { key: 'INTERMEDIATE', label: '进阶' },
  { key: 'HARD', label: '困难' }
]
const panels = [
  { key: 'result', label: '执行结果' },
  { key: 'selftest', label: '自测运行' },
  { key: 'submissions', label: '提交记录' }
]
const langs = ['C', 'CPP', 'PYTHON', 'JAVA']
const CODE_TEMPLATES = {
  C: '#include <stdio.h>\n\nint main() {\n    int a, b;\n    scanf("%d %d", &a, &b);\n    printf("%d\\n", a + b);\n    return 0;\n}\n',
  CPP: '#include <iostream>\nusing namespace std;\n\nint main() {\n    int a, b;\n    cin >> a >> b;\n    cout << a + b << endl;\n    return 0;\n}\n',
  PYTHON: 'import sys\n\ndata = sys.stdin.read().split()\na, b = int(data[0]), int(data[1])\nprint(a + b)\n',
  JAVA: 'import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n        int a = in.nextInt();\n        int b = in.nextInt();\n        System.out.println(a + b);\n    }\n}\n'
}

const problems = ref([])
const selected = ref(null)
const selectedId = ref(null)
const difficulty = ref('')
const keyword = ref('')
const statusFilter = ref('ALL')
const loading = ref(true)
const drawer = ref(false)

const language = ref('CPP')
const code = ref('')
const submitting = ref(false)
const running = ref(false)
const panelOpen = ref(true)
const panel = ref('result')
const resultPhase = ref('idle')
const latestResult = ref({ status: null, score: null, timeMs: null })
const selfTestInput = ref('')
const selfTestResult = ref(null)
const submissions = ref([])
const leftWidth = ref(480)

const cmHost = ref(null)
let editorView = null
const langCompartment = new Compartment()
let pollTimer = null

const filteredProblems = computed(() => problems.value.filter(problem => {
  const matchesDifficulty = !difficulty.value || problem.difficulty === difficulty.value
  const matchesKeyword = !keyword.value || `${problem.title} ${problem.code}`.toLowerCase().includes(keyword.value.toLowerCase())
  const matchesStatus = statusFilter.value === 'ALL'
    || (statusFilter.value === 'AC' && problem.status === 'AC')
    || (statusFilter.value === 'ATTEMPTED' && problem.status !== 'UNATTEMPTED')
    || (statusFilter.value === 'UNATTEMPTED' && problem.status === 'UNATTEMPTED')
  return matchesDifficulty && matchesKeyword && matchesStatus
}))
const passedCount = computed(() => problems.value.filter(problem => problem.status === 'AC').length)
const fileName = computed(() => FILE_NAMES[language.value] || 'main.txt')
const langDot = computed(() => LANG_DOTS[language.value] || '#6a737d')
const currentIndex = computed(() =>
  problems.value.findIndex(problem => problem.problemId === selectedId.value))
const hasPrev = computed(() => currentIndex.value > 0)
const hasNext = computed(() => currentIndex.value >= 0 && currentIndex.value < problems.value.length - 1)
const selfTestPassed = computed(() => {
  if (!selfTestResult.value || selfTestResult.value.phase !== 'FINISHED') return false
  const expected = matchedSample.value?.expectedOutput
  if (expected == null) return null
  return normalize(expected) === normalize(selfTestResult.value.output)
})
const selfTestFailedPhase = computed(() =>
  selfTestResult.value && selfTestResult.value.phase !== 'FINISHED')
const selfTestOutput = computed(() => {
  const r = selfTestResult.value
  if (!r) return ''
  return r.output || r.stderr || r.compileError || '(无输出)'
})
const matchedSample = computed(() =>
  selected.value?.samples?.find(sample => normalize(sample.input) === normalize(selfTestInput.value)))

function normalize(text) {
  return String(text ?? '').split('\n').map(line => line.replace(/\s+$/, '')).join('\n').replace(/\n+$/, '')
}

function displayCode(problem) {
  if (!problem) return ''
  return (problem.code || `#${problem.problemId}`).replace('PRACTICE-', '')
}

function difficultyLabel(key) { return levels.find(level => level.key === key)?.label || key }
function winLabel(w) { return ({ NOT_STARTED: '未开始', OPEN: '进行中', CLOSED: '已截止' })[w] || w }
function winClass(w) { return ({ NOT_STARTED: 'chip-warn', OPEN: 'chip-ok', CLOSED: 'chip-muted' })[w] || 'chip-muted' }
function diffChipClass(key) { return ({ EASY: 'chip-ok', BASIC: 'chip-primary', INTERMEDIATE: 'chip-warn', HARD: 'chip-bad' })[key] || 'chip-muted' }
function levelCount(key) { return problems.value.filter(problem => problem.difficulty === key).length }
function levelPassed(key) { return problems.value.filter(problem => problem.difficulty === key && problem.status === 'AC').length }
function stateText(status) { return ({ AC: '已通过', PD: '评测中', UNATTEMPTED: '未开始', WA: '答案错误', CE: '编译错误', TLE: '超时', MLE: '内存超限', OLE: '输出超限', PE: '格式错误', RE: '运行错误', SE: '评测服务异常', BSC: '沙盒拦截' })[status] || status }
function stateClass(status) { return ({ AC: 'chip-ok', PD: 'chip-warn', UNATTEMPTED: 'chip-muted', WA: 'chip-bad', CE: 'chip-bad', TLE: 'chip-bad', MLE: 'chip-bad', OLE: 'chip-bad', PE: 'chip-bad', RE: 'chip-bad', SE: 'chip-bad', BSC: 'chip-bad' })[status] || 'chip-muted' }
function langName(value) { return ({ C: 'C', CPP: 'C++', PYTHON: 'Python', JAVA: 'Java' })[value] || value }
function draftKey(id, lang) { return `oj-practice-draft-${id}-${lang || ''}` }
function loadCodeFor(problemId, lang) {
  const saved = localStorage.getItem(draftKey(problemId, lang))
  if (saved) return saved
  const legacy = localStorage.getItem(`oj-practice-draft-${problemId}`)
  if (legacy) {
    localStorage.setItem(draftKey(problemId, lang), legacy)
    return legacy
  }
  return CODE_TEMPLATES[lang] || ''
}
function fmtTime(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '' }

async function copyText(text, tip) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
  }
  flashTip(tip)
}
const tipText = ref('')
function flashTip(text) {
  tipText.value = text
  setTimeout(() => { if (tipText.value === text) tipText.value = '' }, 1800)
}

function langExtension(value) {
  if (value === 'PYTHON') return python()
  if (value === 'JAVA') return java()
  return cpp()
}

function mountEditor() {
  if (!cmHost.value || editorView) return
  editorView = new EditorView({
    parent: cmHost.value,
    state: EditorState.create({
      doc: code.value,
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        history(),
        indentOnInput(),
        bracketMatching(),
        closeBrackets(),
        keymap.of([...defaultKeymap, ...historyKeymap, ...closeBracketsKeymap, indentWithTab]),
        langCompartment.of(langExtension(language.value)),
        drawSelection(),
        ...vscodeTheme,
        EditorView.updateListener.of(update => {
          if (update.docChanged) {
            code.value = update.state.doc.toString()
            if (selected.value) localStorage.setItem(draftKey(selected.value.problemId, language.value), code.value)
          }
        })
      ]
    })
  })
}

function setEditorDoc(text) {
  if (!editorView) return
  editorView.dispatch({ changes: { from: 0, to: editorView.state.doc.length, insert: text } })
}

watch(language, (value, old) => {
  editorView?.dispatch({ effects: langCompartment.reconfigure(langExtension(value)) })
  if (!old || !editorView || !selected.value) return
  setEditorDoc(loadCodeFor(selected.value.problemId, value))
})

async function loadProblems() {
  loading.value = true
  try {
    if (isAssignment.value) {
      const list = await api(`/student/targets/${props.targetId}/problems`)
      const allSubs = await api(`/student/submissions?assignmentTargetId=${props.targetId}`)
      const latestByProblem = new Map()
      for (const s of allSubs) {
        const prev = latestByProblem.get(s.problemId)
        if (!prev || String(s.submittedAt) > String(prev.submittedAt)) latestByProblem.set(s.problemId, s)
      }
      problems.value = list.map((p, i) => {
        const last = latestByProblem.get(p.problemId)
        let status = 'UNATTEMPTED'
        let best = 0
        if (last) {
          status = last.judgeStatus
          best = last.normalizedScore ?? 0
          if (best >= 100) status = 'AC'
        }
        return {
          ...p,
          code: p.code || `P${String(i + 1).padStart(2, '0')}`,
          difficulty: null,
          status,
          bestScore: best,
          assignmentTargetId: Number(props.targetId),
          timeLimitMs: p.timeLimitMs ?? 10000,
          memoryLimitMb: p.memoryLimitMb ?? 256
        }
      })
      if (problems.value.length) await selectProblem(problems.value[0].problemId)
    } else {
      problems.value = await api('/student/practice/problems')
      if (!selectedId.value && problems.value.length) await selectProblem(problems.value[0].problemId)
    }
  } finally {
    loading.value = false
  }
}

async function selectProblem(problemId) {
  selectedId.value = problemId
  resultPhase.value = 'idle'
  latestResult.value = { status: null, score: null, timeMs: null }
  selfTestResult.value = null
  clearPoll()
  if (isAssignment.value) {
    selected.value = problems.value.find(problem => problem.problemId === problemId) || null
  } else {
    selected.value = await api(`/student/practice/problems/${problemId}`)
  }
  if (!selected.value) return
  language.value = selected.value.languages?.[0] || 'CPP'
  const firstSample = selected.value.samples?.[0]
  selfTestInput.value = firstSample?.input || ''
  code.value = loadCodeFor(problemId, language.value)
  if (editorView) {
    setEditorDoc(code.value)
  } else {
    await nextTick()
    mountEditor()
    setEditorDoc(code.value)
  }
  await refreshSubmissionStatus()
  loadSubmissions()
}

async function loadSubmissions() {
  if (!selected.value) return
  const problemId = selected.value.problemId
  const list = await api(`/student/submissions?assignmentTargetId=${selected.value.assignmentTargetId}&problemId=${problemId}`)
  if (selected.value?.problemId !== problemId) return
  submissions.value = list.slice().reverse()
}

async function refreshSubmissionStatus() {
  clearPoll()
  if (!selected.value) return
  const problemId = selected.value.problemId
  const list = await api(`/student/submissions?assignmentTargetId=${selected.value.assignmentTargetId}&problemId=${problemId}`)
  if (!selected.value || selected.value.problemId !== problemId) return
  const latest = list.at(-1)
  if (!latest) return
  if (latest.judgeStatus === 'PD') {
    if (resultPhase.value !== 'idle') resultPhase.value = 'pending'
    pollTimer = setTimeout(() => { pollTimer = null; refreshSubmissionStatus() }, 2000)
    return
  }
  latestResult.value = {
    status: latest.judgeStatus,
    score: latest.normalizedScore ?? null,
    timeMs: latest.totalTimeMs ?? null
  }
  if (resultPhase.value === 'pending' || latest.judgeStatus === 'AC') {
    resultPhase.value = 'done'
    panelOpen.value = true
  }
  selected.value.status = latest.judgeStatus
  if (latest.judgeStatus === 'AC') {
    selected.value.bestScore = 100
    const item = problems.value.find(problem => problem.problemId === problemId)
    if (item) { item.status = 'AC'; item.bestScore = 100 }
  }
  loadSubmissions()
}

function clearPoll() {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
}

async function submit() {
  if (!selected.value || !code.value.trim() || !canSubmitNow.value) return
  localStorage.setItem(draftKey(selected.value.problemId, language.value), code.value)
  submitting.value = true
  try {
    await api('/student/submissions', {
      method: 'POST',
      body: {
        assignmentTargetId: selected.value.assignmentTargetId,
        problemId: selected.value.problemId,
        language: language.value,
        code: code.value,
        idempotencyKey: crypto.randomUUID()
      }
    })
    selected.value.status = 'PD'
    const item = problems.value.find(problem => problem.problemId === selected.value.problemId)
    if (item) item.status = 'PD'
    panel.value = 'result'
    panelOpen.value = true
    resultPhase.value = 'pending'
    await refreshSubmissionStatus()
  } finally {
    submitting.value = false
  }
}

async function runSelfTest() {
  if (!selected.value || running.value) return
  running.value = true
  selfTestResult.value = null
  try {
    const path = isAssignment.value
      ? `/student/targets/${props.targetId}/run`
      : '/student/practice/run'
    selfTestResult.value = await api(path, {
      method: 'POST',
      body: {
        problemId: selected.value.problemId,
        language: language.value,
        code: code.value,
        input: selfTestInput.value
      }
    })
  } catch (e) {
    selfTestResult.value = { phase: 'FINISHED', output: '', stderr: e.message, exitCode: -1, timeUs: 0, peakMemoryKb: -1, timedOut: false }
  } finally {
    running.value = false
  }
}

function fmtUs(us) {
  if (us == null) return ''
  if (us < 1000) return (us / 1000).toFixed(2) + 'ms'
  if (us < 1_000_000) return (us / 1000).toFixed(1) + 'ms'
  return (us / 1_000_000).toFixed(2) + 's'
}

function fmtMem(kb) {
  if (kb == null || kb < 0) return ''
  if (kb < 1024) return kb + 'KB'
  if (kb < 1024 * 1024) return (kb / 1024).toFixed(1) + 'MB'
  return (kb / 1024 / 1024).toFixed(2) + 'GB'
}

function resetCode() {
  const template = CODE_TEMPLATES[language.value] || ''
  code.value = template
  setEditorDoc(template)
  if (selected.value) localStorage.setItem(draftKey(selected.value.problemId, language.value), template)
}

function openPanel(key) {
  panelOpen.value = true
  if (panel.value === key && key !== 'selftest') return
  panel.value = key
  if (key === 'submissions') loadSubmissions()
}

function runFromButton() {
  openPanel('selftest')
  runSelfTest()
}

function step(delta) {
  const next = problems.value[currentIndex.value + delta]
  if (next) selectProblem(next.problemId)
}

watch(code, value => {
  if (selected.value && value && editorView && editorView.state.doc.toString() !== value) {
    setEditorDoc(value)
  }
})

function startDrag(event) {
  event.preventDefault()
  const startX = event.clientX
  const startWidth = leftWidth.value
  const onMove = e => {
    leftWidth.value = Math.min(Math.max(320, startWidth + e.clientX - startX), window.innerWidth - 420)
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

function onKeydown(event) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    submit()
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    if (selected.value) localStorage.setItem(draftKey(selected.value.problemId, language.value), code.value)
    flashTip('草稿已保存')
  }
}

onMounted(() => {
  loadProblems()
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  clearPoll()
  window.removeEventListener('keydown', onKeydown)
  editorView?.destroy()
  editorView = null
})
</script>

<style scoped>
.oj-workbench { flex: 1; display: flex; flex-direction: column; min-height: 0; height: 100%; }

.assign-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
}
.assign-title { font-size: 14.5px; }

.wb-topbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 9px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}
.tb-btn {
  background: #fff;
  color: var(--text);
  border: 1px solid var(--border-strong);
  box-shadow: none;
  padding: 6px 12px;
  font-size: 13px;
}
.tb-btn:hover:not(:disabled) { background: var(--panel-2); box-shadow: none; }
.tb-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.tb-title strong { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tb-nav { display: flex; gap: 6px; margin-left: auto; }
.tb-progress { display: flex; align-items: baseline; gap: 2px; color: var(--muted); font-size: 13px; }
.tb-progress strong { color: var(--accent); font-size: 19px; }
.tb-progress small { margin-left: 4px; font-size: 12px; }

.wb-body { flex: 1; display: flex; min-height: 0; }

.wb-left {
  flex: none;
  min-width: 320px;
  max-width: 70vw;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  min-height: 0;
}
.pr-head { padding: 16px 20px 12px; border-bottom: 1px solid var(--border); }
.pr-head h3 { margin: 0 0 8px; font-size: 19px; }
.pr-meta { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.meta-item { color: var(--muted); font-size: 12.5px; }
.pr-scroll { flex: 1; overflow-y: auto; padding: 16px 20px 24px; min-height: 0; }
.pr-desc { white-space: pre-wrap; line-height: 1.85; font-size: 14px; }
.pr-samples { margin-top: 18px; display: flex; flex-direction: column; gap: 12px; }
.sample-box { border: 1px solid var(--border); border-radius: 10px; background: var(--panel-2); padding: 11px 13px; }
.sample-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; }
.sample-head .spacer { flex: 1; }
.mini-btn {
  background: #fff;
  color: var(--muted);
  border: 1px solid var(--border);
  box-shadow: none;
  padding: 3px 9px;
  font-size: 12px;
}
.mini-btn:hover:not(:disabled) { color: var(--accent); border-color: #c7d9ff; box-shadow: none; }
.sample-io { display: grid; grid-template-columns: 34px 1fr; gap: 8px; margin-top: 6px; }
.sample-io span { font-size: 11.5px; color: var(--muted); padding-top: 2px; }
.sample-io pre { margin: 0; font: 12.5px/1.55 Consolas, monospace; white-space: pre-wrap; word-break: break-all; }
.pr-tip { margin-top: 20px; font-size: 12.5px; color: var(--muted); }
.closed-tip { color: var(--danger); font-weight: 600; }
.pr-empty { flex: 1; display: grid; place-items: center; color: var(--muted); padding: 30px; }

.wb-splitter {
  flex: none;
  width: 5px;
  cursor: col-resize;
  background: var(--border);
  transition: background 0.15s ease;
}
.wb-splitter:hover { background: var(--accent); }

.wb-right { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #1e1e1e; }
.code-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: #1f1f1f;
  border-bottom: 1px solid #333333;
}
.code-toolbar select { background: #3c3c3c; color: #cccccc; border: 1px solid #454545; padding: 5px 10px; font-size: 13px; }
.code-toolbar select:hover { border-color: #5a5a5a; }
.mode-tag { color: #8a8a8a; font-size: 12px; }
.code-toolbar .tb-btn { background: #3c3c3c; color: #cccccc; border: 1px solid transparent; }
.code-toolbar .tb-btn:hover:not(:disabled) { background: #4a4a4a; color: #fff; border-color: transparent; }
.code-toolbar .spacer { flex: 1; }
.file-tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: #2d2d2d;
  color: #e8e8e8;
  border: 1px solid #3f3f3f;
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 13px;
  font-family: 'Segoe UI', system-ui, sans-serif;
}
.file-tab::before {
  content: '';
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--dot, #519aba);
  flex: none;
}

.cm-wrap { flex: 1; min-height: 0; }
.cm-host { height: 100%; }
.cm-host :deep(.cm-editor) { height: 100%; }
.cm-host :deep(.cm-gutters) { border-right: 1px solid #333333; }

.result-panel { flex: none; border-top: 1px solid #334155; background: #fff; display: flex; flex-direction: column; height: 250px; }
.result-panel.collapsed { height: auto; }
.rp-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--border);
  flex: none;
}
.rp-tab {
  background: none;
  color: var(--muted);
  border: none;
  box-shadow: none;
  padding: 6px 12px;
  font-size: 13.5px;
  border-radius: 8px;
}
.rp-tab:hover:not(:disabled) { background: var(--panel-2); box-shadow: none; }
.rp-tab.active { color: var(--accent); background: var(--accent-soft); font-weight: 700; }
.rp-badge { font-style: normal; font-size: 11px; background: #e2e8f0; border-radius: 999px; padding: 0 6px; margin-left: 4px; }
.run-btn { background: #16a34a; }
.run-btn:hover:not(:disabled) { background: #15803d; }
.submit-btn { background: var(--accent); }
.submit-btn:hover:not(:disabled) { background: var(--accent-strong); }
.result-panel .rp-tabs .mini-btn { border-color: var(--border-strong); }

.rp-body { flex: 1; overflow-y: auto; padding: 12px 16px; min-height: 0; }
.rp-idle { display: grid; place-items: center; height: 100%; color: var(--muted); font-size: 13.5px; }
.rp-pending { display: flex; align-items: center; gap: 10px; height: 100%; justify-content: center; color: var(--accent); font-size: 14px; }
.spin {
  width: 15px;
  height: 15px;
  border: 2px solid #c7d9ff;
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.result-line { display: flex; align-items: center; gap: 16px; font-size: 14px; }
.result-score strong { color: var(--ok); font-size: 17px; margin: 0 2px; }
.result-hint { margin-top: 8px; font-size: 12.5px; }

.selftest-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.st-label { font-size: 12.5px; font-weight: 600; color: var(--muted); margin-bottom: 6px; display: flex; align-items: center; gap: 8px; }
.st-area { width: 100%; resize: vertical; font-size: 12.5px; }
.st-out { margin: 0; background: var(--panel-2); border: 1px solid var(--border); border-radius: 9px; padding: 9px 11px; min-height: 92px; max-height: 180px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.st-out.bad { border-color: #fecaca; background: var(--danger-soft); }
.st-out.dim { color: var(--muted); }
.st-err { margin: 10px 0 0; color: var(--danger); font-size: 12.5px; white-space: pre-wrap; }

.sub-table { width: 100%; border-collapse: collapse; font-size: 13px; margin: 0; box-shadow: none; border: none; }
.sub-table th, .sub-table td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; }
.sub-table th { background: var(--panel-2); font-size: 12px; }

.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  z-index: 90;
  display: flex;
}
.drawer {
  width: min(430px, 92vw);
  background: var(--panel);
  height: 100%;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-lg);
  animation: slide-in 0.18s ease;
}
@keyframes slide-in { from { transform: translateX(-30px); opacity: 0; } }
.drawer-head { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border-bottom: 1px solid var(--border); }
.level-strip { display: flex; gap: 6px; padding: 12px 16px 0; flex-wrap: wrap; }
.level-tab { border: 1px solid var(--border); background: #fff; color: var(--muted); box-shadow: none; padding: 7px 11px; font-size: 12.5px; border-radius: 9px; }
.level-tab:hover { box-shadow: none; background: var(--panel-2); }
.level-tab.active { background: var(--accent-soft); border-color: #bfd4ff; color: var(--accent-strong); }
.level-tab small { margin-left: 5px; opacity: 0.75; }
.list-toolbar { display: flex; gap: 8px; padding: 12px 16px; }
.list-toolbar input { flex: 1; min-width: 0; padding: 8px 10px; }
.list-toolbar select { width: 104px; padding: 8px 7px; font-size: 12px; }
.drawer-list { flex: 1; overflow-y: auto; border-top: 1px solid var(--border); }
.problem-row {
  width: 100%;
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) auto;
  gap: 8px;
  text-align: left;
  justify-content: initial;
  background: transparent;
  color: var(--text);
  border: 0;
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  box-shadow: none;
  padding: 12px 16px;
  font-weight: 500;
  font-size: 13.5px;
}
.problem-row:hover { background: var(--panel-2); box-shadow: none; }
.problem-row.selected { background: var(--accent-soft); box-shadow: inset 3px 0 var(--accent); }
.problem-no { color: var(--muted); font-size: 11px; }
.problem-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.problem-state { font-size: 11px; }

.copy-tip {
  position: fixed;
  bottom: 40px;
  left: 50%;
  transform: translateX(-50%);
  background: #0f172a;
  color: #fff;
  padding: 8px 16px;
  border-radius: 9px;
  font-size: 13px;
  z-index: 120;
}

@media (max-width: 900px) {
  .wb-body { flex-direction: column; overflow-y: auto; }
  .wb-left { width: 100% !important; max-width: none; }
  .wb-splitter { display: none; }
  .wb-right { min-height: 520px; }
  .selftest-grid { grid-template-columns: 1fr; }
  .tb-title strong { max-width: 32vw; }
}
</style>
