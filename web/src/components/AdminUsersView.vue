<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, errorMessage } from '../lib/api'
import { useAuth } from '../composables/useAuth'
import { statusLabels, statusColors } from '../lib/types'
import type { Profile, UserPage } from '../lib/types'
const auth = useAuth()
const items = ref<Profile[]>([]), total = ref(0), page = ref(1), status = ref('PENDING'), search = ref('')
const loading = ref(true), error = ref(''), feedback = ref(''), busy = ref(false)
const pending = ref<{ user: Profile; action: string; title: string; text: string } | null>(null)
const limit = 20
let version = 0, searchTimer: ReturnType<typeof setTimeout> | undefined
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / limit)))
const choices = [ { title: 'Ожидают подтверждения', value: 'PENDING' }, { title: 'Активные', value: 'ACTIVE' }, { title: 'Приостановленные', value: 'SUSPENDED' }, { title: 'Отклонённые', value: 'REJECTED' }, { title: 'Все аккаунты', value: '' } ]
async function load() {
  const current = ++version
  loading.value = true; error.value = ''
  const params = new URLSearchParams({ offset: String((page.value - 1) * limit), limit: String(limit), search: search.value.trim() })
  if (status.value) params.set('status', status.value)
  try {
    const result = await api<UserPage>(`/api/admin/users?${params}`)
    if (current === version) {
      total.value = result.total
      const last = Math.max(1, Math.ceil(result.total / limit))
      if (page.value > last) page.value = last
      else items.value = result.items
    }
  } catch (e) { if (current === version) error.value = errorMessage(e) }
  finally { if (current === version) loading.value = false }
}
function confirm(user: Profile, action: string) {
  const messages: Record<string, [string, string]> = {
    approve: ['Подтвердить аккаунт?', 'Пользователь получит доступ к пространству и сможет создавать домохозяйства.'],
    reject: ['Отклонить заявку?', 'Вход этого пользователя будет закрыт. Действие попадёт в журнал.'],
    suspend: ['Приостановить доступ?', 'Все сессии пользователя будут завершены. Доступ можно будет восстановить.'],
    reactivate: ['Восстановить доступ?', 'Пользователь снова сможет войти в приложение.'],
    'password-reset': ['Отправить ссылку для сброса?', 'Одноразовая ссылка будет отправлена на email пользователя. Срок действия — 30 минут.'],
  }
  pending.value = { user, action, title: messages[action][0], text: messages[action][1] }
}
async function execute() {
  if (!pending.value) return
  busy.value = true; error.value = ''; feedback.value = ''
  const operation = pending.value
  try {
    await api(`/api/admin/users/${operation.user.id}/${operation.action}`, { method: 'POST' })
    feedback.value = operation.action === 'password-reset' ? 'Письмо со ссылкой отправлено' : 'Изменение сохранено'
    pending.value = null
    await load()
  } catch (e) { error.value = errorMessage(e); pending.value = null }
  finally { busy.value = false }
}
function formatDate(value: string) { return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
function resetPage() { if (page.value !== 1) page.value = 1; else load() }
watch(status, resetPage)
watch(page, load)
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(resetPage, 350) })
onMounted(load)
onUnmounted(() => { version++; clearTimeout(searchTimer) })
</script>
<template>
  <section><div class="page-heading"><div><div class="section-kicker">УПРАВЛЕНИЕ ДОСТУПОМ</div><h2>Пользователи</h2><p>Проверяйте регистрации и управляйте доступом к платформе.</p></div><v-btn color="primary" variant="tonal" prepend-icon="mdi-refresh" :loading="loading" @click="load">Обновить</v-btn></div>
    <v-alert v-if="error" type="error" variant="tonal" class="page-alert" role="alert">{{ error }}</v-alert><v-alert v-if="feedback" type="success" variant="tonal" class="page-alert" closable @click:close="feedback = ''">{{ feedback }}</v-alert>
    <v-card class="surface-card users-card" rounded="xl" elevation="0"><div class="users-toolbar"><v-text-field v-model="search" label="Поиск по имени, логину или email" prepend-inner-icon="mdi-magnify" variant="outlined" density="compact" hide-details maxlength="100" class="users-search" /><v-select v-model="status" :items="choices" label="Статус аккаунта" variant="outlined" density="compact" hide-details class="users-status" /></div>
      <v-progress-linear v-if="loading" indeterminate color="primary" class="table-progress" aria-label="Загрузка пользователей" />
      <div class="table-scroll"><table class="data-table"><thead><tr><th>Пользователь</th><th>Статус</th><th>Регистрация</th><th class="actions-heading">Действия</th></tr></thead><tbody><tr v-for="user in items" :key="user.id"><td><div class="table-person"><v-avatar size="38" class="member-sage">{{ user.nickname.slice(0, 1).toUpperCase() }}</v-avatar><div><strong>{{ user.nickname }} <v-icon v-if="user.isAdmin" icon="mdi-shield-check-outline" size="15" color="primary" title="Администратор" /></strong><span>{{ user.email }}</span><small>@{{ user.username }}</small></div></div></td><td><v-chip :color="statusColors[user.status]" variant="tonal" size="small">{{ statusLabels[user.status] }}</v-chip><small v-if="user.passwordChangeRequired" class="cell-note">Требуется смена пароля</small></td><td class="date-cell">{{ formatDate(user.createdAt) }}</td><td><div class="table-actions"><template v-if="user.status === 'PENDING'"><v-btn size="small" variant="tonal" color="primary" :disabled="busy" @click="confirm(user, 'approve')">Подтвердить</v-btn><v-btn size="small" variant="text" color="error" :disabled="busy" @click="confirm(user, 'reject')">Отклонить</v-btn><v-btn size="small" variant="text" icon="mdi-lock-reset" aria-label="Сброс пароля" :disabled="busy" @click="confirm(user, 'password-reset')" /></template><template v-else-if="user.status === 'ACTIVE'"><v-btn size="small" variant="text" prepend-icon="mdi-lock-reset" :disabled="busy" @click="confirm(user, 'password-reset')">Сброс пароля</v-btn><v-btn size="small" variant="text" color="error" :disabled="busy || user.id === auth.user.value?.id" @click="confirm(user, 'suspend')">Приостановить</v-btn></template><v-btn v-else-if="user.status === 'SUSPENDED'" size="small" variant="tonal" color="primary" :disabled="busy" @click="confirm(user, 'reactivate')">Восстановить</v-btn><span v-else class="muted">Действий нет</span></div></td></tr><tr v-if="!loading && !items.length"><td colspan="4"><div class="table-empty"><v-icon icon="mdi-account-search-outline" size="34" /><strong>Ничего не найдено</strong><span>Попробуйте другой запрос или статус.</span></div></td></tr></tbody></table></div>
      <div class="table-footer"><span>Найдено: {{ total }}</span><v-pagination v-if="pageCount > 1" v-model="page" :length="pageCount" :total-visible="4" density="comfortable" :disabled="loading" aria-label="Страницы пользователей" /></div>
    </v-card><div class="security-caption"><v-icon icon="mdi-shield-lock-outline" size="17" /><span>Пароли пользователей скрыты. Изменения доступа фиксируются в журнале действий.</span></div>
    <v-dialog :model-value="!!pending" max-width="470" :persistent="busy" @update:model-value="value => { if (!value && !busy) pending = null }"><v-card v-if="pending" rounded="xl" class="dialog-card"><v-card-title>{{ pending.title }}</v-card-title><v-card-text><p class="confirmation-user">{{ pending.user.nickname }}<span>{{ pending.user.email }}</span></p><p class="dialog-copy">{{ pending.text }}</p></v-card-text><v-card-actions><v-spacer /><v-btn :disabled="busy" @click="pending = null">Отмена</v-btn><v-btn color="primary" :loading="busy" @click="execute">Подтвердить действие</v-btn></v-card-actions></v-card></v-dialog>
  </section>
</template>
