<template>
  <div class="oj-workbench" :class="{ 'mobile-code': mobilePane === 'code' }" :style="{ '--editor-font-size': editorFontSize + 'px' }">
    <div v-if="loadError" class="workbench-error" role="alert">
      {{ loadError }} <button class="mini-btn" @click="loadProblems">重新加载</button>
    </div>
    <!-- 顶部工具条 -->
    <div class="wb-topbar">
      <button class="tb-btn" @click="drawer = true" aria-haspopup="dialog" :aria-expanded="drawer">
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
        <button class="tb-btn" :disabled="!hasPrev" @click="step(-1)"><Icon icon="mdi:chevron-left" aria-hidden="true" />上一题</button>
        <button class="tb-btn" :disabled="!hasNext" @click="step(1)">下一题<Icon icon="mdi:chevron-right" aria-hidden="true" /></button>
      </div>
      <div class="tb-progress">
        <strong>{{ passedCount }}</strong>/<span>{{ problems.length }}</span>
        <small>已通过</small>
      </div>
    </div>

    <div class="mobile-pane-tabs" role="group" aria-label="切换工作区">
      <button :aria-pressed="mobilePane === 'problem'" @click="mobilePane = 'problem'"><Icon icon="mdi:text-box-outline" aria-hidden="true" />题目描述</button>
      <button :aria-pressed="mobilePane === 'code'" @click="showMobileCode"><Icon icon="mdi:code-braces" aria-hidden="true" />代码与结果</button>
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
          <span class="meta-item" v-if="meta.maxSubmissions">已提交 {{ attemptCount }}/{{ meta.maxSubmissions }} 次</span>
          <span v-if="meta.window === 'CLOSED'" class="meta-item closed-tip">已收卷，禁止提交（可查看题目与成绩）</span>
        </div>
        <template v-if="selected">
          <header class="pr-head">
            <div class="pr-title">
              <h3>{{ selected.title }}</h3>
            </div>
            <div class="pr-meta">
              <DifficultyBadge v-if="selected.difficulty" :difficulty="selected.difficulty" />
              <span class="meta-item">时间限制:{{ selected.timeLimitMs / 1000 }}s</span>
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
                  <button v-if="sample.expectedOutput != null" class="mini-btn" @click="copyText(sample.expectedOutput, '输出已复制')">复制输出</button>
                  <button class="mini-btn" :disabled="running || !code.trim() || !canSubmitNow" @click="runSample(sample)">用此样例自测</button>
                </div>
                <div class="sample-io"><span>输入</span><pre>{{ sample.input }}</pre></div>
                <div class="sample-io" v-if="sample.expectedOutput != null"><span>输出</span><pre>{{ sample.expectedOutput }}</pre></div>
              </div>
            </div>

            <p class="pr-tip">提交后代码将进入隔离沙盒执行全部隐藏用例;自测运行不占提交次数。</p>
          </div>
        </template>
        <div v-else class="pr-empty" role="status"><Icon icon="mdi:code-braces" aria-hidden="true" /><span>{{ loading ? '正在加载题目…' : '打开「题目列表」，开始下一次练习' }}</span></div>
      </section>

      <!-- 可拖拽分隔条 -->
      <div class="wb-splitter" role="separator" aria-label="调整题面宽度" aria-orientation="vertical" :aria-valuenow="leftWidth" :aria-valuemin="320" :aria-valuemax="maxLeftWidth" tabindex="0" @mousedown="startDrag" @keydown.left.prevent="resizeLeft(-20)" @keydown.right.prevent="resizeLeft(20)"></div>

      <!-- 右栏:编辑器 + 结果面板 -->
      <section class="wb-right">
        <div class="code-toolbar">
          <span class="file-tab" :style="{ '--dot': langDot }">{{ fileName }}</span>
          <select v-model="language" aria-label="选择编程语言" :disabled="!selected || problemLoading">
            <option v-for="item in selected?.languages || langs" :key="item" :value="item">{{ langName(item) }}</option>
          </select>
          <span class="mode-tag" role="status">{{ draftStatus }}</span>
          <button v-if="legacyDraft !== null" class="tb-btn" @click="restoreLegacyDraft">恢复旧版草稿</button>
          <span class="spacer"></span>
          <select v-model.number="editorFontSize" aria-label="代码字号" @change="saveEditorFontSize"><option v-for="size in [13, 14, 16, 18]" :key="size" :value="size">{{ size }} px</option></select>
          <button class="tb-btn" :disabled="!selected || problemLoading" @click="showTemplates = true">
            <Icon icon="mdi:code-braces" />
            模板
          </button>
          <details ref="moreTools" class="more-tools">
            <summary aria-label="更多编辑器工具"><Icon icon="mdi:dots-horizontal" aria-hidden="true" /></summary>
            <div class="tools-menu">
              <button @click="openTool('shortcuts')"><Icon icon="mdi:keyboard-outline" aria-hidden="true" />快捷键</button>
              <button @click="openTool('leaderboard')"><Icon icon="mdi:trophy-outline" aria-hidden="true" />性能排行榜</button>
              <button @click="openTool('reset')" :disabled="!selected || problemLoading"><Icon icon="mdi:refresh" aria-hidden="true" />重置代码</button>
            </div>
          </details>
        </div>

        <div class="cm-wrap" :inert="problemLoading || !selected">
          <div ref="cmHost" class="cm-host"></div>
        </div>

        <!-- 底部结果面板 -->
        <div class="result-panel" :class="{ collapsed: !panelOpen }">
          <div class="rp-header">
          <div class="rp-tabs" role="group" aria-label="评测面板">
            <button v-for="t in panels" :key="t.key" class="rp-tab"
                    :aria-pressed="panel === t.key && panelOpen" :class="{ active: panel === t.key && panelOpen }" @click="openPanel(t.key)">
              {{ t.label }}
              <em v-if="t.key === 'submissions'" class="rp-badge">{{ submissions.length }}</em>
            </button>
          </div>
          <div class="run-actions">
            <button class="mini-btn panel-toggle" @click="panelOpen = !panelOpen" :aria-label="panelOpen ? '收起结果面板' : '展开结果面板'" :aria-expanded="panelOpen"><Icon :icon="panelOpen ? 'mdi:chevron-down' : 'mdi:chevron-up'" aria-hidden="true" /></button>
            <button class="run-btn" :disabled="running || !selected || !code.trim() || !canSubmitNow" @click="runFromButton">
              <Icon :icon="running ? 'mdi:loading' : 'mdi:play'" :class="{ 'spin-icon': running }" />
              {{ running ? '运行中…' : '自测运行' }}
            </button>
            <button class="submit-btn"
                    :disabled="submitting || !selected || !code.trim() || !canSubmitNow || limitReached" @click="submit">
              <Icon :icon="submitting ? 'mdi:loading' : 'mdi:send'" :class="{ 'spin-icon': submitting }" />
              {{ submitting ? '提交中…' : problemLoading ? '题目加载中…' : !canSubmitNow ? '窗口未开放' : limitReached ? '次数已用尽' : '提交评测' }}
            </button>
          </div>
          </div>

          <div v-if="panelOpen" class="rp-body">
            <p v-if="submissionError" class="st-err" role="alert">{{ submissionError }}</p>
            <p v-if="statusError" class="st-err" role="alert">
              {{ statusError }} <button class="mini-btn" :disabled="statusLoading" @click="refreshSubmissionStatus">重试刷新</button>
            </p>
            <!-- 执行结果 -->
            <template v-if="panel === 'result'">
              <div v-if="resultPhase === 'idle'" class="rp-idle"><Icon icon="mdi:console-line" aria-hidden="true" /><span>先自测，再提交</span><small>自测不占提交次数，提交评测后在这里查看结果。</small></div>
              <div v-else-if="resultPhase === 'pending'" class="rp-pending">
                <Icon icon="mdi:loading" class="spin-icon" /> 代码已送入安全沙盒,正在评测隐藏用例…
              </div>
              <template v-else>
                <div class="result-line">
                  <span class="muted">第 {{ latestResult.attemptNo }} 次提交</span>
                  <span class="chip" :class="stateClass(latestResult.status)">
                    <Icon :icon="getStatusIcon(latestResult.status)" />
                    {{ stateText(latestResult.status) }}
                  </span>
                  <span v-if="latestResult.score !== null" class="result-score">得分 <strong>{{ latestResult.score }}</strong>/100</span>
                  <span v-if="latestResult.timeMs !== null" class="muted">运行时间:{{ latestResult.timeMs }}ms</span>
                  <span v-if="latestResult.memoryKb != null && latestResult.memoryKb >= 0" class="muted">内存:{{ fmtMem(latestResult.memoryKb) }}</span>
                </div>
                <p v-if="latestResult.score !== null && latestResult.score < 100" class="muted result-hint">
                  未全部通过:可通过左侧样例对照输出,或用「自测运行」调试代码
                </p>
                <CaseDetails v-if="caseDetails.length" :case-details="caseDetails" />
                <p v-if="caseDetailsLoading" class="muted">测试点详情加载中…</p>
                <p v-else-if="caseDetailsError" class="st-err" role="alert">
                  {{ caseDetailsError }} <button class="mini-btn" @click="loadCaseDetails(selectedSubmissionId)">重试加载详情</button>
                </p>
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
              <p v-if="selfTestStale" class="muted" role="status">代码、语言或输入已修改，以下为上次运行结果，请重新自测。</p>
              <div class="selftest-grid">
                <div class="st-io">
                  <div class="st-label">自测输入 <small>(可粘贴样例输入)</small></div>
                  <textarea v-model="selfTestInput" aria-label="自测输入" rows="5" spellcheck="false" class="st-area mono"></textarea>
                </div>
                <div class="st-io">
                  <div class="st-label">
                    运行输出
                    <span v-if="selfTestResult">
                      <span class="chip" :class="selfTestStatus.className" role="status">{{ selfTestStatus.text }}</span>
                      <span class="muted" v-if="selfTestResult.timeUs != null">运行时间:{{ fmtUs(selfTestResult.timeUs) }}</span>
                      <span class="muted" v-if="selfTestResult.peakMemoryKb != null && selfTestResult.peakMemoryKb >= 0">运行内存:{{ fmtMem(selfTestResult.peakMemoryKb) }}</span>
                    </span>
                  </div>
                  <pre v-if="selfTestResult" class="st-area mono st-out" :class="{ bad: selfTestStatus.failed }">{{ selfTestOutput }}</pre>
                  <pre v-else class="st-area mono st-out dim">运行后显示输出</pre>
                </div>
              </div>
              <p v-if="selfTestResult?.message" class="st-err" role="alert">{{ selfTestResult.message }}</p>
              <p v-if="selfTestResult?.compileError" class="st-err mono">{{ selfTestResult.compileError }}</p>
              <p v-else-if="selfTestResult?.stderr" class="st-err mono">{{ selfTestResult.stderr }}</p>
              <p v-if="selfTestResult?.timedOut" class="st-err">运行超时,请检查是否有死循环或阻塞输入</p>
              <SampleCompare
                v-if="showSampleCompare"
                visible
                :input="selfTestContext.input"
                :expected="selfTestContext.expectedOutput"
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
              <button class="mini-btn" :disabled="statusLoading" @click="refreshSubmissionStatus">{{ statusLoading ? '刷新中…' : '刷新记录' }}</button>
              <table v-if="submissions.length" class="sub-table">
                <thead><tr><th>#</th><th>状态</th><th>得分</th><th>语言</th><th>耗时</th><th>内存</th><th>提交时间</th><th>详情</th></tr></thead>
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
                    <td><button class="mini-btn" @click="viewSubmission(s)">查看结果</button></td>
                  </tr>
                </tbody>
              </table>
              <div v-else class="rp-idle">{{ statusLoading ? '提交记录加载中…' : '本题暂无提交记录' }}</div>
            </template>
          </div>
        </div>
      </section>
    </div>

    <!-- 题目列表抽屉 -->
    <div v-if="drawer" class="drawer-mask" @click.self="drawer = false">
      <div ref="drawerPanel" class="drawer" role="dialog" aria-modal="true" aria-label="题目列表" tabindex="-1">
        <div class="drawer-head">
          <strong>题目列表</strong>
          <span class="spacer"></span>
          <span class="muted">{{ passedCount }}/{{ problems.length }} 已通过</span>
          <button class="mini-btn" @click="drawer = false" aria-label="关闭题目列表"><Icon icon="mdi:close" aria-hidden="true" /></button>
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
          <input v-model.trim="keyword" placeholder="搜索题号或题目" aria-label="搜索题目" type="search" />
          <select v-model="statusFilter" aria-label="题目状态">
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
    </div>

    <TemplatePicker :visible="showTemplates" :language="language" @close="showTemplates = false" @insert="insertTemplate" />
    <ShortcutHelp :visible="showShortcuts" @close="showShortcuts = false" />
    <Leaderboard :visible="showLeaderboard" :problem-id="selected?.problemId" @close="showLeaderboard = false" />

    <div v-if="tipText" class="copy-tip" role="status">{{ tipText }}</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { useDialogFocus } from '../composables/useDialogFocus'
import { createDraftStore } from '../composables/draftStore'
import { normalizeOutput, selfTestFeedback } from '../composables/selfTestFeedback'
import { summarizeSubmissions } from '../composables/submissionSummary'
import { useJudgeStatus } from '../composables/useJudgeStatus'
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
import { cpp } from '@codemirror/lang-cpp'
import { python } from '@codemirror/lang-python'
import { java } from '@codemirror/lang-java'

const { getStatusIcon, getStatusText, getStatusClass } = useJudgeStatus()

const props = defineProps({
  mode: { type: String, default: 'practice' },
  targetId: { type: [Number, String], default: null },
  title: { type: String, default: '' },
  meta: { type: Object, default: null }
})
const isAssignment = computed(() => props.mode === 'assignment')
const canSubmitNow = computed(() =>
  !problemLoading.value && (!isAssignment.value || (props.meta && props.meta.window === 'OPEN')))

// VS Code Dark+ 风格的主题
const vscodeTheme = [
  EditorView.theme({
    '&': { color: '#d4d4d4', backgroundColor: '#1e1e1e', height: '100%', fontSize: 'var(--editor-font-size, 14px)' },
    '.cm-content': { caretColor: '#aeafad', padding: '16px 0', fontFamily: "'Cascadia Code', 'JetBrains Mono', Consolas, monospace" },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#aeafad' },
    '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground': {
      backgroundColor: '#264f7890'
    },
    '.cm-activeLine': { backgroundColor: '#2a2d2e' },
    '.cm-activeLineGutter': { backgroundColor: '#2a2d2e', color: '#c6c6c6' },
    '.cm-gutters': { backgroundColor: '#1e1e1e', color: '#858585', border: 'none', borderRight: '1px solid #333333' },
    '.cm-lineNumbers .cm-gutterElement': { padding: '0 9px 0 12px', minWidth: '38px' },
    '.cm-foldGutter': { color: '#858585' },
    '.cm-scroller': { lineHeight: '1.7', fontFamily: "'Cascadia Code', 'JetBrains Mono', Consolas, monospace" },
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
const problemLoading = ref(false)
const loadError = ref('')
const drawer = ref(false)
const drawerPanel = ref(null)
useDialogFocus(() => drawer.value, drawerPanel, () => { drawer.value = false })
const moreTools = ref(null)
const mobilePane = ref('problem')
async function showMobileCode() { mobilePane.value = 'code'; await nextTick(); editorView?.requestMeasure() }
const savedFontSize = Number(localStorage.getItem('oj-editor-font-size'))
const editorFontSize = ref([13, 14, 16, 18].includes(savedFontSize) ? savedFontSize : 14)
function saveEditorFontSize() { localStorage.setItem('oj-editor-font-size', editorFontSize.value) }
function openTool(tool) {
  moreTools.value.open = false
  moreTools.value.querySelector('summary').focus()
  if (tool === 'shortcuts') showShortcuts.value = true
  if (tool === 'leaderboard') showLeaderboard.value = true
  if (tool === 'reset') resetCode()
}

const language = ref('CPP')
const code = ref('')
const submitting = ref(false)
const submissionError = ref('')
const statusError = ref('')
const statusLoading = ref(false)
const selectedSubmissionId = ref(null)
const attemptCount = ref(0)
watch(() => props.meta?.attemptCount, count => { attemptCount.value = count ?? 0 }, { immediate: true })
const limitReached = computed(() => isAssignment.value && props.meta?.maxSubmissions != null
  && attemptCount.value >= props.meta.maxSubmissions)
const pendingRequests = new Map()
let statusVersion = 0
let detailsVersion = 0
let displayedResultKey = ''
const running = ref(false)
const panelOpen = ref(true)
const panel = ref('result')
const resultPhase = ref('idle')
const latestResult = ref({ status: null, score: null, timeMs: null })
const selfTestInput = ref('')
const selfTestResult = ref(null)
const selfTestContext = ref(null)
let runVersion = 0
const submissions = ref([])
const leftWidth = ref(480)
const viewportWidth = ref(window.innerWidth)
const maxLeftWidth = computed(() => Math.max(320, viewportWidth.value - 420))
function resizeLeft(delta) { leftWidth.value = Math.min(maxLeftWidth.value, Math.max(320, leftWidth.value + delta)) }
function onViewportResize() {
  viewportWidth.value = window.innerWidth
  // 手机端不显示分栏，保留桌面宽度，避免切回桌面时题面被压窄。
  if (viewportWidth.value > 900) resizeLeft(0)
}
let stopResize = () => {}
const showTemplates = ref(false)
const showShortcuts = ref(false)
const showLeaderboard = ref(false)
const caseDetails = ref([])
const caseDetailsLoading = ref(false)
const caseDetailsError = ref('')
const myUserId = ref(0)
const draftStatus = ref('草稿仅保存在此浏览器')
const legacyDraft = ref(null)
const drafts = createDraftStore(() => localStorage, () => {
  draftStatus.value = '本机保存失败，请复制代码备份'
})
let selectionVersion = 0
let replacingDocument = false

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
const selfTestStatus = computed(() => selfTestFeedback(selfTestResult.value, selfTestContext.value?.expectedOutput))
const selfTestStale = computed(() => selfTestResult.value && selfTestContext.value && (
  selfTestContext.value.code !== code.value || selfTestContext.value.language !== language.value
  || selfTestContext.value.input !== selfTestInput.value))
const selfTestOutput = computed(() => {
  const r = selfTestResult.value
  if (!r) return ''
  return r.output || '(无标准输出)'
})
const attemptedCount = computed(() =>
  problems.value.filter(problem => problem.status !== 'UNATTEMPTED' && problem.status !== 'AC').length)
const todoCount = computed(() => problems.value.filter(problem => problem.status === 'UNATTEMPTED').length)
const selfTestTimeMs = computed(() =>
  selfTestResult.value?.timeUs != null ? Math.max(0, Math.round(selfTestResult.value.timeUs / 1000)) : null)
const showSampleCompare = computed(() =>
  Boolean(selfTestStatus.value?.comparable))

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
function draftContext(problemId, lang) {
  return { userId: myUserId.value, mode: props.mode, targetId: props.targetId, problemId, language: lang }
}
function saveDraft() {
  if (!selected.value || !myUserId.value) return false
  const saved = drafts.save(draftContext(selected.value.problemId, language.value), code.value)
  if (saved) draftStatus.value = '草稿已保存到本机'
  return saved
}
function loadCodeFor(problemId, lang) {
  draftStatus.value = '草稿仅保存在此浏览器'
  const saved = drafts.read(draftContext(problemId, lang))
  legacyDraft.value = saved === null ? drafts.readLegacy(problemId, lang) : null
  if (saved !== null) draftStatus.value = '已恢复本机草稿'
  return saved ?? CODE_TEMPLATES[lang] ?? ''
}
function restoreLegacyDraft() {
  if (legacyDraft.value === null || !selected.value || problemLoading.value) return
  if (!window.confirm('旧版草稿没有账号和作业标识，请确认属于你本人。恢复后将替换当前代码，是否继续？')) return
  setEditorDoc(legacyDraft.value)
  saveDraft()
  legacyDraft.value = null
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
    state: createEditorState(code.value)
  })
}

function createEditorState(doc) {
  return EditorState.create({
      doc,
      extensions: [
        EditorView.contentAttributes.of({ 'aria-label': '代码编辑器' }),
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
            if (!replacingDocument) saveDraft()
          }
        })
      ]
  })
}

function setEditorDoc(text, fresh = false) {
  code.value = text
  if (!editorView) return
  // 切题和切语言时重建撤销历史，防止撤销把另一份草稿写入当前题目。
  if (fresh) {
    editorView.setState(createEditorState(text))
    return
  }
  replacingDocument = true
  try {
    editorView.dispatch({ changes: { from: 0, to: editorView.state.doc.length, insert: text } })
  } finally {
    replacingDocument = false
  }
}

watch(language, (value, old) => {
  editorView?.dispatch({ effects: langCompartment.reconfigure(langExtension(value)) })
  if (!old || !editorView || !selected.value || problemLoading.value) return
  setEditorDoc(loadCodeFor(selected.value.problemId, value), true)
}, { flush: 'sync' })

async function loadProblems() {
  loading.value = true
  loadError.value = ''
  try {
    await ensureUserId()
    if (isAssignment.value) {
      const list = await api(`/student/targets/${props.targetId}/problems`)
      const allSubs = await api(`/student/submissions?assignmentTargetId=${props.targetId}`)
      const submissionsByProblem = new Map()
      for (const s of allSubs) {
        if (!submissionsByProblem.has(s.problemId)) submissionsByProblem.set(s.problemId, [])
        submissionsByProblem.get(s.problemId).push(s)
      }
      problems.value = list.map((p, i) => {
        const summary = summarizeSubmissions(submissionsByProblem.get(p.problemId) ?? [])
        let config = {}
        try { config = JSON.parse(p.judgeConfig || '{}') ?? {} } catch { /* 兼容缺少配置的旧快照 */ }
        return {
          ...p,
          code: p.code || `P${String(i + 1).padStart(2, '0')}`,
          difficulty: null,
          status: summary.status,
          bestScore: summary.bestScore,
          assignmentTargetId: Number(props.targetId),
          timeLimitMs: p.timeLimitMs ?? config.timeLimitMs ?? 10000,
          memoryLimitMb: p.memoryLimitMb ?? config.memoryLimitMb ?? 256
        }
      })
      if (problems.value.length) await selectProblem(problems.value[0].problemId)
    } else {
      problems.value = await api('/student/practice/problems')
      if (problems.value.length) await selectProblem(selectedId.value || problems.value[0].problemId)
    }
  } catch (error) {
    loadError.value = error.message || '题目加载失败，请重试'
  } finally {
    loading.value = false
  }
}

async function selectProblem(problemId) {
  const version = ++selectionVersion
  statusVersion++
  detailsVersion++
  selectedSubmissionId.value = null
  displayedResultKey = ''
  statusError.value = ''
  statusLoading.value = false
  submissionError.value = ''
  caseDetailsError.value = ''
  caseDetailsLoading.value = false
  problemLoading.value = true
  loadError.value = ''
  selectedId.value = problemId
  selected.value = null
  resultPhase.value = 'idle'
  latestResult.value = { status: null, score: null, timeMs: null }
  selfTestResult.value = null
  selfTestContext.value = null
  runVersion++
  running.value = false
  caseDetails.value = []
  submissions.value = []
  clearPoll()
  try {
    const problem = isAssignment.value
      ? problems.value.find(problem => problem.problemId === problemId) || null
      : await api(`/student/practice/problems/${problemId}`)
    // 快速切题时，较慢的旧请求不能覆盖当前题目或其草稿。
    if (version !== selectionVersion) return
    selected.value = problem
    if (!selected.value) return
    language.value = selected.value.languages?.[0] || 'CPP'
    const firstSample = selected.value.samples?.[0]
    selfTestInput.value = firstSample?.input || ''
    setEditorDoc(loadCodeFor(problemId, language.value), true)
    if (!editorView) {
      await nextTick()
      if (version !== selectionVersion) return
      mountEditor()
    }
    await refreshSubmissionStatus()
  } catch (error) {
    if (version === selectionVersion) loadError.value = error.message || '题目加载失败，请重试'
  } finally {
    if (version === selectionVersion) problemLoading.value = false
  }
}

async function refreshSubmissionStatus() {
  clearPoll()
  if (!selected.value) return
  const version = ++statusVersion
  const problem = selected.value
  statusLoading.value = true
  statusError.value = ''
  try {
    const list = await api(`/student/submissions?assignmentTargetId=${problem.assignmentTargetId}&problemId=${problem.problemId}`)
    if (version !== statusVersion) return
    const summary = summarizeSubmissions(list)
    submissions.value = summary.ordered.slice().reverse()
    problem.status = summary.status
    problem.bestScore = summary.bestScore
    const item = problems.value.find(item => item.problemId === problem.problemId)
    if (item) { item.status = summary.status; item.bestScore = summary.bestScore }
    const displayed = summary.ordered.find(row => row.submissionId === selectedSubmissionId.value) ?? summary.latest
    if (displayed) displaySubmission(displayed)
    if (list.some(row => row.judgeStatus === 'PD')) {
      pollTimer = setTimeout(() => { pollTimer = null; refreshSubmissionStatus() }, 2000)
    }
  } catch (error) {
    if (version === statusVersion) statusError.value = `提交记录/结果加载失败：${error.message}。可重试刷新，无需再次提交代码。`
  } finally {
    if (version === statusVersion) statusLoading.value = false
  }
}

function displaySubmission(submission) {
  selectedSubmissionId.value = submission.submissionId
  const key = JSON.stringify(submission)
  if (displayedResultKey === key) return
  displayedResultKey = key
  latestResult.value = {
    attemptNo: submission.attemptNo,
    status: submission.judgeStatus,
    score: submission.normalizedScore ?? null,
    timeMs: submission.totalTimeMs ?? null,
    memoryKb: submission.peakMemoryKb ?? null
  }
  resultPhase.value = submission.judgeStatus === 'PD' ? 'pending' : 'done'
  caseDetails.value = []
  caseDetailsError.value = ''
  detailsVersion++
  caseDetailsLoading.value = false
  if (submission.judgeStatus !== 'PD') loadCaseDetails(submission.submissionId)
}

function viewSubmission(submission) {
  displaySubmission(submission)
  panel.value = 'result'
  panelOpen.value = true
}

function clearPoll() {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
}

async function loadCaseDetails(submissionId) {
  if (!submissionId) return
  const version = ++detailsVersion
  caseDetailsLoading.value = true
  caseDetailsError.value = ''
  try {
    const details = await api(`/student/submissions/${submissionId}/testcases`)
    if (version === detailsVersion) caseDetails.value = details
  } catch (error) {
    if (version === detailsVersion) caseDetailsError.value = `测试点详情加载失败：${error.message}`
  } finally {
    if (version === detailsVersion) caseDetailsLoading.value = false
  }
}

function insertTemplate(templateCode) {
  if (!selected.value || problemLoading.value) return
  if (code.value && !window.confirm('使用模板将替换当前代码，是否继续？')) return
  code.value = templateCode
  setEditorDoc(templateCode)
  saveDraft()
  flashTip('模板已插入编辑器')
}

async function ensureUserId() {
  if (myUserId.value) return
  const profile = await api('/identity/me')
  if (!profile.appUserId) throw new Error('无法确认登录账号，请重新登录后重试')
  myUserId.value = profile.appUserId
}

async function submit() {
  if (submitting.value || !selected.value || !code.value.trim() || !canSubmitNow.value || limitReached.value) return
  saveDraft()
  const version = selectionVersion
  const problem = selected.value
  const request = { assignmentTargetId: problem.assignmentTargetId, problemId: problem.problemId, language: language.value, code: code.value }
  const key = JSON.stringify(request)
  // 网络中断后重试相同代码复用幂等键，避免服务端已接收却再次消耗提交次数。
  if (!pendingRequests.has(key)) pendingRequests.set(key, crypto.randomUUID())
  submitting.value = true
  submissionError.value = ''
  try {
    const accepted = await api('/student/submissions', {
      method: 'POST',
      body: { ...request, idempotencyKey: pendingRequests.get(key) }
    })
    pendingRequests.delete(key)
    if (isAssignment.value) attemptCount.value = Math.max(attemptCount.value, accepted.attemptNo)
    const item = problems.value.find(item => item.problemId === problem.problemId)
    if (item && item.status !== 'AC') item.status = accepted.judgeStatus
    if (version !== selectionVersion) return
    selectedSubmissionId.value = accepted.submissionId
    displaySubmission(accepted)
    panel.value = 'result'
    panelOpen.value = true
    await refreshSubmissionStatus()
  } catch (error) {
    if (version === selectionVersion) {
      submissionError.value = `提交未确认：${error.message}。代码已保留，可重试提交。`
      panelOpen.value = true
    }
  } finally {
    submitting.value = false
  }
}

async function runSelfTest() {
  if (!selected.value || running.value || !code.value.trim() || !canSubmitNow.value) return
  const version = ++runVersion
  const request = { problemId: selected.value.problemId, language: language.value, code: code.value, input: selfTestInput.value }
  // 对比只绑定发起请求时的样例，编辑输入或切题后不能给旧结果重新判分。
  const sample = selected.value.samples?.find(sample => normalizeOutput(sample.input) === normalizeOutput(request.input))
  selfTestContext.value = { ...request, expectedOutput: sample?.expectedOutput }
  running.value = true
  selfTestResult.value = null
  try {
    const path = isAssignment.value
      ? `/student/targets/${props.targetId}/run`
      : '/student/practice/run'
    const result = await api(path, {
      method: 'POST',
      body: request
    })
    if (version === runVersion) selfTestResult.value = result
  } catch (e) {
    if (version === runVersion) selfTestResult.value = { phase: 'REQUEST_ERROR', message: e.message }
  } finally {
    if (version === runVersion) running.value = false
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
  if (!selected.value || problemLoading.value) return
  if (!window.confirm('重置将覆盖本题当前语言的草稿，是否继续？')) return
  const template = CODE_TEMPLATES[language.value] || ''
  code.value = template
  setEditorDoc(template)
  saveDraft()
}

function openPanel(key) {
  panelOpen.value = true
  if (key === 'discussion') ensureUserId()
  if (panel.value === key && key !== 'selftest') return
  panel.value = key
  if (key === 'submissions') refreshSubmissionStatus()
}

function runFromButton() {
  openPanel('selftest')
  runSelfTest()
}

function runSample(sample) {
  selfTestInput.value = sample.input ?? ''
  showMobileCode()
  runFromButton()
}

function step(delta) {
  const next = problems.value[currentIndex.value + delta]
  if (next) selectProblem(next.problemId)
}

function startDrag(event) {
  event.preventDefault()
  const startX = event.clientX
  const startWidth = leftWidth.value
  const onMove = e => {
    leftWidth.value = Math.min(Math.max(320, startWidth + e.clientX - startX), maxLeftWidth.value)
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
  stopResize = onUp
}

function onKeydown(event) {
  if (drawer.value || showTemplates.value || showShortcuts.value || showLeaderboard.value) return
  if (event.key === 'Escape' && moreTools.value?.open) { moreTools.value.open = false; moreTools.value.querySelector('summary').focus(); return }
  const mod = event.ctrlKey || event.metaKey
  const key = event.key.toLowerCase()
  const handled = mod && (['enter', 's', '/'].includes(key)
    || (event.altKey ? ['r', 't'].includes(key) : ['[', ']'].includes(key)))
  if (!handled) return
  // 先于编辑器消费工作台快捷键，避免 Ctrl+Enter 同时插入空行并提交。
  event.preventDefault()
  event.stopPropagation()
  if (mod && event.key === 'Enter') {
    event.preventDefault()
    submit()
  }
  if (mod && event.key.toLowerCase() === 's') {
    event.preventDefault()
    if (!problemLoading.value && saveDraft()) flashTip('草稿已保存到本机')
  }
  if (mod && event.altKey && event.key.toLowerCase() === 'r') {
    event.preventDefault()
    if (canSubmitNow.value || !isAssignment.value) runFromButton()
  }
  if (mod && event.altKey && event.key.toLowerCase() === 't') {
    event.preventDefault()
    showTemplates.value = true
  }
  if (mod && event.key === '/') {
    event.preventDefault()
    showShortcuts.value = !showShortcuts.value
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
  window.addEventListener('keydown', onKeydown, true)
  window.addEventListener('resize', onViewportResize)
})
onBeforeUnmount(() => {
  selectionVersion++
  runVersion++
  statusVersion++
  detailsVersion++
  clearPoll()
  stopResize()
  window.removeEventListener('resize', onViewportResize)
  window.removeEventListener('keydown', onKeydown, true)
  editorView?.destroy()
  editorView = null
})
</script>

<style scoped>
.oj-workbench { flex: 1; display: flex; flex-direction: column; min-height: 0; height: 100%; }
.workbench-error { padding: 8px 16px; color: var(--danger); background: var(--panel); }

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
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}
.tb-btn {
  background: var(--panel);
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
.pr-head { padding: 24px 28px 18px; border-bottom: 1px solid var(--border); }
.pr-head h3 { margin: 0 0 8px; font-size: 19px; }
.pr-meta { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.meta-item { color: var(--muted); font-size: 12.5px; }
.pr-scroll { flex: 1; overflow-y: auto; padding: 24px 28px 32px; min-height: 0; }
.pr-desc { white-space: pre-wrap; line-height: 1.9; font-size: 15px; overflow-wrap: anywhere; }
.pr-samples { margin-top: 18px; display: flex; flex-direction: column; gap: 12px; }
.sample-box { border: 1px solid var(--border); border-radius: 10px; background: var(--panel-2); padding: 11px 13px; }
.sample-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; }
.sample-head strong, .sample-head button { white-space: nowrap; }
.sample-head .spacer { flex: 1; }
.mini-btn {
  background: var(--panel);
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
.pr-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; color: var(--muted); padding: 40px; }
.pr-empty > svg { width: 32px; height: 32px; }

.wb-splitter {
  flex: none;
  width: 7px;
  cursor: col-resize;
  background: var(--border);
  transition: background 0.15s ease;
}
.wb-splitter:hover, .wb-splitter:focus-visible { background: var(--accent); }

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
.mode-tag { color: #b1b8c2; font-size: 12px; }
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

.result-panel { flex: none; border-top: 1px solid var(--border); background: var(--panel); display: flex; flex-direction: column; height: clamp(190px, 28vh, 280px); }
.result-panel.collapsed { height: auto; }
.rp-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0;
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
.rp-badge { font-style: normal; font-size: 11px; background: var(--panel-2); border-radius: 999px; padding: 0 6px; margin-left: 4px; }
.run-btn { background: var(--panel); color: var(--ok); border-color: var(--border-strong); }
.run-btn:hover:not(:disabled) { background: var(--ok-soft); color: var(--ok); border-color: var(--ok); }
.submit-btn { background: var(--button-bg); }
.submit-btn:hover:not(:disabled) { background: var(--button-hover); }
.result-panel .rp-tabs .mini-btn { border-color: var(--border-strong); }

.rp-body { flex: 1; overflow-y: auto; padding: 12px 16px; min-height: 0; }
.rp-idle { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 120px; height: 100%; color: var(--muted); font-size: 14px; text-align: center; padding: 12px; }
.rp-idle > svg { width: 26px; height: 26px; color: var(--muted); margin-bottom: 3px; }
.rp-idle small { font-size: 12px; }
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
.drawer-progress { padding: 12px 16px; border-bottom: 1px solid var(--border); background: var(--panel-2); }
.drawer-progress :deep(.progress-card) { box-shadow: none; border: none; background: transparent; padding: 0; }
.level-strip { display: flex; gap: 6px; padding: 12px 16px 0; flex-wrap: wrap; }
.level-tab { border: 1px solid var(--border); background: var(--panel); color: var(--muted); box-shadow: none; padding: 7px 11px; font-size: 12.5px; border-radius: 9px; }
.level-tab:hover { box-shadow: none; background: var(--panel-2); }
.level-tab.active { background: var(--accent-soft); border-color: var(--accent); color: var(--accent-strong); }
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
  .oj-workbench button { width: auto; white-space: nowrap; }
  .wb-body { flex-direction: column; overflow-y: auto; }
  .wb-left { width: 100% !important; min-width: 0; max-width: none; }
  .wb-splitter { display: none; }
  .wb-right { flex: none; min-height: 0; }
  .cm-wrap { flex: none; height: 340px; }
  .selftest-grid { grid-template-columns: 1fr; }
  .wb-topbar { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 8px; }
  .tb-title { width: 100%; }
  .tb-title strong { min-width: 0; }
  .tb-title .chip { flex-shrink: 0; }
  .tb-nav { grid-column: 1; grid-row: 2; margin-left: 0; }
  .tb-progress { grid-column: 2; grid-row: 2; justify-self: end; white-space: nowrap; }
  .assign-meta { flex-wrap: wrap; gap: 6px 12px; padding: 12px 16px; }
  .assign-title { flex-basis: 100%; }
  .pr-scroll { flex: none; overflow: visible; }
  .sample-head .spacer, .code-toolbar .spacer, .rp-tabs .spacer { display: none; }
  .code-toolbar { flex-wrap: wrap; gap: 6px; }
  .code-toolbar > button, .code-toolbar select, .file-tab { flex-shrink: 0; }
  .mode-tag { flex-basis: 100%; order: 1; }
  .result-panel { height: auto; }
  .rp-tabs { flex-wrap: wrap; padding: 8px; }
  .rp-tabs .run-btn, .rp-tabs .submit-btn { flex: 1 0 120px; }
  .rp-body { flex: none; max-height: 400px; }
  .rp-idle, .rp-pending { height: auto; min-height: 100px; }
  .result-line, .st-label { flex-wrap: wrap; gap: 8px; }
}

.mobile-pane-tabs { display: none; }
.rp-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--border); }
.run-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.panel-toggle { padding: 7px; }
.code-toolbar { flex-wrap: wrap; }
.more-tools { position: relative; color: #d5dbe4; }
.more-tools summary { display: grid; place-items: center; list-style: none; width: 38px; height: 38px; border: 1px solid #454545; border-radius: 8px; cursor: pointer; }
.more-tools summary::-webkit-details-marker { display: none; }
.more-tools summary:hover { background: #3c3c3c; }
.more-tools summary svg { width: 22px; height: 22px; }
.tools-menu { position: absolute; top: calc(100% + 8px); right: 0; z-index: 60; width: 180px; padding: 6px; background: var(--panel); border: 1px solid var(--border); border-radius: 10px; box-shadow: var(--shadow-md); }
.tools-menu button { width: 100%; background: transparent; color: var(--text); justify-content: flex-start; font-size: 13px; font-weight: 500; }
.tools-menu button:hover { color: var(--accent); background: var(--accent-soft); }
.drawer:focus { outline: none; }
@media (max-width: 900px) {
  .mobile-pane-tabs { display: flex; gap: 4px; padding: 8px 16px; background: var(--panel); border-bottom: 1px solid var(--border); }
  .mobile-pane-tabs button { flex: 1; background: transparent; color: var(--muted); }
  .mobile-pane-tabs button[aria-pressed="true"] { background: var(--accent-soft); color: var(--accent); }
  .oj-workbench:not(.mobile-code) .wb-right, .oj-workbench.mobile-code .wb-left { display: none; }
  .pr-head { padding: 20px; }
  .pr-scroll { padding: 20px; }
  .code-toolbar .mode-tag { flex-basis: 100%; }
  .run-actions { width: 100%; }
  .run-actions .run-btn, .run-actions .submit-btn { flex: 1; }
  .rp-tabs { width: 100%; justify-content: space-between; }
  .rp-tab { padding: 8px; font-size: 13px; }
}
@media (max-width: 600px) {
  .tb-title { grid-column: 1 / -1; grid-row: 1; min-height: 28px; }
  .wb-topbar > .tb-btn { grid-column: 1; grid-row: 2; justify-self: start; }
  .tb-nav { grid-column: 2; grid-row: 2; justify-self: end; }
  .tb-nav .tb-btn { padding-inline: 9px; }
  .tb-progress { display: none; }
  .code-toolbar .file-tab { display: none; }
}
</style>
