<template>
  <div class="leaderboard-modal" v-if="visible" @click.self="$emit('close')">
    <div ref="dialogPanel" class="modal-content" role="dialog" aria-modal="true" aria-label="性能排行榜" tabindex="-1">
      <div class="modal-header">
        <div class="header-left">
          <Icon icon="mdi:trophy" class="trophy-icon" />
          <h3>性能排行榜</h3>
        </div>
        <button aria-label="关闭性能排行榜" class="close-btn" @click="$emit('close')">
          <Icon icon="mdi:close" />
        </button>
      </div>

      <div class="modal-body">
        <div class="tabs" role="group" aria-label="排行依据">
          <button 
            class="tab-btn" 
            :class="{ active: activeTab === 'time' }" :aria-pressed="activeTab === 'time'"
            @click="activeTab = 'time'">
            <Icon icon="mdi:timer" />
            最快时间
          </button>
          <button 
            class="tab-btn" 
            :class="{ active: activeTab === 'memory' }" :aria-pressed="activeTab === 'memory'"
            @click="activeTab = 'memory'">
            <Icon icon="mdi:memory" />
            最少内存
          </button>
        </div>

        <div v-if="loading" class="loading-state">
          <Icon icon="mdi:loading" class="spin-icon" />
          加载中...
        </div>

        <div v-else-if="error" class="error-state">
          <Icon icon="mdi:alert-circle" />
          {{ error }}
          <button class="tab-btn" @click="reload++">重试加载</button>
        </div>

        <div v-else class="leaderboard-list">
          <div v-if="currentList.length === 0" class="empty-state">
            <Icon icon="mdi:trophy-outline" />
            <p>暂无排行数据</p>
          </div>

          <div v-else class="rank-table">
            <div class="rank-header">
              <span class="col-rank">排名</span>
              <span class="col-student">学生</span>
              <span class="col-lang">语言</span>
              <span class="col-perf">{{ activeTab === 'time' ? '运行时间' : '内存占用' }}</span>
              <span class="col-date">提交时间</span>
            </div>

            <div class="rank-items">
              <div 
                v-for="(entry, index) in currentList" 
                :key="entry.submissionId"
                class="rank-item">
                <span class="col-rank">
                  <span class="rank-badge">{{ index + 1 }}</span>
                </span>
                <span class="col-student">{{ entry.studentName }}</span>
                <span class="col-lang">
                  <span class="lang-tag">{{ formatLanguage(entry.language) }}</span>
                </span>
                <span class="col-perf perf-value">
                  <Icon :icon="activeTab === 'time' ? 'mdi:timer' : 'mdi:memory'" />
                  {{ activeTab === 'time' ? entry.timeMs + 'ms' : formatMemory(entry.memoryKb) }}
                </span>
                <span class="col-date">{{ formatDate(entry.submittedAt) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="modal-footer">
        <p class="footer-note">
          <Icon icon="mdi:information" />
          排行榜仅展示已通过(AC)的提交，每位学生取最优成绩
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useDialogFocus } from '../composables/useDialogFocus'
import { api } from '../api'

const props = defineProps({
  visible: Boolean,
  problemId: Number
})

const emit = defineEmits(['close'])

const activeTab = ref('time')
const loading = ref(false)
const error = ref(null)
const leaderboardData = ref(null)
const reload = ref(0)

const currentList = computed(() => {
  if (!leaderboardData.value) return []
  return activeTab.value === 'time' 
    ? leaderboardData.value.byTime 
    : leaderboardData.value.byMemory
})

watch([() => props.visible, () => props.problemId, reload], async ([visible, problemId], _, onCleanup) => {
  // 切题或关闭后忽略旧请求，避免其他题目的排行覆盖当前题目。
  let active = true
  onCleanup(() => { active = false })
  leaderboardData.value = null
  error.value = null
  loading.value = false
  if (!visible || !problemId) return
  loading.value = true
  try {
    const data = await api(`/student/problems/${problemId}/leaderboard?limit=50`)
    if (active) leaderboardData.value = data
  } catch (err) {
    if (active) error.value = err.message || '加载排行榜失败'
  } finally {
    if (active) loading.value = false
  }
}, { immediate: true })



function formatLanguage(lang) {
  const map = { C: 'C', CPP: 'C++', PYTHON: 'Python', JAVA: 'Java' }
  return map[lang] || lang
}

function formatMemory(kb) {
  if (kb == null || kb <= 0) return '—'
  if (kb >= 1024) {
    return (kb / 1024).toFixed(1) + 'MB'
  }
  return kb + 'KB'
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  return dateStr.replace('T', ' ').slice(0, 16)
}
const dialogPanel = ref(null)
useDialogFocus(() => props.visible, dialogPanel, () => emit('close'))
</script>

<style scoped>
.leaderboard-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-content {
  background: var(--panel);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  width: 100%;
  max-width: 900px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.trophy-icon {
  font-size: 28px;
  color: var(--muted);
}

.modal-header h3 {
  margin: 0;
  font-size: 20px;
  color: var(--text);
}

.close-btn {
  width: 36px;
  height: 36px;
  padding: 0;
  display: grid;
  place-items: center;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--muted);
  transition: all 0.15s ease;
}

.close-btn:hover {
  background: var(--bg);
  color: var(--text);
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}

.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
  border-radius: 8px;
  font-weight: 500;
  transition: all 0.15s ease;
}

.tab-btn:hover {
  background: var(--bg);
  color: var(--text);
}

.tab-btn.active {
  background: var(--accent-soft);
  border-color: var(--border);
  color: var(--accent);
}

.loading-state, .error-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--muted);
  gap: 12px;
}

.loading-state svg, .error-state svg, .empty-state svg {
  font-size: 48px;
  opacity: 0.5;
}

.rank-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rank-header {
  display: grid;
  grid-template-columns: 48px minmax(100px, 1fr) 80px 120px 140px;
  gap: 12px;
  padding: 12px 16px;
  background: var(--panel-2);
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.rank-items {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.rank-item {
  display: grid;
  grid-template-columns: 48px minmax(100px, 1fr) 80px 120px 140px;
  gap: 12px;
  padding: 14px 16px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
  align-items: center;
  transition: all 0.15s ease;
}

.rank-item:hover {
  background: var(--panel-2);
}




.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  font-size: 16px;
}




.col-student {
  font-weight: 600;
  color: var(--text);
}

.lang-tag {
  display: inline-block;
  padding: 4px 10px;
  background: var(--panel-2);
  color: var(--muted);
  border-radius: 5px;
  font-size: 12px;
  font-weight: 600;
}

.perf-value {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--ok);
}

.col-date {
  font-size: 13px;
  color: var(--muted);
}

.modal-footer {
  padding: 16px 24px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}

.footer-note {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

@media (max-width: 768px) {
  .rank-header, .rank-item {
    grid-template-columns: 32px minmax(70px, 1fr) minmax(80px, auto);
    gap: 8px;
    padding-inline: 4px;
    font-size: 12px;
  }
  
  .col-lang, .col-date {
    display: none;
  }
}
@media (max-width: 480px) { .modal-body, .modal-header, .modal-footer { padding: 16px; } .footer-note { align-items: flex-start; } .footer-note svg { flex-shrink: 0; } }
.modal-content:focus { outline: none; }
</style>
