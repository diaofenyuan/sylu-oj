import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
import TeacherClasses from './views/teacher/Classes.vue'
import TeacherProblemBank from './views/teacher/ProblemBank.vue'
import TeacherAssignmentEditor from './views/teacher/AssignmentEditor.vue'
import TeacherAnalytics from './views/teacher/Analytics.vue'
import StudentHome from './views/student/StudentHome.vue'
import StudentAssignment from './views/student/StudentAssignment.vue'
import { getRole, refreshRole } from './auth'
import { getToken } from './api'

const TEACHER_ROLES = ['TEACHER', 'ADMIN']

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: Login },
    { path: '/teacher/classes', component: TeacherClasses, meta: { area: 'teacher' } },
    { path: '/teacher/classes/:classId/problems', component: TeacherProblemBank, meta: { area: 'teacher' } },
    { path: '/teacher/assignment', component: TeacherAssignmentEditor, meta: { area: 'teacher' } },
    { path: '/teacher/analytics/:targetId', component: TeacherAnalytics, meta: { area: 'teacher' } },
    { path: '/student', component: StudentHome, meta: { area: 'student' } },
    { path: '/student/targets/:targetId', component: StudentAssignment, meta: { area: 'student' } }
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
  if (to.meta.area === 'teacher' && !TEACHER_ROLES.includes(role)) return '/student'
  if (to.meta.area === 'student' && role !== 'STUDENT') return '/teacher/classes'
  return true
})

export default router
