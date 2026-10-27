<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from './composables/useAuth'
import { errorMessage } from './lib/api'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const drawer = ref(false)
const exitBusy = ref(false)
const error = ref('')
const titles: Record<string, string> = { home: 'Моё пространство', profile: 'Личный кабинет', 'change-password': 'Смена пароля', 'admin-users': 'Пользователи', audit: 'Журнал действий' }
const title = computed(() => titles[String(route.name)] || 'Дом рядом')
const initials = computed(() => auth.user.value?.nickname.split(' ').filter(Boolean).map(x => x[0]).slice(0, 2).join('').toUpperCase() || 'Д')
const nav = computed(() => [
  ...(auth.canUseWorkspace.value ? [{ to: '/', label: 'Моё пространство', icon: 'mdi-home-outline' }] : []),
  { to: '/profile', label: 'Личный кабинет', icon: 'mdi-account-circle-outline' },
  ...(auth.canAdmin.value ? [
    { to: '/admin/users', label: 'Пользователи', icon: 'mdi-account-group-outline' },
    { to: '/admin/audit', label: 'Журнал действий', icon: 'mdi-history' },
  ] : []),
])
async function logout() {
  exitBusy.value = true; error.value = ''
  try { await auth.logout(); await router.replace('/login') }
  catch (e) { error.value = errorMessage(e) }
  finally { exitBusy.value = false }
}
async function retry() { await auth.initialize(true); if (auth.phase.value !== 'unavailable') await router.replace('/') }
function refreshOnFocus() {
  if (auth.phase.value === 'ready' && !document.hidden) auth.refreshProfile().catch(() => {})
}
watch(() => auth.user.value, user => {
  if (!user && auth.phase.value === 'anonymous' && !route.meta.public) router.replace('/login')
  if (user?.passwordChangeRequired && route.name !== 'change-password' && route.name !== 'reset') router.replace('/change-password')
  if (user && !user.passwordChangeRequired && user.status === 'PENDING' && route.meta.workspace) router.replace('/profile')
})
onMounted(() => window.addEventListener('focus', refreshOnFocus))
onUnmounted(() => window.removeEventListener('focus', refreshOnFocus))
</script>

<template>
  <v-app>
    <div v-if="auth.phase.value === 'loading'" class="full-state" role="status"><div class="brand-mark"><v-icon icon="mdi-home-heart" /></div><v-progress-circular indeterminate size="26" width="2" color="primary" /><p>Открываем ваше пространство…</p></div>
    <div v-else-if="auth.phase.value === 'unavailable'" class="full-state"><v-icon icon="mdi-cloud-alert-outline" size="46" color="primary" /><h1>Сервер пока недоступен</h1><p>{{ auth.connectionError.value }}</p><v-btn color="primary" @click="retry">Попробовать снова</v-btn></div>
    <template v-else-if="!auth.user.value || route.name === 'reset'">
      <main class="auth-layout">
        <aside class="auth-story"><router-link to="/" class="brand-lockup auth-brand"><span class="brand-mark"><v-icon icon="mdi-home-heart" /></span><span><strong>Дом рядом</strong><small>наш общий ритм</small></span></router-link><div class="auth-story-copy"><div class="story-kicker">МЕНЬШЕ СУЕТЫ · БОЛЬШЕ ЖИЗНИ</div><h1>Домашние дела<br>в общем ритме.</h1><p>Одно пространство для семьи, дел и времени, которое хочется оставить себе.</p><div class="story-shapes" aria-hidden="true"><span>⌂</span><span>☼</span><span>✓</span></div></div><div class="auth-story-foot">У каждого свой график. Дома — общий план.</div></aside>
        <section class="auth-form-area"><router-view /></section>
      </main>
    </template>
    <template v-else>
      <v-navigation-drawer v-model="drawer" :permanent="$vuetify.display.mdAndUp" :temporary="$vuetify.display.smAndDown" width="256" class="app-drawer">
        <router-link to="/" class="brand-lockup"><span class="brand-mark"><v-icon icon="mdi-home-heart" /></span><span><span class="brand-name">Дом рядом</span><span class="brand-caption">наш общий ритм</span></span></router-link>
        <div class="nav-section-label">{{ auth.canAdmin.value ? 'УПРАВЛЕНИЕ ПЛАТФОРМОЙ' : 'ВАШЕ ПРОСТРАНСТВО' }}</div>
        <v-list nav class="nav-list"><v-list-item v-for="item in nav" :key="item.to" :to="auth.user.value.passwordChangeRequired ? '/change-password' : item.to" :title="item.label" :prepend-icon="item.icon" rounded="lg" @click="drawer = false" /></v-list>
        <template #append><div class="sidebar-user"><v-avatar class="member-sage" size="38">{{ initials }}</v-avatar><div><strong>{{ auth.user.value.nickname }}</strong><small>{{ auth.user.value.isAdmin ? 'Администратор' : 'Участник' }}</small></div></div><v-btn block variant="text" prepend-icon="mdi-logout" :loading="exitBusy" @click="logout" class="signout-button">Выйти</v-btn></template>
      </v-navigation-drawer>
      <v-main><header class="topbar"><div class="topbar-left"><v-btn class="mobile-menu" variant="text" icon="mdi-menu" aria-label="Открыть меню" @click="drawer = !drawer" /><div><div class="eyebrow">HOUSEHOLD PLANNER</div><h1>{{ title }}</h1></div></div><v-chip v-if="auth.canAdmin.value" prepend-icon="mdi-shield-check-outline" color="primary" variant="tonal" size="small">Администратор</v-chip><v-avatar v-else class="member-sage" size="36">{{ initials }}</v-avatar></header><div class="page-content"><v-alert v-if="error" type="error" variant="tonal" closable @click:close="error = ''" class="page-alert">{{ error }}</v-alert><router-view /></div></v-main>
    </template>
  </v-app>
</template>
