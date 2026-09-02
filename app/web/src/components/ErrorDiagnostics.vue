<template>
  <div v-if="hasError" class="error-diagnostics">
    <div class="diagnostic-header">
      <Icon icon="mdi:lightbulb-on" class="bulb-icon" />
      <strong>错误诊断助手</strong>
    </div>
    
    <div class="diagnostic-body">
      <!-- 编译错误诊断 -->
      <template v-if="errorType === 'CE'">
        <div class="error-type">
          <Icon icon="mdi:hammer-wrench" />
          编译错误
        </div>
        <div class="error-message">{{ compileError }}</div>
        <div class="suggestions">
          <div class="suggestion-title">
            <Icon icon="mdi:help-circle" />
            可能的原因：
          </div>
          <ul>
            <li v-for="(suggestion, idx) in compileSuggestions" :key="idx">
              {{ suggestion }}
            </li>
          </ul>
        </div>
      </template>

      <!-- 运行时错误诊断 -->
      <template v-else-if="errorType === 'RE'">
        <div class="error-type">
          <Icon icon="mdi:alert-circle" />
          运行时错误
        </div>
        <div class="suggestions">
          <div class="suggestion-title">
            <Icon icon="mdi:help-circle" />
            常见原因：
          </div>
          <ul>
            <li v-for="(suggestion, idx) in runtimeSuggestions" :key="idx">
              <strong>{{ suggestion.title }}</strong>: {{ suggestion.desc }}
            </li>
          </ul>
        </div>
      </template>

      <!-- 超时诊断 -->
      <template v-else-if="errorType === 'TLE'">
        <div class="error-type">
          <Icon icon="mdi:timer-alert" />
          运行超时
        </div>
        <div class="error-info">
          <div class="info-item">
            <Icon icon="mdi:clock-outline" />
            <span>限制时间: {{ timeLimit }}ms</span>
          </div>
          <div class="info-item">
            <Icon icon="mdi:clock-alert" />
            <span>实际用时: {{ actualTime }}ms</span>
          </div>
        </div>
        <div class="suggestions">
          <div class="suggestion-title">
            <Icon icon="mdi:help-circle" />
            优化建议：
          </div>
          <ul>
            <li v-for="(suggestion, idx) in tleSuggestions" :key="idx">
              {{ suggestion }}
            </li>
          </ul>
        </div>
      </template>

      <!-- 内存超限诊断 -->
      <template v-else-if="errorType === 'MLE'">
        <div class="error-type">
          <Icon icon="mdi:memory" />
          内存超限
        </div>
        <div class="error-info">
          <div class="info-item">
            <Icon icon="mdi:memory" />
            <span>限制内存: {{ memoryLimit }}MB</span>
          </div>
          <div class="info-item">
            <Icon icon="mdi:alert-octagon" />
            <span>实际使用: {{ actualMemory }}MB</span>
          </div>
        </div>
        <div class="suggestions">
          <div class="suggestion-title">
            <Icon icon="mdi:help-circle" />
            优化建议：
          </div>
          <ul>
            <li v-for="(suggestion, idx) in mleSuggestions" :key="idx">
              {{ suggestion }}
            </li>
          </ul>
        </div>
      </template>

      <!-- 答案错误诊断 -->
      <template v-else-if="errorType === 'WA'">
        <div class="error-type">
          <Icon icon="mdi:close-circle" />
          答案错误
        </div>
        <div class="suggestions">
          <div class="suggestion-title">
            <Icon icon="mdi:help-circle" />
            调试建议：
          </div>
          <ul>
            <li v-for="(suggestion, idx) in waSuggestions" :key="idx">
              {{ suggestion }}
            </li>
          </ul>
        </div>
      </template>

      <!-- 沙盒拦截诊断 -->
      <template v-else-if="errorType === 'BSC'">
        <div class="error-type">
          <Icon icon="mdi:shield-alert" />
          非法系统调用
        </div>
        <div class="suggestions">
          <div class="suggestion-title">
            <Icon icon="mdi:help-circle" />
            可能原因：
          </div>
          <ul>
            <li v-for="(suggestion, idx) in bscSuggestions" :key="idx">
              {{ suggestion }}
            </li>
          </ul>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: String,
  compileError: String,
  stderr: String,
  timeLimit: Number,
  memoryLimit: Number,
  actualTime: Number,
  actualMemory: Number
})

const errorType = computed(() => props.status)
const hasError = computed(() => ['CE', 'RE', 'TLE', 'MLE', 'WA', 'BSC'].includes(errorType.value))

// 编译错误智能分析
const compileSuggestions = computed(() => {
  const error = props.compileError || ''
  const suggestions = []
  
  if (error.includes('undefined reference') || error.includes('未定义的引用')) {
    suggestions.push('函数或变量未声明，检查是否忘记包含头文件或定义函数')
  }
  if (error.includes('expected') || error.includes('期望')) {
    suggestions.push('语法错误，检查是否缺少分号、括号或其他符号')
  }
  if (error.includes('undeclared') || error.includes('未声明')) {
    suggestions.push('变量未声明，检查变量名拼写是否正确')
  }
  if (error.includes('cannot convert') || error.includes('无法转换')) {
    suggestions.push('类型不匹配，检查变量类型是否正确')
  }
  if (error.includes('redefinition') || error.includes('重定义')) {
    suggestions.push('变量或函数重复定义，检查是否有同名标识符')
  }
  if (error.includes('does not name a type')) {
    suggestions.push('类型名拼写错误或未包含相应头文件')
  }
  
  if (suggestions.length === 0) {
    suggestions.push('仔细阅读编译错误信息中的行号和错误描述')
    suggestions.push('检查代码语法是否符合语言标准')
    suggestions.push('确认所有使用的函数和变量都已正确声明')
  }
  
  return suggestions
})

// 运行时错误建议
const runtimeSuggestions = computed(() => [
  { title: '数组越界', desc: '访问数组时索引超出范围，检查循环边界条件' },
  { title: '空指针访问', desc: '访问未初始化或已释放的指针，检查指针是否有效' },
  { title: '栈溢出', desc: '递归深度过大或局部数组太大，考虑改用动态分配或迭代' },
  { title: '除零错误', desc: '除数为0，检查除法运算前是否判断除数' },
  { title: '整数溢出', desc: '计算结果超出数据类型范围，考虑使用更大的数据类型' }
])

// 超时优化建议
const tleSuggestions = computed(() => [
  '检查算法时间复杂度，是否存在不必要的嵌套循环',
  '是否有死循环或递归无法终止的情况',
  '考虑使用更高效的算法或数据结构（如：哈希表、二分查找）',
  '减少不必要的重复计算，使用记忆化或动态规划',
  '检查输入输出操作是否过多，尝试批量处理'
])

// 内存超限建议
const mleSuggestions = computed(() => [
  '检查是否创建了过大的数组，根据题目约束调整数组大小',
  '是否存在内存泄漏（动态分配后未释放）',
  '递归深度过大导致栈空间不足，考虑改用循环',
  '使用更紧凑的数据结构，如使用位运算压缩状态',
  '检查是否可以复用内存空间，避免重复分配'
])

// 答案错误建议
const waSuggestions = computed(() => [
  '使用题目提供的样例进行自测，逐步调试',
  '检查边界条件：空输入、单个元素、最大/最小值',
  '检查数据类型：是否需要使用 long long 防止溢出',
  '检查输出格式：空格、换行、小数位数是否符合要求',
  '添加调试输出，打印中间结果查看计算过程',
  '尝试手动构造更多测试用例，特别是特殊情况'
])

// 沙盒拦截建议
const bscSuggestions = computed(() => [
  '代码尝试访问文件系统（如 fopen, freopen），OJ环境禁止文件操作',
  '使用了被禁止的系统调用（如 system, fork, exec）',
  '尝试创建网络连接或访问外部资源',
  '使用了某些特殊库函数或系统API',
  '请使用标准输入输出（stdin/stdout）进行数据读写'
])
</script>

<style scoped>
.error-diagnostics {
  background: linear-gradient(135deg, #fef2f2 0%, #fff 100%);
  border: 1px solid #fca5a5;
  border-left: 4px solid var(--danger);
  border-radius: 10px;
  padding: 16px;
  margin-top: 12px;
}

.dark .error-diagnostics {
  background: linear-gradient(135deg, #3a1a1a 0%, var(--panel) 100%);
  border-color: #7f1d1d;
}

.diagnostic-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 15px;
  color: var(--text);
}

.bulb-icon {
  color: #f59e0b;
  font-size: 20px;
}

.diagnostic-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.error-type {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--danger);
  font-size: 14px;
}

.error-message {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 10px;
  font-family: 'Cascadia Code', 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--text);
  white-space: pre-wrap;
  overflow-x: auto;
  max-height: 200px;
}

.error-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  background: var(--panel);
  border-radius: 6px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text);
}

.suggestions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.suggestion-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--accent);
  font-size: 13px;
}

.suggestions ul {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.suggestions li {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text);
}

.suggestions li strong {
  color: var(--accent);
}
</style>
