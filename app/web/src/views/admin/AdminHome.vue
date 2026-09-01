<template>
  <div>
    <div class="page-head">
      <h2>管理控制台</h2>
      <p class="muted">维护学期、专业、课程、教学班、师生与授课关系，查看审计事件</p>
    </div>

    <div class="tabs">
      <button v-for="t in tabs" :key="t.key" class="tab" :class="{ active: tab === t.key }" @click="tab = t.key">
        {{ t.label }}
      </button>
    </div>

    <!-- 一键开通向导 -->
    <template v-if="tab === 'wizard'">
      <div class="card section">
        <div class="row section-head">
          <h3>一键开通教学班</h3>
          <span class="muted">依次完成：学期 → 课程 → 教学班 → 主讲教师 → 学生名单，账号自动开通、学生自动选课</span>
        </div>

        <div class="wiz-grid">
          <div class="wiz-block">
            <h4>1. 学期</h4>
            <select v-model="wiz.termMode" class="wide">
              <option v-for="t in terms" :key="t.id" :value="'use:' + t.id">使用：{{ t.name }}</option>
              <option value="new">+ 新建学期</option>
            </select>
            <div v-if="wiz.termMode === 'new'" class="wiz-fields">
              <input v-model="wiz.term.code" placeholder="学期代码 如 2026S1" />
              <input v-model="wiz.term.name" placeholder="学期名称" />
              <div class="row">
                <input v-model="wiz.term.startDate" type="date" />
                <span class="muted">至</span>
                <input v-model="wiz.term.endDate" type="date" />
              </div>
            </div>
          </div>

          <div class="wiz-block">
            <h4>2. 课程</h4>
            <select v-model="wiz.courseMode" class="wide">
              <option v-for="c in courses" :key="c.id" :value="'use:' + c.id">使用：{{ c.name }}（{{ c.code }}）</option>
              <option value="new">+ 新建课程</option>
            </select>
            <div v-if="wiz.courseMode === 'new'" class="wiz-fields">
              <input v-model="wiz.course.code" placeholder="课程代码 如 CS101" />
              <input v-model="wiz.course.name" placeholder="课程名称" />
              <input v-model="wiz.course.credit" type="number" step="0.5" min="0" placeholder="学分（默认 1）" />
            </div>
          </div>

          <div class="wiz-block">
            <h4>3. 教学班</h4>
            <div class="wiz-fields">
              <input v-model="wiz.classCode" placeholder="班级代码 如 CS101-01" />
              <input v-model="wiz.className" placeholder="班级名称 如 01班" />
            </div>
          </div>

          <div class="wiz-block">
            <h4>4. 主讲教师</h4>
            <select v-model="wiz.teacherMode" class="wide">
              <option v-for="t in teachers" :key="t.id" :value="'use:' + t.id">使用：{{ t.name }}（{{ t.staffNo }}）</option>
              <option value="new">+ 新建教师（自动开通登录账号）</option>
            </select>
            <div v-if="wiz.teacherMode === 'new'" class="wiz-fields">
              <div class="row">
                <input v-model="wiz.teacher.staffNo" placeholder="工号 如 T2026002" />
                <input v-model="wiz.teacher.name" placeholder="姓名" />
              </div>
              <div class="row">
                <input v-model="wiz.teacher.login" placeholder="登录账号（默认=工号）" />
                <input v-model="wiz.teacher.password" placeholder="初始密码" />
              </div>
            </div>
          </div>

          <div class="wiz-block wiz-full">
            <h4>5. 学生名单</h4>
            <textarea v-model="wiz.studentsText" rows="6" placeholder="每行一个学生，格式：学号,姓名&#10;20260001,张三&#10;20260002,李四"></textarea>
            <div class="row" style="margin-top: 10px;">
              <label class="chk"><input type="checkbox" v-model="wiz.stuAccount" /> 同时开通学生登录账号</label>
              <input v-if="wiz.stuAccount" v-model="wiz.stuPassword" placeholder="学生初始密码" class="slim" />
            </div>
          </div>
        </div>

        <div class="row" style="margin-top: 16px;">
          <button :disabled="wizRunning" @click="runWizard">{{ wizRunning ? '开通中…' : '一键开通' }}</button>
          <span class="muted">已选 {{ wizardStudentCount }} 名学生</span>
        </div>
        <div v-if="wizLog.length" class="wiz-log">
          <div v-for="(l, i) in wizLog" :key="i" :class="{ bad: l.startsWith('✕') }">{{ l }}</div>
        </div>
      </div>
    </template>

    <!-- 教学组织 -->
    <template v-if="tab === 'org'">
      <div class="card section">
        <div class="row section-head">
          <h3>学期</h3>
          <div class="spacer"></div>
          <input v-model="termForm.code" placeholder="学期代码 如 2026S1" class="slim" />
          <input v-model="termForm.name" placeholder="学期名称" class="slim" />
          <input v-model="termForm.startDate" type="date" class="slim" />
          <input v-model="termForm.endDate" type="date" class="slim" />
          <button @click="createTerm">新增学期</button>
        </div>
        <table>
          <thead><tr><th>代码</th><th>名称</th><th>起止</th><th>状态</th><th></th></tr></thead>
          <tbody>
            <tr v-for="t in terms" :key="t.id">
              <td>{{ t.code }}</td><td>{{ t.name }}</td>
              <td>{{ t.startDate }} ~ {{ t.endDate }}</td>
              <td><span class="chip" :class="t.status === 'ACTIVE' ? 'chip-ok' : 'chip-muted'">{{ t.status === 'ACTIVE' ? '进行中' : '已结课' }}</span></td>
              <td><button v-if="t.status === 'ACTIVE'" class="danger slim-btn" @click="closeTerm(t.id)">结课</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card section">
        <div class="row section-head">
          <h3>专业</h3>
          <div class="spacer"></div>
          <input v-model="majorForm.code" placeholder="专业代码" class="slim" />
          <input v-model="majorForm.name" placeholder="专业名称" class="slim" />
          <button @click="createMajor">新增专业</button>
        </div>
        <table>
          <thead><tr><th>代码</th><th>名称</th></tr></thead>
          <tbody><tr v-for="m in majors" :key="m.id"><td>{{ m.code }}</td><td>{{ m.name }}</td></tr></tbody>
        </table>
      </div>

      <div class="card section">
        <div class="row section-head">
          <h3>课程</h3>
          <div class="spacer"></div>
          <input v-model="courseForm.code" placeholder="课程代码" class="slim" />
          <input v-model="courseForm.name" placeholder="课程名称" class="slim" />
          <input v-model="courseForm.credit" type="number" step="0.5" min="0" placeholder="学分" class="slim tiny" />
          <button @click="createCourse">新增课程</button>
        </div>
        <table>
          <thead><tr><th>代码</th><th>名称</th><th>学分</th></tr></thead>
          <tbody><tr v-for="c in courses" :key="c.id"><td>{{ c.code }}</td><td>{{ c.name }}</td><td>{{ c.credit }}</td></tr></tbody>
        </table>
      </div>

      <div class="card section">
        <div class="row section-head">
          <h3>教学班</h3>
          <div class="spacer"></div>
          <select v-model="classForm.termId" class="slim"><option value="">选择学期</option><option v-for="t in terms" :key="t.id" :value="t.id">{{ t.name }}</option></select>
          <select v-model="classForm.courseId" class="slim"><option value="">选择课程</option><option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option></select>
          <select v-model="classForm.majorId" class="slim"><option value="">不限专业</option><option v-for="m in majors" :key="m.id" :value="m.id">{{ m.name }}</option></select>
          <input v-model="classForm.code" placeholder="班级代码" class="slim" />
          <input v-model="classForm.name" placeholder="班级名称" class="slim" />
          <button @click="createClass">新增教学班</button>
        </div>
        <table>
          <thead><tr><th>代码</th><th>名称</th><th>学期</th><th>课程</th></tr></thead>
          <tbody>
            <tr v-for="c in classes" :key="c.id">
              <td>{{ c.code }}</td><td>{{ c.name }}</td>
              <td>{{ termName(c.termId) }}</td><td>{{ courseName(c.courseId) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 教师与学生 -->
    <template v-if="tab === 'people'">
      <div class="card section">
        <div class="row section-head">
          <h3>教师</h3>
          <div class="spacer"></div>
          <input v-model="teacherForm.staffNo" placeholder="工号" class="slim" />
          <input v-model="teacherForm.name" placeholder="姓名" class="slim" />
          <button @click="createTeacher">新增教师</button>
        </div>
        <table>
          <thead><tr><th>工号</th><th>姓名</th></tr></thead>
          <tbody><tr v-for="t in teachers" :key="t.id"><td>{{ t.staffNo }}</td><td>{{ t.name }}</td></tr></tbody>
        </table>
      </div>

      <div class="card section">
        <div class="row section-head">
          <h3>学生</h3>
          <div class="spacer"></div>
          <input v-model="studentKeyword" placeholder="按学号/姓名搜索" class="slim" @input="searchStudents" />
          <input v-model="studentForm.studentNo" placeholder="学号" class="slim" />
          <input v-model="studentForm.name" placeholder="姓名" class="slim" />
          <button @click="createStudent">新增学生</button>
        </div>
        <table>
          <thead><tr><th>学号</th><th>姓名</th></tr></thead>
          <tbody><tr v-for="s in students" :key="s.id"><td>{{ s.studentNo }}</td><td>{{ s.name }}</td></tr></tbody>
        </table>
      </div>
    </template>

    <!-- 授课与选课 -->
    <template v-if="tab === 'assign'">
      <div class="card section">
        <div class="row section-head">
          <h3>授课关系与选课归属</h3>
          <div class="spacer"></div>
          <select v-model="selectedClassId" class="slim">
            <option value="">选择教学班</option>
            <option v-for="c in classes" :key="c.id" :value="c.id">{{ c.name }}（{{ c.code }}）</option>
          </select>
        </div>

        <template v-if="selectedClassId">
          <div class="assign-block">
            <div class="row">
              <select v-model="assignTeacherId" class="slim">
                <option value="">选择教师</option>
                <option v-for="t in teachers" :key="t.id" :value="t.id">{{ t.name }}（{{ t.staffNo }}）</option>
              </select>
              <select v-model="assignRole" class="slim tiny">
                <option value="PRIMARY">主讲</option>
                <option value="ASSISTANT">助教</option>
              </select>
              <button @click="assignTeacher">分配授课</button>
            </div>
            <table>
              <thead><tr><th>教师</th><th>角色</th><th></th></tr></thead>
              <tbody>
                <tr v-for="ta in teacherAssignments" :key="ta.id">
                  <td>{{ teacherName(ta.teacherId) }}</td>
                  <td><span class="chip" :class="ta.role === 'PRIMARY' ? 'chip-primary' : 'chip-muted'">{{ ta.role === 'PRIMARY' ? '主讲' : '助教' }}</span></td>
                  <td><button class="danger slim-btn" @click="removeAssignment(ta.id)">撤销</button></td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="assign-block">
            <div class="row">
              <select v-model="enrollStudentId" class="slim">
                <option value="">选择学生</option>
                <option v-for="s in students" :key="s.id" :value="s.id">{{ s.name }}（{{ s.studentNo }}）</option>
              </select>
              <label class="chk"><input type="checkbox" v-model="enrollTransfer" /> 转班（自动结束原归属）</label>
              <button @click="enrollStudent">加入班级</button>
            </div>
            <table>
              <thead><tr><th>学生</th><th>状态</th><th></th></tr></thead>
              <tbody>
                <tr v-for="e in enrollments" :key="e.id">
                  <td>{{ studentName(e.studentId) }}</td>
                  <td><span class="chip" :class="!e.endedAt ? 'chip-ok' : 'chip-muted'">{{ !e.endedAt ? '在读' : '已移出' }}</span></td>
                  <td><button v-if="!e.endedAt" class="danger slim-btn" @click="endEnrollment(e.id)">移出</button></td>
                </tr>
              </tbody>
        </table>
      </div>
    </template>

    <!-- 账号管理（本地合成账号，可多管理员） -->
    <template v-if="tab === 'accounts'">
      <div class="card section">
        <div class="row section-head">
          <h3>本地账号</h3>
          <p class="muted" style="margin: 0;">支持多个管理员；角色任意切换，停用后立即禁止登录。主管理员（bootstrap）启动时自动恢复启用、不可移除</p>
        </div>
        <div class="row" style="margin-bottom: 12px;">
          <select v-model="acctForm.role" class="slim">
            <option value="ADMIN">管理员</option>
            <option value="TEACHER">教师</option>
            <option value="STUDENT">学生</option>
          </select>
          <input v-model="acctForm.loginName" placeholder="登录名" class="slim" />
          <input v-model="acctForm.password" placeholder="初始密码（至少 8 位）" type="password" class="slim" />
          <button @click="createAccount">创建账号</button>
        </div>
        <table>
          <thead><tr><th>登录名</th><th>角色</th><th>关联</th><th>状态</th><th>创建时间</th><th></th></tr></thead>
          <tbody>
            <tr v-for="a in accounts" :key="a.id">
              <td>{{ a.loginName }}</td>
              <td><span class="chip" :class="a.role === 'ADMIN' ? 'chip-primary' : 'chip-muted'">{{ a.role }}</span></td>
              <td>{{ a.role === 'TEACHER' ? 'teacher#' + a.teacherId : a.role === 'STUDENT' ? 'student#' + a.studentId : '—' }}</td>
              <td><span class="chip" :class="a.status === 'ACTIVE' ? 'chip-ok' : 'chip-muted'">{{ a.status }}</span></td>
              <td>{{ fmtTime(a.createdAt) }}</td>
              <td><button v-if="a.status === 'ACTIVE'" class="danger slim-btn" @click="disableAccount(a.id)">停用</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
        <div v-else class="empty">请先选择教学班</div>
      </div>
    </template>

    <!-- 审计事件 -->
    <template v-if="tab === 'audit'">
      <div class="card section">
        <div class="row section-head">
          <h3>审计事件</h3>
          <div class="spacer"></div>
          <input v-model="auditLimit" type="number" min="1" max="200" class="slim tiny" @change="loadAudit" />
          <span class="muted">条</span>
          <button class="secondary" @click="loadAudit">刷新</button>
        </div>
        <table>
          <thead><tr><th>时间</th><th>操作者</th><th>动作</th><th>对象</th></tr></thead>
          <tbody>
            <tr v-for="e in audits" :key="e.id">
              <td>{{ fmtTime(e.createdAt) }}</td>
              <td>{{ e.actorType }}:{{ e.actorId }}</td>
              <td><span class="chip chip-muted">{{ e.action }}</span></td>
              <td>{{ e.targetType }}:{{ e.targetId }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="audits.length === 0" class="empty">暂无审计事件</div>
      </div>
    </template>

    <div v-if="message" class="msg" :class="{ ok: msgOk, bad: !msgOk }">{{ message }}</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api'

const tabs = [
  { key: 'wizard', label: '一键开通' },
  { key: 'org', label: '教学组织' },
  { key: 'people', label: '教师与学生' },
  { key: 'assign', label: '授课与选课' },
  { key: 'accounts', label: '账号管理' },
  { key: 'audit', label: '审计事件' }
]
const tab = ref('org')

const terms = ref([])
const majors = ref([])
const courses = ref([])
const classes = ref([])
const teachers = ref([])
const students = ref([])
const teacherAssignments = ref([])
const enrollments = ref([])
const accounts = ref([])
const audits = ref([])
const message = ref('')
const msgOk = ref(true)

const termForm = ref({ code: '', name: '', startDate: '', endDate: '' })
const majorForm = ref({ code: '', name: '' })
const courseForm = ref({ code: '', name: '', credit: '' })
const classForm = ref({ termId: '', courseId: '', majorId: '', code: '', name: '' })
const teacherForm = ref({ staffNo: '', name: '' })
const studentForm = ref({ studentNo: '', name: '' })
const acctForm = ref({ role: 'ADMIN', loginName: '', password: '' })
const studentKeyword = ref('')
const selectedClassId = ref('')
const assignTeacherId = ref('')
const assignRole = ref('PRIMARY')
const enrollStudentId = ref('')
const enrollTransfer = ref(false)
const auditLimit = ref(50)

const defaultTermStart = new Date().toISOString().slice(0, 10)
const defaultTermEnd = new Date(Date.now() + 120 * 86400000).toISOString().slice(0, 10)
const wiz = ref({
  termMode: 'new',
  term: { code: '', name: '', startDate: defaultTermStart, endDate: defaultTermEnd },
  courseMode: 'new',
  course: { code: '', name: '', credit: '' },
  classCode: '',
  className: '',
  teacherMode: 'new',
  teacher: { staffNo: '', name: '', login: '', password: 'Teacher@123456' },
  studentsText: '',
  stuAccount: true,
  stuPassword: 'Student@123456'
})
const wizRunning = ref(false)
const wizLog = ref([])
const wizardStudentCount = computed(() =>
  wiz.value.studentsText.split('\n').map(s => s.trim()).filter(Boolean).length
)

onMounted(async () => {
  await Promise.all([loadTerms(), loadMajors(), loadCourses(), loadClasses(), loadTeachers(), loadStudents(), loadAccounts(), loadAudit()])
})

function notify(text, ok = true) {
  message.value = text
  msgOk.value = ok
  setTimeout(() => { if (message.value === text) message.value = '' }, 4000)
}

async function run(action) {
  try {
    await action()
    notify('操作成功')
  } catch (e) {
    notify(e.message, false)
  }
}

async function loadTerms() { terms.value = await api('/admin/terms') }
async function loadMajors() { majors.value = await api('/admin/majors') }
async function loadCourses() { courses.value = await api('/admin/courses') }
async function loadClasses() { classes.value = await api('/admin/teaching-classes') }
async function loadTeachers() { teachers.value = await api('/admin/teachers') }
async function loadStudents() { students.value = await api('/admin/students' + (studentKeyword.value ? `?keyword=${encodeURIComponent(studentKeyword.value)}` : '')) }
async function loadAccounts() { accounts.value = await api('/admin/accounts') }
async function loadAudit() { audits.value = await api(`/admin/audit-events?limit=${auditLimit.value || 50}`) }

async function loadAssignments() {
  if (!selectedClassId.value) return
  const id = selectedClassId.value
  const [ta, en] = await Promise.all([
    api(`/admin/teacher-assignments?teachingClassId=${id}`),
    api(`/admin/enrollments?teachingClassId=${id}`)
  ])
  teacherAssignments.value = ta
  enrollments.value = en
}

function searchStudents() {
  clearTimeout(searchStudents._t)
  searchStudents._t = setTimeout(() => { run(loadStudents) }, 300)
}

const createTerm = () => run(async () => {
  await api('/admin/terms', { method: 'POST', body: termForm.value })
  termForm.value = { code: '', name: '', startDate: '', endDate: '' }
  await loadTerms()
})
const closeTerm = (id) => run(async () => {
  await api(`/admin/terms/${id}/close`, { method: 'POST' })
  await loadTerms()
})
const createMajor = () => run(async () => {
  await api('/admin/majors', { method: 'POST', body: majorForm.value })
  majorForm.value = { code: '', name: '' }
  await loadMajors()
})
const createCourse = () => run(async () => {
  await api('/admin/courses', { method: 'POST', body: { code: courseForm.value.code, name: courseForm.value.name, credit: courseForm.value.credit || undefined } })
  courseForm.value = { code: '', name: '', credit: '' }
  await loadCourses()
})
const createClass = () => run(async () => {
  await api('/admin/teaching-classes', { method: 'POST', body: { ...classForm.value, termId: num(classForm.value.termId), courseId: num(classForm.value.courseId), majorId: num(classForm.value.majorId) } })
  classForm.value = { termId: '', courseId: '', majorId: '', code: '', name: '' }
  await loadClasses()
})
const createTeacher = () => run(async () => {
  await api('/admin/teachers', { method: 'POST', body: teacherForm.value })
  teacherForm.value = { staffNo: '', name: '' }
  await loadTeachers()
})
const createStudent = () => run(async () => {
  await api('/admin/students', { method: 'POST', body: studentForm.value })
  studentForm.value = { studentNo: '', name: '' }
  await loadStudents()
})
const createAccount = () => run(async () => {
  await api('/admin/dev-accounts', { method: 'POST', body: acctForm.value })
  acctForm.value = { role: 'ADMIN', loginName: '', password: '' }
  await loadAccounts()
})
const disableAccount = (id) => run(async () => {
  await api(`/admin/accounts/${id}/disable`, { method: 'POST' })
  await loadAccounts()
})

const assignTeacher = () => run(async () => {
  await api('/admin/teacher-assignments', { method: 'POST', body: { teachingClassId: num(selectedClassId.value), teacherId: num(assignTeacherId.value), role: assignRole.value } })
  assignTeacherId.value = ''
  await loadAssignments()
})
const removeAssignment = (id) => run(async () => {
  await api(`/admin/teacher-assignments/${id}`, { method: 'DELETE' })
  await loadAssignments()
})
const enrollStudent = () => run(async () => {
  await api('/admin/enrollments', { method: 'POST', body: { teachingClassId: num(selectedClassId.value), studentId: num(enrollStudentId.value), transfer: enrollTransfer.value } })
  enrollStudentId.value = ''
  await loadAssignments()
})
const endEnrollment = (id) => run(async () => {
  await api(`/admin/enrollments/${id}`, { method: 'DELETE' })
  await loadAssignments()
})

function num(v) { return v === '' || v === null ? null : Number(v) }
function termName(id) { return terms.value.find(t => t.id === id)?.name || id }
function courseName(id) { return courses.value.find(c => c.id === id)?.name || id }
function teacherName(id) { return teachers.value.find(t => t.id === id)?.name || id }
function studentName(id) { return students.value.find(s => s.id === id)?.name || id }
function fmtTime(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '' }
</script>

<style scoped>
.tabs { display: flex; gap: 8px; margin-bottom: 18px; flex-wrap: wrap; }
.tab {
  padding: 8px 18px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--muted);
  font-weight: 600;
}
.tab.active { color: var(--accent); background: var(--accent-soft); border-color: #c7d9ff; }
.section { padding: 18px 20px; margin-bottom: 18px; }
.section-head { margin-bottom: 14px; }
.section-head h3 { margin: 0; font-size: 16px; }
.slim { width: auto; }
.tiny { width: 90px; }
.slim-btn { padding: 5px 12px; font-size: 13px; }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { text-align: left; padding: 9px 10px; border-bottom: 1px solid var(--border); }
th { color: var(--muted); font-weight: 600; font-size: 13px; }
tr:last-child td { border-bottom: none; }
.assign-block { margin-bottom: 24px; }
.assign-block:last-child { margin-bottom: 0; }
.chk { display: flex; align-items: center; gap: 6px; font-size: 13.5px; color: var(--muted); }
.wiz-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px 24px;
}
.wiz-block {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px 16px;
  background: var(--panel-2);
}
.wiz-block h4 { margin: 0 0 10px; font-size: 14px; color: var(--accent); }
.wiz-full { grid-column: 1 / -1; }
.wiz-fields { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.wiz-fields input { width: 100%; }
.wide { width: 100%; }
.wiz-block textarea { width: 100%; resize: vertical; font-family: inherit; }
.wiz-log {
  margin-top: 14px;
  background: #0f172a;
  color: #d7e3f4;
  border-radius: 10px;
  padding: 12px 16px;
  font-size: 13px;
  font-family: Consolas, monospace;
  max-height: 220px;
  overflow-y: auto;
}
.wiz-log .bad { color: #fca5a5; }
.msg {
  position: fixed;
  bottom: 26px;
  left: 50%;
  transform: translateX(-50%);
  padding: 10px 20px;
  border-radius: 10px;
  font-size: 14px;
  box-shadow: var(--shadow-lg);
  z-index: 100;
}
.msg.ok { background: var(--ok-soft); color: var(--ok); border: 1px solid #bbe7c9; }
.msg.bad { background: var(--danger-soft); color: var(--danger); border: 1px solid #fecaca; }
</style>
