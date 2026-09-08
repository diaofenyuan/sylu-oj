<template>
  <div>
    <div class="page-head manage-head"><div><h2>作业管理</h2><p class="muted">调整作业安排，查看各班成绩与完成情况。</p></div><router-link class="button-link" to="/teacher/assignment"><Icon icon="mdi:plus" aria-hidden="true" />布置作业</router-link></div>
    <div class="page-toolbar">
      <label class="search-field"><span class="sr-only">搜索已发布作业</span><Icon icon="mdi:magnify" aria-hidden="true" /><input v-model.trim="keyword" type="search" placeholder="搜索作业名称" /></label>
      <select v-model="modeFilter" aria-label="筛选作业类型"><option value="ALL">全部类型</option><option value="HOMEWORK">普通作业</option><option value="EXAM">正式考试</option></select>
    </div>
    <p v-if="loading" role="status" class="muted">正在加载作业安排…</p>
    <p class="manage-note"><Icon icon="mdi:information-outline" aria-hidden="true" />正式考试发布后会锁定，修改需经双人审批。</p>

    <div class="card" v-for="a in filteredItems" :key="a.id">
      <div class="row section-row">
        <h3>#{{ a.id }} {{ a.title }}</h3>
        <span class="chip" :class="a.mode === 'EXAM' ? 'chip-warn' : 'chip-primary'">
          {{ a.mode === 'EXAM' ? '正式考试' : '普通作业' }}
        </span>
        <span class="chip" :class="statusClass(a.status)">{{ statusLabel(a.status) }}</span>
        <div class="spacer"></div>
      </div>

      <div v-if="a.targets.length" class="table-scroll"><table>
        <thead>
          <tr>
            <th>班级</th><th>发布时间</th><th>截止时间</th><th>窗口</th>
            <th style="width: 90px;">最多提交</th><th style="min-width: 260px;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in a.targets" :key="t.id">
            <td>{{ className(t.teachingClassId) }}</td>
            <td><input v-model="t.publishAt" :aria-label="a.title + ' ' + className(t.teachingClassId) + '发布时间'" type="datetime-local" /></td>
            <td><input v-model="t.deadline" :aria-label="a.title + ' ' + className(t.teachingClassId) + '截止时间'" type="datetime-local" /></td>
            <td><span class="chip" :class="winClass(t.window)">{{ winLabel(t.window) }}</span></td>
            <td><input v-model.number="t.maxSubmissions" :aria-label="a.title + ' ' + className(t.teachingClassId) + '最大提交次数'" type="number" min="1" class="slim" /></td>
            <td>
              <div class="row-op">
                <router-link class="grade-link" :to="`/teacher/analytics/${t.id}`">查看成绩</router-link>
                <button class="mini green" :disabled="saving" @click="saveRules(a, t)">保存</button>
                <button class="mini warn" :disabled="saving" @click="collect(a, t)">收卷</button>
                <button class="mini" :disabled="saving" @click="withdraw(a, t)">
                  {{ t.status === 'WITHDRAWN' ? '恢复' : '撤回' }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table></div>
      <div v-else class="empty">尚未发布到任何班级</div>

      <div v-if="a.mode === 'EXAM' && a.approvals && a.approvals.length" class="approvals">
        <h4>考试修改审批（锁定后修改需双人审批）</h4>
        <table>
          <thead><tr><th>#</th><th>动作</th><th>状态</th><th>理由</th><th style="width: 140px;">操作</th></tr></thead>
          <tbody>
            <tr v-for="ap in a.approvals" :key="ap.id">
              <td class="mono">{{ ap.id }}</td>
              <td>{{ actionLabel(ap.action) }}</td>
              <td><span class="chip" :class="approvalClass(ap.status)">{{ approvalLabel(ap.status) }}</span></td>
              <td class="muted">{{ ap.reason || '—' }}</td>
              <td>
                <template v-if="ap.status === 'PENDING'">
                  <button class="mini green" @click="decide(a, ap, true)">同意</button>
                  <button class="mini warn" @click="decide(a, ap, false)">拒绝</button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="muted">批准人不得与请求人同一账号，且须已完成 TOTP 双因子绑定；审批通过后第一位教师重试修改即可生效</p>
      </div>
    </div>

    <div v-if="!loading && !errMsg && !filteredItems.length" class="empty"><h3>{{ items.length ? '没有匹配的作业' : '还没有发布作业' }}</h3><p>{{ items.length ? '调整搜索关键词或作业类型后重试。' : '点击「布置作业」，从题库选择题目并发布到班级。' }}</p></div>
    <div v-if="msg" role="status" class="ok-banner">{{ msg }}</div>
    <div v-if="errMsg" role="alert" class="err-banner">
      {{ errMsg }}
      <template v-if="pendingApproval">
        <button class="secondary slim-btn" @click="requestApproval(pendingApproval)">发起修改审批</button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api'

const items = ref([])
const keyword = ref('')
const modeFilter = ref('ALL')
const filteredItems = computed(() => items.value.filter(a => a.title.toLowerCase().includes(keyword.value.toLowerCase()) && (modeFilter.value === 'ALL' || a.mode === modeFilter.value)))
const classes = ref([])
const loading = ref(true)
const saving = ref(false)
const msg = ref('')
const errMsg = ref('')
const pendingApproval = ref(null)

const ACTION_LABELS = {
  CHANGE_TARGET_RULES: '修改时间窗口/规则',
  PUBLISH: '再次发布',
  WITHDRAW: '撤回/恢复',
  LOCK: '锁定',
  CHANGE_GRADE: '成绩修订'
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [cls, list] = await Promise.all([api('/teacher/classes'), api('/teacher/assignments')])
    classes.value = cls
    items.value = list.map(a => {
      const item = { ...a, approvals: null, targets: a.targets || [] }
      for (const t of item.targets) {
        t.publishAt = sliceTime(t.publishAt)
        t.deadline = sliceTime(t.deadline)
      }
      return item
    })
    for (const a of items.value) {
      if (a.mode === 'EXAM') {
        try {
          a.approvals = await api(`/teacher/exams/${a.id}/approvals`)
        } catch { a.approvals = null }
      }
    }
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}

async function saveRules(a, t) {
  saving.value = true
  try {
    await api(`/teacher/assignments/${a.id}/targets/${t.teachingClassId}/rules`, {
      method: 'PUT',
      body: {
        publishAt: t.publishAt ? normTime(t.publishAt) : null,
        deadline: t.deadline ? normTime(t.deadline) : null,
        maxSubmissions: Number(t.maxSubmissions) || 0,
        scoringRules: t.scoringRules || null
      }
    })
    pendingApproval.value = null
    notify(`已保存 #${a.id} ${className(t.teachingClassId)} 的规则`)
    await load()
  } catch (e) {
    errMsg.value = e.message
    if (isExamLocked(e) && a.mode === 'EXAM') {
      pendingApproval.value = a
    }
  } finally {
    saving.value = false
  }
}

async function collect(a, t) {
  saving.value = true
  try {
    await api(`/teacher/assignments/${a.id}/targets/${t.teachingClassId}/collect`, { method: 'POST' })
    pendingApproval.value = null
    notify(`已收卷 #${a.id} ${className(t.teachingClassId)}`)
    await load()
  } catch (e) {
    errMsg.value = e.message
    if (isExamLocked(e) && a.mode === 'EXAM') pendingApproval.value = a
  } finally {
    saving.value = false
  }
}

async function withdraw(a, t) {
  saving.value = true
  try {
    const action = t.status === 'WITHDRAWN' ? 'republish' : 'withdraw'
    await api(`/teacher/assignments/${a.id}/targets/${t.teachingClassId}/${action}`, { method: 'POST' })
    pendingApproval.value = null
    notify(`${action === 'withdraw' ? '已撤回' : '已恢复'}：${className(t.teachingClassId)}`)
    await load()
  } catch (e) {
    errMsg.value = e.message
    if (isExamLocked(e) && a.mode === 'EXAM') pendingApproval.value = a
  } finally {
    saving.value = false
  }
}

async function requestApproval(a) {
  if (!a) return
  try {
    await api(`/teacher/exams/${a.id}/changes`, {
      method: 'POST',
      body: { action: 'CHANGE_TARGET_RULES', reason: '修改时间窗口/立即收卷/撤回' }
    })
    notify('审批已提交，等待第二位教师/管理员批准')
    pendingApproval.value = null
    await load()
  } catch (e) {
    fail(e)
  }
}

async function decide(a, ap, approve) {
  try {
    await api(`/teacher/exams/approvals/${ap.id}/${approve ? 'approve' : 'reject'}`, {
      method: 'POST',
      body: { reason: approve ? '批准修改' : '拒绝修改' }
    })
    notify(`审批 #${ap.id} ${approve ? '已批准' : '已拒绝'}`)
    await load()
  } catch (e) {
    fail(e)
  }
}

function isExamLocked(e) {
  return /锁定/.test(e.message) || /EXAM_LOCKED/.test(e.message || '')
}

function className(id) {
  const c = classes.value.find(x => x.teachingClassId === id)
  return c ? `${c.name}（${c.code}）` : '班级 #' + id
}

function statusLabel(s) {
  return ({ DRAFT: '草稿', PUBLISHED: '已发布', WITHDRAWN: '已撤回' })[s] || s
}
function statusClass(s) {
  return ({ DRAFT: 'chip-muted', PUBLISHED: 'chip-ok', WITHDRAWN: 'chip-muted' })[s] || 'chip-muted'
}
function winLabel(w) {
  return ({ NOT_STARTED: '未开始', OPEN: '进行中', CLOSED: '已截止' })[w] || w
}
function winClass(w) {
  return ({ NOT_STARTED: 'chip-warn', OPEN: 'chip-ok', CLOSED: 'chip-muted' })[w] || 'chip-muted'
}
function actionLabel(a) { return ACTION_LABELS[a] || a }
function approvalLabel(s) {
  return ({ PENDING: '待审批', APPROVED: '已批准', REJECTED: '已拒绝', CANCELLED: '已取消' })[s] || s
}
function approvalClass(s) {
  return ({ PENDING: 'chip-warn', APPROVED: 'chip-ok', REJECTED: 'chip-bad', CANCELLED: 'chip-muted' })[s] || 'chip-muted'
}
function normTime(v) { return v && v.length === 16 ? v + ':00' : (v || null) }
function sliceTime(v) { return v ? String(v).slice(0, 16) : '' }

function notify(text) {
  msg.value = text
  errMsg.value = ''
  setTimeout(() => { if (msg.value === text) msg.value = '' }, 5000)
}
function fail(e) {
  errMsg.value = e.message
  msg.value = ''
}
</script>

<style scoped>
.section-row { margin-bottom: 12px; }
.section-row h3 { margin: 0; }
.slim-btn { padding: 5px 12px; font-size: 13px; }
.row-op { display: flex; gap: 6px; }
.mini {
  background: var(--panel);
  color: var(--text);
  border: 1px solid var(--border);
  box-shadow: none;
  padding: 4px 10px;
  font-size: 12px;
  border-radius: 7px;
}
.mini:hover:not(:disabled) { box-shadow: none; background: var(--panel-2); }
.mini.green { color: var(--ok); border-color: #bbe7d4; }
.mini.green:hover:not(:disabled) { background: var(--ok-soft); }
.mini.warn { color: var(--danger); border-color: #fecaca; }
.mini.warn:hover:not(:disabled) { background: var(--danger-soft); }
.approvals { margin-top: 14px; border-top: 1px solid var(--border); padding-top: 10px; }
.approvals h4 { margin: 4px 0 10px; font-size: 14px; }
.approvals table { margin-bottom: 8px; }
.mono { font-family: Consolas, monospace; }
.ok-banner {
  background: var(--ok-soft);
  color: var(--ok);
  border: 1px solid #bbe7d4;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  margin-top: 4px;
}
.err-banner {
  background: var(--danger-soft);
  color: var(--danger);
  border: 1px solid #fecaca;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.manage-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 20px; }
.manage-note { display: flex; align-items: flex-start; gap: 7px; margin: 0 0 24px; color: var(--muted); font-size: 12px; }
.manage-note svg { width: 16px; height: 16px; flex-shrink: 0; margin-top: 2px; }
.row-op { align-items: center; white-space: nowrap; }
.grade-link { padding: 8px 4px; font-size: 13px; margin-right: 6px; }
.table-scroll table { margin-bottom: 0; }
</style>
