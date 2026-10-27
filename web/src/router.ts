import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuth } from './composables/useAuth'
import LoginView from './components/LoginView.vue'
import RegisterView from './components/RegisterView.vue'
import ProfileView from './components/ProfileView.vue'
import ResetPasswordView from './components/ResetPasswordView.vue'
import AdminUsersView from './components/AdminUsersView.vue'
import AuditView from './components/AuditView.vue'
import HomeView from './components/HomeView.vue'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView, meta: { workspace: true } },
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/register', name: 'register', component: RegisterView, meta: { public: true } },
    { path: '/reset-password', name: 'reset', component: ResetPasswordView, meta: { public: true } },
    { path: '/profile', name: 'profile', component: ProfileView },
    { path: '/change-password', name: 'change-password', component: ProfileView, props: { forced: true } },
    { path: '/admin/users', name: 'admin-users', component: AdminUsersView, meta: { admin: true } },
    { path: '/admin/audit', name: 'audit', component: AuditView, meta: { admin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(async to => {
  const auth = useAuth()
  await auth.initialize()
  if (auth.phase.value === 'unavailable') return true
  const user = auth.user.value
  if (to.name === 'reset') return true
  if (!user) return to.meta.public ? true : { name: 'login' }
  if (user.passwordChangeRequired) return to.name === 'change-password' ? true : { name: 'change-password' }
  if (to.meta.public || to.name === 'change-password') return { name: user.status === 'PENDING' ? 'profile' : 'home' }
  if (to.meta.admin && !auth.canAdmin.value) return { name: 'profile' }
  if (to.meta.workspace && !auth.canUseWorkspace.value) return { name: 'profile' }
  return true
})
