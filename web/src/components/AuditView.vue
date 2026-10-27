<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage } from '../lib/api'
import type { AuditEntry } from '../lib/types'
const entries = ref<AuditEntry[]>([]), loading = ref(true), error = ref('')
const labels: Record<string, string> = { ACCOUNT_APPROVED: 'Аккаунт подтверждён', ACCOUNT_REJECTED: 'Заявка отклонена', ACCOUNT_SUSPENDED: 'Доступ приостановлен', ACCOUNT_REACTIVATED: 'Доступ восстановлен', PASSWORD_RESET_ISSUED: 'Запрошено письмо для сброса', PASSWORD_RESET_COMPLETED: 'Пароль восстановлен', PASSWORD_CHANGED: 'Пароль изменён', BOOTSTRAP_PASSWORD_CHANGED: 'Первичный пароль изменён', PLATFORM_ROLE_GRANTED: 'Выдана роль платформы', PLATFORM_ROLE_REVOKED: 'Отозвана роль платформы' }
async function load() { loading.value = true; error.value = ''; try { entries.value = await api<AuditEntry[]>('/api/admin/audit') } catch (e) { error.value = errorMessage(e) } finally { loading.value = false } }
function date(value: string) { return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
onMounted(load)
</script>
<template>
  <section><div class="page-heading"><div><div class="section-kicker">КОНТРОЛЬ И ИСТОРИЯ</div><h2>Журнал действий</h2><p>Последние 100 изменений аккаунтов и настроек безопасности.</p></div><v-btn variant="tonal" color="primary" prepend-icon="mdi-refresh" :loading="loading" @click="load">Обновить</v-btn></div><v-alert v-if="error" type="error" variant="tonal" class="page-alert" role="alert">{{ error }}</v-alert><v-card class="surface-card users-card" rounded="xl" elevation="0"><v-progress-linear v-if="loading" indeterminate color="primary" /><div class="table-scroll"><table class="data-table"><thead><tr><th>Действие</th><th>Кто выполнил</th><th>Аккаунт</th><th>Когда</th></tr></thead><tbody><tr v-for="entry in entries" :key="entry.id"><td><strong>{{ labels[entry.action] || entry.action }}</strong></td><td class="identifier" :title="entry.actor || ''">{{ entry.actor?.slice(0, 8) || 'Система' }}</td><td class="identifier" :title="entry.target || ''">{{ entry.target?.slice(0, 8) || 'Удалён' }}</td><td class="date-cell">{{ date(entry.occurredAt) }}</td></tr><tr v-if="!loading && !entries.length"><td colspan="4" class="empty-cell">Действий пока нет</td></tr></tbody></table></div></v-card></section>
</template>
