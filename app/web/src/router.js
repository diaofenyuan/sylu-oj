import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
const AdminHome = () => import('./views/admin/AdminHome.vue')
const TeacherClasses = () => import('./views/teacher/Classes.vue')
const TeacherProblemBank = () => import('./views/teacher/ProblemBank.vue')
const TeacherAssignmentEditor = () => import('./views/teacher/AssignmentEditor.vue')
const TeacherAssignmentsManage = () => import('./views/teacher/AssignmentsManage.vue')
const TeacherAnalytics = () => import('./views/teacher/Analytics.vue')
const TeacherAnalyticsEnhanced = () => import('./views/teacher/AnalyticsEnhanced.vue')
const StudentHome = () => import('./views/student/StudentHome.vue')
const StudentAssignment = () => import('./views/student/StudentAssignment.vue')
const StudentPractice = () => import('./views/student/StudentPractice.vue')
const ContestMode = () => import('./components/ContestMode.vue')
import { getRole, refreshRole } from './auth'
import { getToken } from './api'

const TEACHER_ROLES = ['TEACHER', 'ADMIN']

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: Login, meta: { title: '登录' } },
    { path: '/admin', component: AdminHome, meta: { area: 'admin', title: '管理控制台' } },
    { path: '/teacher/classes', component: TeacherClasses, meta: { area: 'teacher', title: '授课班级' } },
    { path: '/teacher/classes/:classId/problems', component: TeacherProblemBank, meta: { area: 'teacher', title: '班级题库' } },
    { path: '/teacher/assignment', component: TeacherAssignmentEditor, meta: { area: 'teacher', title: '组卷发布' } },
    { path: '/teacher/assignments', component: TeacherAssignmentsManage, meta: { area: 'teacher', title: '作业管理' } },
    { path: '/teacher/analytics/:targetId', component: TeacherAnalyticsEnhanced, meta: { area: 'teacher', title: '成绩分析' } },
    { path: '/teacher/analytics/:targetId/classic', component: TeacherAnalytics, meta: { area: 'teacher', title: '成绩分析' } },
    { path: '/student', component: StudentHome, meta: { area: 'student', title: '我的作业' } },
    { path: '/student/practice', component: StudentPractice, meta: { area: 'student', title: '刷题中心' } },
    { path: '/student/targets/:targetId', component: StudentAssignment, meta: { area: 'student', title: '作业工作区' } },
    { path: '/student/contest/:contestId', component: ContestMode, props: route => ({ contestId: Number(route.params.contestId) }), meta: { area: 'student', title: '比赛模式' } }
  ]
})

async function resolveRole() {
  let role = getRole()
  if (!role) role = await refreshRole()
  return role
}

router.beforeEach(async (to) => {
  if (!to.meta.area) return true
  if (!getToken()) return '/login'
  let role
  try {
    role = await resolveRole()
  } catch {
    return '/login'
  }
  if (to.meta.area === 'admin' && role !== 'ADMIN') {
    return role === 'STUDENT' ? '/student' : '/teacher/classes'
  }
  if (to.meta.area === 'teacher' && !TEACHER_ROLES.includes(role)) return '/student'
  if (to.meta.area === 'student' && role !== 'STUDENT') {
    return role === 'ADMIN' ? '/admin' : '/teacher/classes'
  }
  return true
})

// 页面按需加载，登录时不必下载编辑器与管理后台；标题帮助用户定位当前任务。
router.afterEach((to, from, failure) => {
  if (!failure) document.title = (to.meta.title || '在线判题系统') + ' · SYLU-OJ'
})

export default router
