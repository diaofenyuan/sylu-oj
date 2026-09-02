<template>
  <div class="contest-mode">
    <div class="contest-header">
      <div class="contest-info">
        <h2>{{ contest.title }}</h2>
        <p class="contest-desc">{{ contest.description }}</p>
      </div>
      <div class="contest-status" :class="statusClass">
        <Icon :icon="statusIcon" />
        <span>{{ statusText }}</span>
      </div>
    </div>

    <!-- 比赛倒计时 -->
    <div class="contest-timer" v-if="contest.status !== 'ended'">
      <div class="timer-section">
        <Icon icon="mdi:clock-outline" />
        <div class="timer-info">
          <div class="timer-label">
            {{ contest.status === 'pending' ? '距离开始' : '距离结束' }}
          </div>
          <div class="timer-display">
            <span class="time-unit">{{ timeLeft.days }}<small>天</small></span>
            <span class="separator">:</span>
            <span class="time-unit">{{ timeLeft.hours }}<small>时</small></span>
            <span class="separator">:</span>
            <span class="time-unit">{{ timeLeft.minutes }}<small>分</small></span>
            <span class="separator">:</span>
            <span class="time-unit">{{ timeLeft.seconds }}<small>秒</small></span>
          </div>
        </div>
      </div>
      <div v-if="contest.status === 'running' && contest.freezeTime" class="freeze-info">
        <Icon icon="mdi:snowflake" />
        <span>{{ isFrozen ? '已封榜' : `距离封榜 ${freezeTimeLeft}` }}</span>
      </div>
    </div>

    <!-- 比赛规则 -->
    <div class="contest-rules">
      <div class="rule-item">
        <Icon icon="mdi:calendar" />
        <div>
          <strong>比赛时间</strong>
          <p>{{ formatDateTime(contest.startTime) }} - {{ formatDateTime(contest.endTime) }}</p>
        </div>
      </div>
      <div class="rule-item">
        <Icon icon="mdi:format-list-numbered" />
        <div>
          <strong>题目数量</strong>
          <p>{{ contest.problems.length }} 道题目</p>
        </div>
      </div>
      <div class="rule-item">
        <Icon icon="mdi:account-group" />
        <div>
          <strong>参赛人数</strong>
          <p>{{ contest.participants }} 人</p>
        </div>
      </div>
      <div class="rule-item">
        <Icon icon="mdi:calculator" />
        <div>
          <strong>计分规则</strong>
          <p>ACM/ICPC（罚时制）</p>
        </div>
      </div>
    </div>

    <!-- 题目列表 -->
    <div class="contest-problems">
      <h3>
        <Icon icon="mdi:puzzle" />
        题目列表
      </h3>
      <div class="problems-grid">
        <div 
          v-for="(problem, idx) in contest.problems" 
          :key="problem.id"
          class="problem-card"
          :class="getProblemStatus(problem)"
          @click="goToProblem(problem)">
          <div class="problem-header">
            <div class="problem-label">{{ String.fromCharCode(65 + idx) }}</div>
            <div v-if="problem.solved" class="solved-badge">
              <Icon icon="mdi:check-circle" />
            </div>
          </div>
          <div class="problem-body">
            <h4>{{ problem.title }}</h4>
            <div class="problem-stats">
              <span class="stat-item">
                <Icon icon="mdi:account-check" />
                {{ problem.accepted }} / {{ problem.submissions }}
              </span>
              <span class="stat-item">
                <Icon icon="mdi:percent" />
                {{ problem.acceptRate }}%
              </span>
            </div>
          </div>
          <div class="problem-footer">
            <span v-if="problem.firstBlood" class="first-blood">
              <Icon icon="mdi:fire" />
              首杀
            </span>
            <span class="score">{{ problem.score }} 分</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 实时排行榜 -->
    <div class="contest-leaderboard">
      <div class="leaderboard-header">
        <h3>
          <Icon icon="mdi:podium" />
          实时排行榜
        </h3>
        <button v-if="isFrozen" @click="showFrozenTime = !showFrozenTime" class="freeze-toggle">
          <Icon icon="mdi:snowflake" />
          {{ showFrozenTime ? '显示封榜前' : '显示当前' }}
        </button>
      </div>

      <div class="leaderboard-table">
        <table>
          <thead>
            <tr>
              <th class="rank-col">排名</th>
              <th class="team-col">队伍/学生</th>
              <th class="solved-col">通过题数</th>
              <th class="penalty-col">罚时</th>
              <th 
                v-for="(problem, idx) in contest.problems" 
                :key="problem.id"
                class="problem-col">
                {{ String.fromCharCode(65 + idx) }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr 
              v-for="team in rankedTeams" 
              :key="team.id"
              :class="getRankRowClass(team.rank)">
              <td class="rank-col">
                <div class="rank-badge" :class="getRankClass(team.rank)">
                  <Icon v-if="team.rank <= 3" :icon="getRankIcon(team.rank)" />
                  {{ team.rank }}
                </div>
              </td>
              <td class="team-col">
                <div class="team-info">
                  <strong>{{ team.name }}</strong>
                  <span class="team-school">{{ team.school }}</span>
                </div>
              </td>
              <td class="solved-col">
                <strong class="solved-count">{{ team.solved }}</strong>
              </td>
              <td class="penalty-col">
                <span class="penalty-time">{{ team.penalty }}</span>
              </td>
              <td 
                v-for="(problem, idx) in contest.problems" 
                :key="problem.id"
                class="problem-col">
                <div 
                  v-if="team.problems[idx]" 
                  class="problem-cell"
                  :class="team.problems[idx].status">
                  <div class="cell-time">{{ team.problems[idx].time }}</div>
                  <div v-if="team.problems[idx].attempts > 1" class="cell-attempts">
                    -{{ team.problems[idx].attempts - 1 }}
                  </div>
                </div>
                <div v-else class="problem-cell empty"></div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 提交统计 -->
    <div class="contest-stats">
      <h3>
        <Icon icon="mdi:chart-line" />
        提交统计
      </h3>
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-value">{{ totalSubmissions }}</div>
          <div class="stat-label">总提交数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ totalAccepted }}</div>
          <div class="stat-label">通过提交</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ ((totalAccepted / totalSubmissions) * 100).toFixed(1) }}%</div>
          <div class="stat-label">通过率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ contest.participants }}</div>
          <div class="stat-label">参赛人数</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const props = defineProps({
  contestId: {
    type: Number,
    required: true
  }
})

// 模拟比赛数据
const contest = ref({
  id: 1,
  title: '2026年秋季算法竞赛',
  description: 'ACM/ICPC规则，共5道题目，时间3小时',
  startTime: '2026-09-05T14:00:00Z',
  endTime: '2026-09-05T17:00:00Z',
  freezeTime: 60, // 封榜前60分钟
  status: 'running', // pending | running | ended
  participants: 156,
  problems: [
    { id: 1, title: '简单的加法', score: 100, accepted: 120, submissions: 150, acceptRate: 80, solved: true, firstBlood: false },
    { id: 2, title: '字符串处理', score: 200, accepted: 80, submissions: 200, acceptRate: 40, solved: false, firstBlood: false },
    { id: 3, title: '图的遍历', score: 300, accepted: 50, submissions: 180, acceptRate: 28, solved: false, firstBlood: false },
    { id: 4, title: '动态规划', score: 400, accepted: 30, submissions: 120, acceptRate: 25, solved: false, firstBlood: true },
    { id: 5, title: '数据结构', score: 500, accepted: 10, submissions: 80, acceptRate: 12, solved: false, firstBlood: false }
  ]
})

const currentTime = ref(new Date())
const showFrozenTime = ref(false)
let timerInterval = null

// 排行榜数据
const teams = ref([
  {
    id: 1, name: '清华大学1队', school: '清华大学', rank: 1, solved: 5, penalty: 456,
    problems: [
      { status: 'accepted', time: 12, attempts: 1 },
      { status: 'accepted', time: 45, attempts: 2 },
      { status: 'accepted', time: 89, attempts: 1 },
      { status: 'accepted', time: 134, attempts: 3 },
      { status: 'accepted', time: 156, attempts: 1 }
    ]
  },
  {
    id: 2, name: '北京大学1队', school: '北京大学', rank: 2, solved: 4, penalty: 389,
    problems: [
      { status: 'accepted', time: 15, attempts: 1 },
      { status: 'accepted', time: 56, attempts: 1 },
      { status: 'accepted', time: 98, attempts: 2 },
      { status: 'accepted', time: 145, attempts: 1 },
      { status: 'pending', time: 0, attempts: 2 }
    ]
  },
  {
    id: 3, name: '上海交大1队', school: '上海交通大学', rank: 3, solved: 4, penalty: 412,
    problems: [
      { status: 'accepted', time: 18, attempts: 1 },
      { status: 'accepted', time: 67, attempts: 3 },
      { status: 'accepted', time: 102, attempts: 1 },
      { status: 'failed', time: 0, attempts: 5 },
      { status: 'accepted', time: 167, attempts: 1 }
    ]
  }
])

const statusClass = computed(() => {
  const status = contest.value.status
  return {
    'status-pending': status === 'pending',
    'status-running': status === 'running',
    'status-ended': status === 'ended'
  }
})

const statusIcon = computed(() => {
  const status = contest.value.status
  if (status === 'pending') return 'mdi:clock-outline'
  if (status === 'running') return 'mdi:play-circle'
  return 'mdi:check-circle'
})

const statusText = computed(() => {
  const status = contest.value.status
  if (status === 'pending') return '未开始'
  if (status === 'running') return '进行中'
  return '已结束'
})

const timeLeft = computed(() => {
  const target = contest.value.status === 'pending' 
    ? new Date(contest.value.startTime) 
    : new Date(contest.value.endTime)
  
  const diff = Math.max(0, target - currentTime.value)
  
  return {
    days: Math.floor(diff / (1000 * 60 * 60 * 24)),
    hours: Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)),
    minutes: Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60)),
    seconds: Math.floor((diff % (1000 * 60)) / 1000)
  }
})

const isFrozen = computed(() => {
  if (contest.value.status !== 'running' || !contest.value.freezeTime) return false
  const endTime = new Date(contest.value.endTime)
  const freezeStart = new Date(endTime - contest.value.freezeTime * 60 * 1000)
  return currentTime.value >= freezeStart
})

const freezeTimeLeft = computed(() => {
  if (!contest.value.freezeTime) return ''
  const endTime = new Date(contest.value.endTime)
  const freezeStart = new Date(endTime - contest.value.freezeTime * 60 * 1000)
  const diff = Math.max(0, freezeStart - currentTime.value)
  const minutes = Math.floor(diff / (1000 * 60))
  return `${minutes} 分钟`
})

const rankedTeams = computed(() => {
  return [...teams.value].sort((a, b) => {
    if (a.solved !== b.solved) return b.solved - a.solved
    return a.penalty - b.penalty
  })
})

const totalSubmissions = computed(() => {
  return contest.value.problems.reduce((sum, p) => sum + p.submissions, 0)
})

const totalAccepted = computed(() => {
  return contest.value.problems.reduce((sum, p) => sum + p.accepted, 0)
})

function getProblemStatus(problem) {
  if (problem.solved) return 'solved'
  if (problem.firstBlood) return 'first-blood'
  return ''
}

function getRankRowClass(rank) {
  if (rank === 1) return 'rank-1'
  if (rank === 2) return 'rank-2'
  if (rank === 3) return 'rank-3'
  return ''
}

function getRankClass(rank) {
  if (rank === 1) return 'rank-gold'
  if (rank === 2) return 'rank-silver'
  if (rank === 3) return 'rank-bronze'
  return ''
}

function getRankIcon(rank) {
  if (rank === 1) return 'mdi:trophy'
  if (rank === 2) return 'mdi:medal'
  if (rank === 3) return 'mdi:medal-outline'
  return ''
}

function formatDateTime(dateStr) {
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function goToProblem(problem) {
  if (contest.value.status === 'running') {
    router.push(`/contest/${contest.value.id}/problem/${problem.id}`)
  }
}

onMounted(() => {
  timerInterval = setInterval(() => {
    currentTime.value = new Date()
  }, 1000)
})

onUnmounted(() => {
  if (timerInterval) clearInterval(timerInterval)
})
</script>

<style scoped>
.contest-mode {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* 比赛头部 */
.contest-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 24px;
  background: linear-gradient(135deg, var(--panel), var(--panel-2));
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.contest-info h2 {
  margin: 0 0 8px;
  font-size: 28px;
  color: var(--text);
}

.contest-desc {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}

.contest-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border-radius: 999px;
  font-weight: 600;
  font-size: 14px;
}

.status-pending {
  background: var(--muted-soft);
  color: var(--muted);
}

.status-running {
  background: linear-gradient(135deg, #10b981, #059669);
  color: #fff;
  animation: pulse 2s infinite;
}

.status-ended {
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.8; }
}

/* 倒计时 */
.contest-timer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  background: var(--panel);
  border: 2px solid var(--accent);
  border-radius: var(--radius);
}

.timer-section {
  display: flex;
  align-items: center;
  gap: 16px;
}

.timer-section > svg {
  font-size: 32px;
  color: var(--accent);
}

.timer-label {
  font-size: 13px;
  color: var(--muted);
  margin-bottom: 8px;
}

.timer-display {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 32px;
  font-weight: 700;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.time-unit {
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
}

.time-unit small {
  font-size: 14px;
  font-weight: 400;
  color: var(--muted);
}

.separator {
  color: var(--muted);
  font-weight: 400;
}

.freeze-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: 8px;
  color: var(--accent);
  font-weight: 600;
}

/* 规则 */
.contest-rules {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.rule-item {
  display: flex;
  gap: 12px;
  padding: 20px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.rule-item > svg {
  font-size: 32px;
  color: var(--accent);
}

.rule-item strong {
  display: block;
  margin-bottom: 4px;
  color: var(--text);
}

.rule-item p {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

/* 题目网格 */
.contest-problems h3,
.contest-leaderboard h3,
.contest-stats h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
}

.problems-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 16px;
}

.problem-card {
  padding: 20px;
  background: var(--panel);
  border: 2px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  transition: all 0.2s ease;
}

.problem-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--accent);
}

.problem-card.solved {
  border-color: var(--ok);
  background: linear-gradient(135deg, var(--panel), var(--ok-soft));
}

.problem-card.first-blood {
  border-color: #ef4444;
}

.problem-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.problem-label {
  width: 40px;
  height: 40px;
  background: var(--accent);
  color: #fff;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 20px;
  font-weight: 700;
}

.solved-badge {
  font-size: 24px;
  color: var(--ok);
}

.problem-body h4 {
  margin: 0 0 12px;
  font-size: 16px;
  color: var(--text);
}

.problem-stats {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: var(--muted);
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.problem-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.first-blood {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #ef4444;
  font-weight: 600;
  font-size: 12px;
}

.score {
  font-weight: 700;
  color: var(--accent);
}

/* 排行榜 */
.leaderboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.freeze-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--accent);
  color: #fff;
}

.leaderboard-table {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.leaderboard-table table {
  width: 100%;
  border-collapse: collapse;
}

.leaderboard-table th {
  padding: 12px 8px;
  background: var(--panel-2);
  border-bottom: 2px solid var(--border);
  font-weight: 600;
  font-size: 13px;
  text-align: center;
}

.leaderboard-table td {
  padding: 12px 8px;
  border-bottom: 1px solid var(--border);
  text-align: center;
}

.rank-col { width: 80px; }
.team-col { width: 200px; text-align: left; }
.solved-col { width: 100px; }
.penalty-col { width: 100px; }
.problem-col { width: 80px; }

.rank-1 { background: linear-gradient(90deg, rgba(251, 191, 36, 0.1), transparent); }
.rank-2 { background: linear-gradient(90deg, rgba(203, 213, 225, 0.1), transparent); }
.rank-3 { background: linear-gradient(90deg, rgba(251, 146, 60, 0.1), transparent); }

.rank-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-radius: 8px;
  font-weight: 700;
}

.rank-gold {
  background: linear-gradient(135deg, #fef3c7, #fde68a);
  color: #92400e;
}

.rank-silver {
  background: linear-gradient(135deg, #f1f5f9, #e2e8f0);
  color: #475569;
}

.rank-bronze {
  background: linear-gradient(135deg, #fed7aa, #fdba74);
  color: #7c2d12;
}

.team-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.team-school {
  font-size: 12px;
  color: var(--muted);
}

.solved-count {
  font-size: 18px;
  color: var(--accent);
}

.penalty-time {
  font-variant-numeric: tabular-nums;
  color: var(--muted);
}

.problem-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px;
  border-radius: 6px;
  font-size: 12px;
}

.problem-cell.accepted {
  background: var(--ok-soft);
  color: var(--ok);
}

.problem-cell.failed {
  background: var(--danger-soft);
  color: var(--danger);
}

.problem-cell.pending {
  background: var(--accent-soft);
  color: var(--accent);
}

.problem-cell.empty {
  background: transparent;
}

.cell-time {
  font-weight: 600;
}

.cell-attempts {
  font-size: 10px;
  opacity: 0.8;
}

/* 统计 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  padding: 24px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  text-align: center;
}

.stat-value {
  font-size: 32px;
  font-weight: 700;
  color: var(--accent);
  margin-bottom: 8px;
}

.stat-label {
  font-size: 13px;
  color: var(--muted);
}

@media (max-width: 1024px) {
  .contest-rules {
    grid-template-columns: repeat(2, 1fr);
  }
  
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .contest-header {
    flex-direction: column;
    gap: 16px;
  }
  
  .contest-timer {
    flex-direction: column;
    gap: 16px;
  }
  
  .contest-rules,
  .stats-grid {
    grid-template-columns: 1fr;
  }
}
</style>
