import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
import { getRole, refreshRole } from './auth'
import { getToken } from './api'

// 除登录页外均按需加载：显著减小首屏包体，加快登录页可达时间
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

const TEACHER_ROLES = ['TEACHER', 'ADMIN']

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: Login },
    { path: '/admin', component: AdminHome, meta: { area: 'admin' } },
    { path: '/teacher/classes', component: TeacherClasses, meta: { area: 'teacher' } },
    { path: '/teacher/classes/:classId/problems', component: TeacherProblemBank, meta: { area: 'teacher' } },
    { path: '/teacher/assignment', component: TeacherAssignmentEditor, meta: { area: 'teacher' } },
    { path: '/teacher/assignments', component: TeacherAssignmentsManage, meta: { area: 'teacher' } },
    { path: '/teacher/analytics/:targetId', component: TeacherAnalyticsEnhanced, meta: { area: 'teacher' } },
    { path: '/teacher/analytics/:targetId/classic', component: TeacherAnalytics, meta: { area: 'teacher' } },
    { path: '/student', component: StudentHome, meta: { area: 'student' } },
    { path: '/student/practice', component: StudentPractice, meta: { area: 'student' } },
    { path: '/student/targets/:targetId', component: StudentAssignment, meta: { area: 'student' } },
    { path: '/student/contest/:contestId', component: ContestMode, props: route => ({ contestId: Number(route.params.contestId) }), meta: { area: 'student' } }
  ],
  /**
   * 路由切换后回到页面顶部。
   * - 浏览器前进/后退时恢复历史滚动位置
   * - 同一路径（仅参数变化）不打断用户当前滚动
   * - 尊重系统"减弱动效"偏好，关闭平滑滚动
   */
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.path === from.path) return false
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    return { top: 0, behavior: reduce ? 'auto' : 'smooth' }
  }
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

export default router
