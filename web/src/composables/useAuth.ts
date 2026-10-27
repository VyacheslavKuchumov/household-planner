import { computed, ref } from 'vue'
import { acceptSession, api, ApiError, errorMessage, onSessionChanged, refreshSession } from '../lib/api'
import type { Profile, SessionPayload } from '../lib/types'

const user = ref<Profile | null>(null)
const phase = ref<'loading' | 'anonymous' | 'ready' | 'unavailable'>('loading')
const connectionError = ref('')
let initialization: Promise<void> | null = null
onSessionChanged(value => {
  user.value = value?.user || null
  phase.value = value ? 'ready' : 'anonymous'
})
async function initialize(force = false): Promise<void> {
  if (!initialization || force) {
    phase.value = 'loading'
    initialization = refreshSession().then(() => {}).catch(error => {
      if (error instanceof ApiError && [401, 403].includes(error.status)) {
        acceptSession(null)
      } else {
        connectionError.value = errorMessage(error)
        phase.value = 'unavailable'
      }
    })
  }
  return initialization
}
async function login(login: string, password: string) {
  acceptSession(await api<SessionPayload>('/api/auth/login', {
    method: 'POST', body: JSON.stringify({ login, password }),
  }, false))
}
async function register(input: { username: string; email: string; nickname: string; password: string }) {
  await api<Profile>('/api/auth/register', { method: 'POST', body: JSON.stringify(input) }, false)
  await login(input.username, input.password)
}
async function refreshProfile() {
  user.value = await api<Profile>('/api/me/profile')
}
async function logout(all = false) {
  await api(`/api/auth/${all ? 'logout-all' : 'logout'}`, { method: 'POST' })
  acceptSession(null)
}
export function useAuth() {
  return {
    user, phase, connectionError, initialize, login, register, refreshProfile, logout,
    canUseWorkspace: computed(() => user.value?.status === 'ACTIVE' && !user.value.passwordChangeRequired),
    canAdmin: computed(() => user.value?.isAdmin && user.value.status === 'ACTIVE' && !user.value.passwordChangeRequired),
    clear: () => acceptSession(null),
  }
}
