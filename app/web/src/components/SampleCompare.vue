<template>
  <div class="sample-compare" v-if="visible">
    <div class="compare-header">
      <h4>
        <Icon :icon="passed ? 'mdi:check-circle' : 'mdi:close-circle'" 
              :class="passed ? 'icon-success' : 'icon-error'" />
        {{ passed ? '样例通过' : '样例不匹配' }}
      </h4>
      <div class="compare-meta">
        <span v-if="timeMs" class="meta-item">
          <Icon icon="mdi:timer" />
          {{ timeMs }}ms
        </span>
        <span v-if="memoryKb" class="meta-item">
          <Icon icon="mdi:memory" />
          {{ formatMemory(memoryKb) }}
        </span>
      </div>
    </div>

    <div class="compare-body">
      <!-- 输入 -->
      <div class="io-section">
        <div class="section-header">
          <Icon icon="mdi:import" />
          <strong>输入</strong>
          <button class="copy-btn" @click="copy(input)">
            <Icon icon="mdi:content-copy" />
            复制
          </button>
        </div>
        <pre class="io-content">{{ input }}</pre>
      </div>

      <!-- 并排对比 -->
      <div class="compare-section">
        <div class="output-column expected-column">
          <div class="section-header">
            <Icon icon="mdi:check" />
            <strong>预期输出</strong>
            <button class="copy-btn" @click="copy(expected)">
              <Icon icon="mdi:content-copy" />
              复制
            </button>
          </div>
          <pre class="io-content" v-html="highlightDiff(expected, actual)"></pre>
        </div>

        <div class="output-column actual-column">
          <div class="section-header">
            <Icon icon="mdi:play" />
            <strong>实际输出</strong>
            <button class="copy-btn" @click="copy(actual)">
              <Icon icon="mdi:content-copy" />
              复制
            </button>
          </div>
          <pre class="io-content" v-html="highlightDiff(actual, expected)"></pre>
        </div>
      </div>

      <!-- 差异说明 -->
      <div v-if="!passed" class="diff-info">
        <Icon icon="mdi:alert-circle" />
        <div class="diff-details">
          <p><strong>差异分析：</strong></p>
          <ul>
            <li v-if="lengthDiff">输出长度不同 (预期: {{ expected.length }} 字符, 实际: {{ actual.length }} 字符)</li>
            <li v-if="lineDiff">行数不同 (预期: {{ expectedLines.length }} 行, 实际: {{ actualLines.length }} 行)</li>
            <li v-if="whitespaceDiff">包含空格或换行符差异</li>
            <li v-else>输出内容不匹配，请仔细对比高亮部分</li>
          </ul>
        </div>
      </div>
    </div>

    <div class="compare-footer">
      <button class="toggle-btn" @click="showRaw = !showRaw">
        <Icon :icon="showRaw ? 'mdi:eye-off' : 'mdi:eye'" />
        {{ showRaw ? '隐藏' : '显示' }}原始字符
      </button>
      <div v-if="showRaw" class="raw-display">
        <div class="raw-item">
          <strong>预期 (原始):</strong>
          <code>{{ JSON.stringify(expected) }}</code>
        </div>
        <div class="raw-item">
          <strong>实际 (原始):</strong>
          <code>{{ JSON.stringify(actual) }}</code>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  visible: Boolean,
  input: String,
  expected: String,
  actual: String,
  timeMs: Number,
  memoryKb: Number
})

const showRaw = ref(false)

const passed = computed(() => normalize(props.expected) === normalize(props.actual))

const expectedLines = computed(() => (props.expected || '').split('\n'))
const actualLines = computed(() => (props.actual || '').split('\n'))

const lengthDiff = computed(() => props.expected?.length !== props.actual?.length)
const lineDiff = computed(() => expectedLines.value.length !== actualLines.value.length)
const whitespaceDiff = computed(() => {
  const exp = (props.expected || '').trim()
  const act = (props.actual || '').trim()
  return exp === act && exp !== (props.expected || '')
})

function normalize(text) {
  return (text || '').split('\n').map(line => line.replace(/\s+$/, '')).join('\n').replace(/\n+$/, '')
}

function highlightDiff(text, compare) {
  if (!text || !compare) return escapeHtml(text || '')
  
  const lines1 = (text || '').split('\n')
  const lines2 = (compare || '').split('\n')
  
  return lines1.map((line, idx) => {
    const compareLine = lines2[idx]
    if (line === compareLine) {
      return escapeHtml(line)
    }
    
    // 高亮不同的字符
    let result = ''
    const maxLen = Math.max(line.length, (compareLine || '').length)
    for (let i = 0; i < maxLen; i++) {
      const char = line[i] || ''
      const compareChar = (compareLine || '')[i] || ''
      if (char === compareChar) {
        result += escapeHtml(char)
      } else {
        result += `<mark class="diff-char">${escapeHtml(char || '∅')}</mark>`
      }
    }
    return result
  }).join('\n')
}

function escapeHtml(text) {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

function formatMemory(kb) {
  if (kb >= 1024) {
    return (kb / 1024).toFixed(1) + 'MB'
  }
  return kb + 'KB'
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    // 可以添加提示
  } catch (err) {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
  }
}
</script>

<style scoped>
.sample-compare {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  margin-top: var(--space-3);
}

.compare-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: var(--panel-2);
  border-bottom: 1px solid var(--border);
}

.compare-header h4 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: var(--fs-md);
}

.icon-success {
  color: var(--ok);
}

.icon-error {
  color: var(--danger);
}

.compare-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.meta-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--fs-sm);
  color: var(--muted);
}

.compare-body {
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.io-section, .output-column {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.section-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--text);
}

.section-header strong {
  flex: 1;
}

.copy-btn {
  padding: var(--space-1) 10px;
  font-size: var(--fs-xs);
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.copy-btn:hover {
  background: var(--bg);
  color: var(--accent);
}

.io-content {
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: var(--space-3);
  margin: 0;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: var(--fs-sm);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text);
  max-height: 200px;
  overflow-y: auto;
}

.compare-section {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
}

.expected-column .section-header {
  color: var(--ok);
}

.actual-column .section-header {
  color: var(--accent);
}

.diff-char {
  background: rgba(239, 68, 68, 0.2);
  color: var(--danger);
  font-weight: 600;
  padding: 0 2px;
  border-radius: 2px;
}

.diff-info {
  display: flex;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--warn-soft);
  border: 1px solid var(--warn);
  border-radius: 8px;
  font-size: var(--fs-sm);
}

.diff-details {
  flex: 1;
}

.diff-details p {
  margin: 0 0 8px;
  font-weight: 600;
}

.diff-details ul {
  margin: 0;
  padding-left: var(--space-5);
}

.diff-details li {
  margin: var(--space-1) 0;
}

.compare-footer {
  padding: var(--space-3) 16px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}

.toggle-btn {
  padding: 6px 12px;
  font-size: var(--fs-sm);
  background: var(--panel);
  border: 1px solid var(--border);
  color: var(--muted);
}

.raw-display {
  margin-top: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.raw-item {
  font-size: var(--fs-xs);
}

.raw-item code {
  display: block;
  margin-top: var(--space-1);
  padding: var(--space-2);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 4px;
  font-family: 'Cascadia Code', monospace;
  word-break: break-all;
}

@media (max-width: 768px) {
  .compare-section {
    grid-template-columns: 1fr;
  }
}
</style>
