<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage } from '../lib/api'
import { useAuth } from '../composables/useAuth'
import type { Household } from '../lib/types'
const auth = useAuth()
const households = ref<Household[]>([]), busy = ref(true), error = ref(''), dialog = ref(false), saving = ref(false)
const name = ref(''), timezone = ref(Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Yekaterinburg'), formError = ref('')
async function load() { busy.value = true; error.value = ''; try { households.value = await api<Household[]>('/api/households') } catch (e) { error.value = errorMessage(e) } finally { busy.value = false } }
async function create() {
  saving.value = true; formError.value = ''
  try { const item = await api<Household>('/api/households', { method: 'POST', body: JSON.stringify({ name: name.value, timezone: timezone.value }) }); households.value.push(item); name.value = ''; dialog.value = false }
  catch (e) { formError.value = errorMessage(e) } finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <section><div class="page-heading"><div><div class="section-kicker">ДОМАШНЕЕ ПРОСТРАНСТВО</div><h2>Добро пожаловать, {{ auth.user.value?.nickname }}</h2><p>Соберите домашние планы в одном месте.</p></div><v-btn color="primary" prepend-icon="mdi-plus" @click="dialog = true">Создать домохозяйство</v-btn></div><v-alert v-if="error" type="error" variant="tonal" class="page-alert" role="alert">{{ error }}<v-btn variant="text" @click="load">Повторить</v-btn></v-alert><v-progress-linear v-if="busy" indeterminate color="primary" aria-label="Загрузка домохозяйств" />
    <v-card v-else-if="!error && !households.length" class="surface-card empty-card" rounded="xl" elevation="0"><div class="empty-illustration"><v-icon icon="mdi-home-plus-outline" size="48" /></div><h3>Начните с вашего дома</h3><p>Создайте домохозяйство и выберите его часовой пояс.<br>Вы станете владельцем этого пространства.</p><v-btn color="primary" variant="tonal" @click="dialog = true">Создать первое домохозяйство</v-btn></v-card>
    <div v-else class="household-grid"><v-card v-for="household in households" :key="household.id" class="surface-card household-card" rounded="xl" elevation="0"><div class="household-card-top"><div class="household-mark"><v-icon icon="mdi-home-heart" /></div><v-chip size="small" color="primary" variant="tonal">{{ household.role === 'OWNER' ? 'Владелец' : 'Участник' }}</v-chip></div><h3>{{ household.name }}</h3><p><v-icon icon="mdi-map-clock-outline" size="16" /> {{ household.timezone }}</p><div class="household-card-footer"><v-icon icon="mdi-account-group-outline" size="18" /> Участников: {{ household.memberCount }}</div></v-card></div>
    <v-dialog v-model="dialog" max-width="480" :persistent="saving"><v-card rounded="xl" class="dialog-card"><v-card-title>Новое домохозяйство</v-card-title><form @submit.prevent="create"><v-card-text><v-alert v-if="formError" type="error" variant="tonal" class="form-alert">{{ formError }}</v-alert><v-text-field v-model="name" label="Название" placeholder="Наш дом" variant="outlined" maxlength="100" required :disabled="saving" /><v-combobox v-model="timezone" label="Часовой пояс" :items="['Asia/Yekaterinburg', 'Europe/Moscow', 'Asia/Novosibirsk', 'Europe/Kaliningrad', 'UTC']" variant="outlined" required :disabled="saving" /></v-card-text><v-card-actions><v-spacer /><v-btn :disabled="saving" @click="dialog = false">Отмена</v-btn><v-btn type="submit" color="primary" :loading="saving">Создать</v-btn></v-card-actions></form></v-card></v-dialog>
  </section>
</template>
