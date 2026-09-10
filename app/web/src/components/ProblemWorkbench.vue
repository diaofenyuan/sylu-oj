<template>
  <div class="oj-workbench">
    <!-- 顶部工具条 -->
    <div class="wb-topbar">
      <button class="tb-btn" @click="drawer = true">
        <Icon icon="mdi:format-list-bulleted" />
        题目列表
      </button>
      <div class="tb-title">
        <strong>{{ selected ? displayCode(selected) + ' ' + selected.title : title || (isAssignment ? '作业题目' : '刷题中心') }}</strong>
        <span v-if="selected" class="chip" :class="stateClass(selected.status)">
          <Icon :icon="getStatusIcon(selected.status)" />
          {{ stateText(selected.status) }}
        </span>
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
              <DifficultyBadge v-if="selected.difficulty" :difficulty="selected.difficulty" />
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
        <div v-else-if="loading" class="pr-empty pr-loading" role="status" aria-busy="true">
          <span class="spinner spinner-lg" aria-hidden="true"></span>
          <p>题目加载中…</p>
          <div class="pr-skeleton">
            <div class="skeleton skeleton-line" style="--skeleton-w: 55%"></div>
            <div class="skeleton skeleton-line" style="--skeleton-w: 92%"></div>
            <div class="skeleton skeleton-line" style="--skeleton-w: 78%"></div>
            <div class="skeleton skeleton-block"></div>
          </div>
        </div>
        <div v-else class="pr-empty">从右上角「题目列表」选择一道题</div>
      </section>

      <!-- 可拖拽分隔条：同时支持键盘调整，避免仅鼠标可操作 -->
      <div
        class="wb-splitter"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整题面宽度"
        :aria-valuemin="LEFT_WIDTH_MIN"
        :aria-valuemax="leftWidthMax"
        :aria-valuenow="Math.round(leftWidth)"
        tabindex="0"
        @mousedown="startDrag"
        @keydown="onSplitterKeydown"
      ></div>

      <!-- 右栏:编辑器 + 结果面板 -->
      <section class="wb-right">
        <div class="code-toolbar">
          <span class="file-tab" :style="{ '--dot': langDot }">{{ fileName }}</span>
          <select v-model="language" aria-label="选择编程语言">
            <option v-for="item in selected?.languages || langs" :key="item" :value="item">{{ langName(item) }}</option>
          </select>
          <span v-if="langLoading" class="lang-loading" role="status">
            <span class="spinner" aria-hidden="true"></span>
            加载高亮…
          </span>
          <span class="mode-tag">ACM 模式 · stdin/stdout</span>
          <span class="spacer"></span>
          <button class="tb-btn" @click="showTemplates = true">
            <Icon icon="mdi:code-braces" />
            模板
          </button>
          <button class="tb-btn" @click="showLeaderboard = true">
            <Icon icon="mdi:trophy" />
            排行榜
          </button>
          <button class="tb-btn" @click="showShortcuts = true">
            <Icon icon="mdi:keyboard" />
            快捷键
          </button>
          <button class="tb-btn" @click="resetCode">
            <Icon icon="mdi:refresh" />
            重置代码
          </button>
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
              <Icon :icon="running ? 'mdi:loading' : 'mdi:play'" :class="{ 'spin-icon': running }" />
              {{ running ? '运行中…' : '自测运行' }}
            </button>
            <button class="submit-btn"
                    :disabled="submitting || !selected || !code.trim() || !canSubmitNow" @click="submit">
              <Icon :icon="submitting ? 'mdi:loading' : 'mdi:send'" :class="{ 'spin-icon': submitting }" />
              {{ submitting ? '提交中…' : canSubmitNow ? '保存并提交' : '窗口未开放' }}
            </button>
          </div>

          <div v-if="panelOpen" class="rp-body">
            <!-- 执行结果 -->
            <template v-if="panel === 'result'">
              <div v-if="resultPhase === 'idle'" class="rp-idle">保存并提交之后,这里将会显示运行结果</div>
              <div v-else-if="resultPhase === 'pending'" class="rp-pending">
                <Icon icon="mdi:loading" class="spin-icon" /> 代码已送入安全沙盒,正在评测隐藏用例…
              </div>
              <template v-else>
                <div class="result-line">
                  <span class="chip" :class="stateClass(latestResult.status)">
                    <Icon :icon="getStatusIcon(latestResult.status)" />
                    {{ stateText(latestResult.status) }}
                  </span>
                  <span v-if="latestResult.score !== null" class="result-score">得分 <strong>{{ latestResult.score }}</strong>/100</span>
                  <span v-if="latestResult.timeMs !== null" class="muted">运行时间:{{ latestResult.timeMs }}ms</span>
                </div>
                <p v-if="latestResult.score !== null && latestResult.score < 100" class="muted result-hint">
                  未全部通过:可通过左侧样例对照输出,或用「自测运行」调试代码
                </p>
                <CaseDetails v-if="caseDetails.length" :case-details="caseDetails" />
                <ErrorDiagnostics
                  :status="latestResult.status"
                  :time-limit="selected?.timeLimitMs"
                  :memory-limit="selected?.memoryLimitMb"
                  :actual-time="latestResult.timeMs"
                />
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
              <SampleCompare
                v-if="showSampleCompare"
                visible
                :input="selfTestInput"
                :expected="matchedSample.expectedOutput"
                :actual="selfTestResult.output || ''"
                :time-ms="selfTestTimeMs"
                :memory-kb="selfTestResult.peakMemoryKb"
              />
            </template>

            <!-- 题目讨论区 -->
            <template v-else-if="panel === 'discussion'">
              <DiscussionZone
                v-if="selected"
                :problem-id="Number(selected.problemId)"
                user-role="student"
                :user-id="myUserId"
              />
              <div v-else class="rp-idle">先从题目列表选择一道题目</div>
            </template>

            <!-- 提交记录 -->
            <template v-else>
              <table v-if="submissions.length" class="sub-table">
                <thead><tr><th>#</th><th>状态</th><th>得分</th><th>语言</th><th>耗时</th><th>内存</th><th>提交时间</th></tr></thead>
                <tbody>
                  <tr v-for="s in submissions" :key="s.submissionId">
                    <td>{{ s.attemptNo }}</td>
                    <td>
                      <span class="chip" :class="stateClass(s.judgeStatus)">
                        <Icon :icon="getStatusIcon(s.judgeStatus)" />
                        {{ stateText(s.judgeStatus) }}
                      </span>
                    </td>
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
    <AppOverlay :open="drawer" placement="drawer-left" aria-label="题目列表"
                @close="drawer = false">
      <div class="drawer">
        <div class="drawer-head">
          <strong>题目列表</strong>
          <span class="spacer"></span>
          <span class="muted">{{ passedCount }}/{{ problems.length }} 已通过</span>
          <button type="button" class="mini-btn" aria-label="关闭题目列表" @click="drawer = false">✕</button>
        </div>
        <div v-if="isAssignment && meta" class="drawer-progress">
          <ProgressCard
            title="作业进度"
            :subtitle="title || ''"
            :completed="passedCount"
            :in-progress="attemptedCount"
            :todo="todoCount"
            :deadline="meta.deadline"
            :size="72"
            :stroke-width="8"
          />
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
            <span class="problem-state chip" :class="stateClass(problem.status)">
              <Icon :icon="getStatusIcon(problem.status)" />
              {{ stateText(problem.status) }}
            </span>
          </button>
          <div v-if="!filteredProblems.length" class="rp-idle">没有匹配的题目</div>
        </div>
      </div>
    </AppOverlay>

    <TemplatePicker :visible="showTemplates" :language="language" @close="showTemplates = false" @insert="insertTemplate" />
    <ShortcutHelp :visible="showShortcuts" @close="showShortcuts = false" />
    <Leaderboard :visible="showLeaderboard" :problem-id="selected?.problemId" @close="showLeaderboard = false" />

    <div v-if="tipText" class="copy-tip">{{ tipText }}</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { useJudgeStatus } from '../composables/useJudgeStatus'
import { useToast } from '../composables/useToast'
import CaseDetails from './CaseDetails.vue'
import ErrorDiagnostics from './ErrorDiagnostics.vue'
import SampleCompare from './SampleCompare.vue'
import TemplatePicker from './TemplatePicker.vue'
import ShortcutHelp from './ShortcutHelp.vue'
import Leaderboard from './Leaderboard.vue'
import DiscussionZone from './DiscussionZone.vue'
import ProgressCard from './ProgressCard.vue'
import DifficultyBadge from './DifficultyBadge.vue'

import {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection
} from '@codemirror/view'
import { EditorState, Compartment } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete'
import { indentOnInput, bracketMatching, syntaxHighlighting, HighlightStyle } from '@codemirror/language'
import { tags } from '@lezer/highlight'
// 三套语言语法包（@lezer/cpp 等）体积可观，改为按需加载，见 resolveLangExtension

const { getStatusIcon, getStatusText, getStatusClass } = useJudgeStatus()
const toast = useToast()

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
  { key: 'submissions', label: '提交记录' },
  { key: 'discussion', label: '讨论' }
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

/**
 * 题面宽度
 * -----------------------------------------------------------------------------
 * 学生通常会按自己的读题习惯调整左右分栏，此前每次进入工作台都会重置回默认值。
 * 这里把宽度持久化到 localStorage，并在读取时按当前视口重新约束，避免旧值在小屏上溢出。
 */
const LEFT_WIDTH_KEY = 'oj-wb-left-width'
const LEFT_WIDTH_MIN = 320
// 右侧编辑器区至少保留的宽度
const LEFT_WIDTH_RESERVED = 420
const LEFT_WIDTH_DEFAULT = 480
// 仅用于计算 aria-valuemax，随窗口尺寸变化
const viewportWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1440)

const leftWidthMax = computed(() => Math.max(LEFT_WIDTH_MIN, viewportWidth.value - LEFT_WIDTH_RESERVED))

function clampLeftWidth(value) {
  return Math.min(Math.max(LEFT_WIDTH_MIN, value), leftWidthMax.value)
}

function loadLeftWidth() {
  try {
    const saved = Number(localStorage.getItem(LEFT_WIDTH_KEY))
    if (Number.isFinite(saved) && saved > 0) return clampLeftWidth(saved)
  } catch {
    // 隐私模式下 localStorage 不可用，回退默认值
  }
  return LEFT_WIDTH_DEFAULT
}

const leftWidth = ref(loadLeftWidth())

function persistLeftWidth() {
  try {
    localStorage.setItem(LEFT_WIDTH_KEY, String(Math.round(leftWidth.value)))
  } catch {
    // 写入失败不影响使用
  }
}

/** 键盘调整：左右方向键移动 16px，Home/End 跳到两端 */
function onSplitterKeydown(event) {
  const step = 16
  const actions = {
    ArrowLeft: () => { leftWidth.value = clampLeftWidth(leftWidth.value - step) },
    ArrowRight: () => { leftWidth.value = clampLeftWidth(leftWidth.value + step) },
    Home: () => { leftWidth.value = LEFT_WIDTH_MIN },
    End: () => { leftWidth.value = leftWidthMax.value }
  }
  const action = actions[event.key]
  if (!action) return
  event.preventDefault()
  action()
  persistLeftWidth()
}

function syncViewportWidth() {
  viewportWidth.value = window.innerWidth
}
const showTemplates = ref(false)
const showShortcuts = ref(false)
const showLeaderboard = ref(false)
const caseDetails = ref([])
const myUserId = ref(0)

const cmHost = ref(null)
let editorView = null
const langCompartment = new Compartment()
let pollTimer = null

/**
 * 语言语法包按需加载
 * -----------------------------------------------------------------------------
 * 三套 lezer 语法包（cpp / python / java）合计占工作台分块的绝大部分体积。
 * 静态 import 会让只用一种语言的学生也下载其余两套，故改为按需动态加载：
 *   - 编辑器先以纯文本挂载（保证工具栏与输入立刻可用），语言包就绪后再注入高亮；
 *   - 加载结果按语言缓存，切换回来时不再重新下载；
 *   - 加载失败降级为纯文本编辑，不阻断答题。
 */
const LANG_LOADERS = {
  CPP: () => import('@codemirror/lang-cpp').then((m) => m.cpp()),
  PYTHON: () => import('@codemirror/lang-python').then((m) => m.python()),
  JAVA: () => import('@codemirror/lang-java').then((m) => m.java())
}

const langCache = new Map()
const langLoading = ref(false)
// 竞态令牌：快速连续切换语言时，只允许最后一次请求的结果落地
let langRequestSeq = 0
let langFallbackNotified = false

async function resolveLangExtension(value) {
  const key = LANG_LOADERS[value] ? value : 'CPP'
  if (langCache.has(key)) return langCache.get(key)

  langLoading.value = true
  try {
    const extension = await LANG_LOADERS[key]()
    langCache.set(key, extension)
    return extension
  } catch (e) {
    // 语言包加载失败不应让编辑器不可用：退回无高亮的纯文本编辑，答题与提交不受影响
    console.error('[workbench] 语言语法包加载失败，已回退为纯文本编辑:', e)
    if (!langFallbackNotified) {
      langFallbackNotified = true
      toast.error('代码高亮加载失败，已切换为纯文本编辑，不影响提交')
    }
    return null
  } finally {
    langLoading.value = false
  }
}

/** 切换语言时异步应用高亮；首次挂载不走此路径（见 mountEditor） */
async function applyLanguage(value) {
  const seq = ++langRequestSeq
  const extension = await resolveLangExtension(value)
  // 已有更新的切换请求，丢弃本次结果，避免旧语言覆盖新语言
  if (seq !== langRequestSeq || !editorView) return
  editorView.dispatch({ effects: langCompartment.reconfigure(extension ?? []) })
}

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
const attemptedCount = computed(() =>
  problems.value.filter(problem => problem.status !== 'UNATTEMPTED' && problem.status !== 'AC').length)
const todoCount = computed(() => problems.value.filter(problem => problem.status === 'UNATTEMPTED').length)
const selfTestTimeMs = computed(() =>
  selfTestResult.value?.timeUs != null ? Math.max(0, Math.round(selfTestResult.value.timeUs / 1000)) : null)
const showSampleCompare = computed(() =>
  Boolean(selfTestResult.value && matchedSample.value && selfTestResult.value.phase === 'FINISHED'))

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
// 使用新的状态工具函数
function stateText(status) { return getStatusText(status) }
function stateClass(status) { return getStatusClass(status) }
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

let editorMounting = false

async function mountEditor() {
  // editorMounting 防止并发调用导致创建两个编辑器实例
  if (!cmHost.value || editorView || editorMounting) return
  editorMounting = true
  try {
    // 先取到语法包再挂载：避免"先无高亮、随后突然变色"的闪烁。
    // 取包失败时 extension 为 null，退化为纯文本编辑而非报错。
    const extension = await resolveLangExtension(language.value)
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
          langCompartment.of(extension ?? []),
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
  } finally {
    editorMounting = false
  }
}

function setEditorDoc(text) {
  if (!editorView) return
  editorView.dispatch({ changes: { from: 0, to: editorView.state.doc.length, insert: text } })
}

watch(language, (value, old) => {
  applyLanguage(value)
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
  caseDetails.value = []
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
    await mountEditor()
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
  caseDetails.value = []
  if (latest.judgeStatus !== 'PD') loadCaseDetails(latest.submissionId)
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

async function loadCaseDetails(submissionId) {
  if (!submissionId) return
  try {
    caseDetails.value = await api(`/student/submissions/${submissionId}/testcases`)
  } catch {
    caseDetails.value = []
  }
}

function insertTemplate(templateCode) {
  code.value = templateCode
  setEditorDoc(templateCode)
  if (selected.value) localStorage.setItem(draftKey(selected.value.problemId, language.value), templateCode)
  flashTip('模板已插入编辑器')
}

async function ensureUserId() {
  if (myUserId.value) return
  try {
    const profile = await api('/identity/me')
    myUserId.value = profile.appUserId ?? 0
  } catch {
    myUserId.value = 0
  }
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
  if (key === 'discussion') ensureUserId()
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
    leftWidth.value = clampLeftWidth(startWidth + e.clientX - startX)
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    // 拖拽结束后再落盘，避免每帧写入 localStorage
    persistLeftWidth()
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

function onKeydown(event) {
  const mod = event.ctrlKey || event.metaKey
  // 快捷键帮助自身需可由同一组合键关闭，故先单独处理；
  // 注意不能把它放在下面的"让位"判断之后，否则面板打开后就再也关不掉。
  if (mod && event.key === '/') {
    if (drawer.value || showTemplates.value || showLeaderboard.value) return
    event.preventDefault()
    showShortcuts.value = !showShortcuts.value
    return
  }
  // 其余弹层打开时全局快捷键全部让位，避免在弹层内误触发提交/跳题等动作
  if (drawer.value || showTemplates.value || showShortcuts.value || showLeaderboard.value) return

  if (mod && event.key === 'Enter') {
    event.preventDefault()
    submit()
  }
  if (mod && event.key.toLowerCase() === 's') {
    event.preventDefault()
    if (selected.value) localStorage.setItem(draftKey(selected.value.problemId, language.value), code.value)
    flashTip('草稿已保存')
  }
  if (mod && event.altKey && event.key.toLowerCase() === 'r') {
    event.preventDefault()
    if (canSubmitNow.value || !isAssignment.value) runFromButton()
  }
  if (mod && event.altKey && event.key.toLowerCase() === 't') {
    event.preventDefault()
    showTemplates.value = true
  }
  if (mod && !event.altKey && event.key === '[') {
    event.preventDefault()
    step(-1)
  }
  if (mod && !event.altKey && event.key === ']') {
    event.preventDefault()
    step(1)
  }
}

onMounted(() => {
  loadProblems()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', syncViewportWidth)
})
onBeforeUnmount(() => {
  clearPoll()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', syncViewportWidth)
  editorView?.destroy()
  editorView = null
})
</script>

<style scoped>
.oj-workbench { flex: 1; display: flex; flex-direction: column; min-height: 0; height: 100%; }

.assign-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
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
  background: var(--panel);
  color: var(--text);
  border: 1px solid var(--border-strong);
  box-shadow: none;
  padding: 6px 12px;
  font-size: var(--fs-sm);
}
.tb-btn:hover:not(:disabled) { background: var(--panel-2); box-shadow: none; }
.tb-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.tb-title strong { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tb-nav { display: flex; gap: 6px; margin-left: auto; }
.tb-progress { display: flex; align-items: baseline; gap: 2px; color: var(--muted); font-size: var(--fs-sm); }
.tb-progress strong { color: var(--accent); font-size: 19px; }
.tb-progress small { margin-left: var(--space-1); font-size: var(--fs-xs); }

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
.pr-head { padding: var(--space-4) 20px 12px; border-bottom: 1px solid var(--border); }
.pr-head h3 { margin: 0 0 8px; font-size: 19px; }
.pr-meta { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.meta-item { color: var(--muted); font-size: 12.5px; }
.pr-scroll { flex: 1; overflow-y: auto; padding: var(--space-4) 20px 24px; min-height: 0; }
.pr-desc { white-space: pre-wrap; line-height: 1.85; font-size: var(--fs-base); }
.pr-samples { margin-top: 18px; display: flex; flex-direction: column; gap: var(--space-3); }
.sample-box { border: 1px solid var(--border); border-radius: 10px; background: var(--panel-2); padding: 11px 13px; }
.sample-head { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-2); font-size: var(--fs-sm); }
.sample-head .spacer { flex: 1; }
.mini-btn {
  background: var(--panel);
  color: var(--muted);
  border: 1px solid var(--border);
  box-shadow: none;
  padding: 3px 9px;
  font-size: var(--fs-xs);
}
.mini-btn:hover:not(:disabled) { color: var(--accent); border-color: color-mix(in srgb, var(--accent) 34%, transparent); box-shadow: none; }
.sample-io { display: grid; grid-template-columns: 34px 1fr; gap: var(--space-2); margin-top: 6px; }
.sample-io span { font-size: 11.5px; color: var(--muted); padding-top: 2px; }
.sample-io pre { margin: 0; font: 12.5px/1.55 Consolas, monospace; white-space: pre-wrap; word-break: break-all; }
.pr-tip { margin-top: var(--space-5); font-size: 12.5px; color: var(--muted); }
.closed-tip { color: var(--danger); font-weight: 600; }
.pr-empty { flex: 1; display: grid; place-items: center; color: var(--muted); padding: 30px; }

/* 题面加载骨架：与最终排版保持相近的视觉密度，减少加载完成后的跳动 */
.pr-loading {
  align-content: center;
  justify-items: center;
  gap: 14px;
  color: var(--accent);
  animation: m-fade-in var(--dur-base) var(--ease-out) both;
}
.pr-loading p { margin: 0; color: var(--muted); font-size: var(--fs-sm); }
.pr-skeleton {
  width: min(460px, 88%);
  margin-top: var(--space-2);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.pr-skeleton .skeleton-line { margin-bottom: 0; height: 13px; }
.skeleton-block { height: 96px; border-radius: 10px; }

.wb-splitter {
  flex: none;
  width: 5px;
  cursor: col-resize;
  background: var(--border);
  transition: background var(--dur-fast) var(--ease-out);
}
.wb-splitter:hover { background: var(--accent); }
.wb-splitter:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
  background: var(--accent);
}

.wb-right { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; background: var(--editor-bg); }
.code-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: var(--space-2) 12px;
  background: var(--editor-bar);
  border-bottom: 1px solid var(--editor-border);
}
.code-toolbar select { background: var(--editor-control); color: var(--editor-text); border: 1px solid var(--editor-control-border); padding: 5px 10px; font-size: var(--fs-sm); }
.code-toolbar select:hover { border-color: var(--editor-control-hover); }
.mode-tag { color: var(--editor-text-muted); font-size: var(--fs-xs); }

/* 语言语法包按需加载时的提示：编辑器仍可输入，仅高亮尚未生效 */
.lang-loading {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-xs);
  color: var(--editor-text-muted);
  animation: m-fade-in var(--dur-base) var(--ease-out) both;
}
.lang-loading .spinner {
  width: 12px;
  height: 12px;
  border-width: 2px;
  color: var(--editor-text-muted);
}
.code-toolbar .tb-btn { background: var(--editor-control); color: var(--editor-text); border: 1px solid transparent; }
.code-toolbar .tb-btn:hover:not(:disabled) { background: var(--editor-control-active); color: var(--editor-text-strong); border-color: transparent; }
.code-toolbar .spacer { flex: 1; }
.file-tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: var(--editor-tab);
  color: var(--editor-text-strong);
  border: 1px solid var(--editor-tab-border);
  border-radius: 6px;
  padding: var(--space-1) 12px;
  font-size: var(--fs-sm);
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
.cm-host :deep(.cm-gutters) { border-right: 1px solid var(--editor-border); }

.result-panel { flex: none; border-top: 1px solid var(--border); background: var(--panel); display: flex; flex-direction: column; height: 250px; }
.result-panel.collapsed { height: auto; }
.rp-tabs {
  display: flex;
  align-items: center;
  gap: var(--space-1);
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
.rp-badge { font-style: normal; font-size: var(--fs-2xs); background: var(--panel-2); border-radius: 999px; padding: 0 6px; margin-left: var(--space-1); }
.run-btn { background: var(--ok); }
.run-btn:hover:not(:disabled) { background: color-mix(in srgb, var(--ok) 82%, #000); }
.submit-btn { background: var(--accent); }
.submit-btn:hover:not(:disabled) { background: var(--accent-strong); }
.result-panel .rp-tabs .mini-btn { border-color: var(--border-strong); }

.rp-body { flex: 1; overflow-y: auto; padding: var(--space-3) 16px; min-height: 0; }
.rp-idle { display: grid; place-items: center; height: 100%; color: var(--muted); font-size: 13.5px; }
.rp-pending { display: flex; align-items: center; gap: 10px; height: 100%; justify-content: center; color: var(--accent); font-size: var(--fs-base); }
.spin {
  width: 15px;
  height: 15px;
  border: 2px solid color-mix(in srgb, var(--accent) 34%, transparent);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.result-line { display: flex; align-items: center; gap: var(--space-4); font-size: var(--fs-base); }
.result-score strong { color: var(--ok); font-size: 17px; margin: 0 2px; }
.result-hint { margin-top: var(--space-2); font-size: 12.5px; }

.selftest-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.st-label { font-size: 12.5px; font-weight: 600; color: var(--muted); margin-bottom: 6px; display: flex; align-items: center; gap: var(--space-2); }
.st-area { width: 100%; resize: vertical; font-size: 12.5px; }
.st-out { margin: 0; background: var(--panel-2); border: 1px solid var(--border); border-radius: 9px; padding: 9px 11px; min-height: 92px; max-height: 180px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.st-out.bad { border-color: color-mix(in srgb, var(--danger) 32%, transparent); background: var(--danger-soft); }
.st-out.dim { color: var(--muted); }
.st-err { margin: 10px 0 0; color: var(--danger); font-size: 12.5px; white-space: pre-wrap; }

.sub-table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); margin: 0; box-shadow: none; border: none; }
.sub-table th, .sub-table td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; }
.sub-table th { background: var(--panel-2); font-size: var(--fs-xs); }

.drawer {
  width: min(430px, 92vw);
  background: var(--panel);
  height: 100%;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-lg);
}
.drawer-head { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border-bottom: 1px solid var(--border); }
.drawer-progress { padding: var(--space-3) 16px; border-bottom: 1px solid var(--border); background: var(--panel-2); }
.drawer-progress :deep(.progress-card) { box-shadow: none; border: none; background: transparent; padding: 0; }
.level-strip { display: flex; gap: 6px; padding: var(--space-3) 16px 0; flex-wrap: wrap; }
.level-tab { border: 1px solid var(--border); background: var(--panel); color: var(--muted); box-shadow: none; padding: 7px 11px; font-size: 12.5px; border-radius: 9px; }
.level-tab:hover { box-shadow: none; background: var(--panel-2); }
.level-tab.active { background: var(--accent-soft); border-color: color-mix(in srgb, var(--accent) 34%, transparent); color: var(--accent-strong); }
.level-tab small { margin-left: 5px; opacity: 0.75; }
.list-toolbar { display: flex; gap: var(--space-2); padding: var(--space-3) 16px; }
.list-toolbar input { flex: 1; min-width: 0; padding: var(--space-2) 10px; }
.list-toolbar select { width: 104px; padding: var(--space-2) 7px; font-size: var(--fs-xs); }
.drawer-list { flex: 1; overflow-y: auto; border-top: 1px solid var(--border); }
.problem-row {
  width: 100%;
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) auto;
  gap: var(--space-2);
  text-align: left;
  justify-content: initial;
  background: transparent;
  color: var(--text);
  border: 0;
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  box-shadow: none;
  padding: var(--space-3) 16px;
  font-weight: 500;
  font-size: 13.5px;
}
.problem-row:hover { background: var(--panel-2); box-shadow: none; }
.problem-row.selected { background: var(--accent-soft); box-shadow: inset 3px 0 var(--accent); }
.problem-no { color: var(--muted); font-size: var(--fs-2xs); }
.problem-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.problem-state { font-size: var(--fs-2xs); }

.copy-tip {
  position: fixed;
  bottom: 40px;
  left: 50%;
  transform: translateX(-50%);
  background: #0f172a;
  color: #fff;
  padding: var(--space-2) 16px;
  border-radius: 9px;
  font-size: var(--fs-sm);
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

@media (max-width: 640px) {
  /* 工具条改为多行排布，按钮保持内容宽度，避免小屏下被拉伸成竖排文字 */
  .wb-topbar { flex-wrap: wrap; row-gap: var(--space-2); padding: var(--space-2) 12px; }
  .wb-topbar button,
  .code-toolbar button,
  .rp-tabs button { width: auto; flex: none; }
  .tb-nav { margin-left: 0; }
  .tb-progress { margin-left: auto; }
  .tb-title { flex: 1 1 100%; order: 3; }
  .tb-title strong { max-width: none; white-space: normal; }
  .wb-left { max-height: 46vh; }
  .wb-right { min-height: 380px; }
  .result-panel { height: auto; max-height: 44vh; }
  .code-toolbar { flex-wrap: wrap; row-gap: 6px; }
}
</style>
