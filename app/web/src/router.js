import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
import TeacherClasses from './views/teacher/Classes.vue'
import TeacherProblemBank from './views/teacher/ProblemBank.vue'
import TeacherAssignmentEditor from './views/teacher/AssignmentEditor.vue'
import TeacherAnalytics from './views/teacher/Analytics.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: Login },
    { path: '/teacher/classes', component: TeacherClasses },
    { path: '/teacher/classes/:classId/problems', component: TeacherProblemBank },
    { path: '/teacher/assignment', component: TeacherAssignmentEditor },
    { path: '/teacher/analytics/:targetId', component: TeacherAnalytics }
  ]
})

export default router
