<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../composables/useAuth'
import { api, errorMessage } from '../lib/api'
import { statusColors, statusLabels } from '../lib/types'
import type { Profile } from '../lib/types'
const props = defineProps<{ forced?: boolean }>()
const auth = useAuth(), router = useRouter()
const nickname = ref(auth.user.value?.nickname || '')
const profileBusy = ref(false), passwordBusy = ref(false), checkBusy = ref(false)
const profileError = ref(''), passwordError = ref(''), success = ref('')
const current = ref(''), next = ref(''), repeat = ref('')
const forced = computed(() => props.forced || auth.user.value?.passwordChangeRequired)
let timer: ReturnType<typeof setInterval> | undefined
watch(() => auth.user.value?.nickname, value => { nickname.value = value || '' })
async function saveProfile() {
  profileBusy.value = true; profileError.value = ''; success.value = ''
  try { auth.user.value = await api<Profile>('/api/me/profile', { method: 'PATCH', body: JSON.stringify({ nickname: nickname.value }) }); success.value = 'Никнейм сохранён' }
  catch (e) { profileError.value = errorMessage(e) }
  finally { profileBusy.value = false }
}
async function changePassword() {
  passwordError.value = ''
  if (next.value !== repeat.value) { passwordError.value = 'Пароли не совпадают'; return }
  passwordBusy.value = true
  try {
    await api('/api/auth/change-password', { method: 'POST', body: JSON.stringify({ currentPassword: current.value, newPassword: next.value }) })
    current.value = ''; next.value = ''; repeat.value = ''; auth.clear()
    await router.replace({ path: '/login', query: { changed: '1' } })
  } catch (e) { passwordError.value = errorMessage(e) }
  finally { passwordBusy.value = false }
}
async function checkStatus() {
  checkBusy.value = true; profileError.value = ''
  try { await auth.refreshProfile() } catch (e) { profileError.value = errorMessage(e) }
  finally { checkBusy.value = false }
}
onMounted(() => { timer = setInterval(() => { if (auth.user.value?.status === 'PENDING' && !document.hidden && !checkBusy.value) checkStatus() }, 20000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <section v-if="auth.user.value">
    <div class="page-heading"><div><div class="section-kicker">ВАША УЧЁТНАЯ ЗАПИСЬ</div><h2>{{ forced ? 'Установите постоянный пароль' : 'Личный кабинет' }}</h2><p>{{ forced ? 'Это обязательный шаг для завершения первого входа.' : 'Настройки имени и безопасности вашего аккаунта.' }}</p></div><v-chip :color="statusColors[auth.user.value.status]" variant="tonal">{{ statusLabels[auth.user.value.status] }}</v-chip></div>
    <v-alert v-if="forced" type="warning" variant="tonal" class="page-alert" icon="mdi-shield-lock-outline">До смены временного пароля доступны только эта страница и выход. После сохранения потребуется войти заново.</v-alert>
    <v-alert v-else-if="auth.user.value.status === 'PENDING'" variant="tonal" color="warning" class="page-alert" icon="mdi-account-clock-outline"><strong>Аккаунт ожидает подтверждения</strong><div>Никнейм и пароль уже можно изменить. Остальные разделы откроются после одобрения администратора.</div><v-btn variant="text" size="small" :loading="checkBusy" @click="checkStatus">Проверить статус</v-btn></v-alert>
    <div class="profile-grid" :class="{ 'profile-forced': forced }">
      <v-card v-if="!forced" class="surface-card" rounded="xl" elevation="0"><div class="card-heading"><v-icon icon="mdi-account-outline" color="primary" /><div><h3>Основная информация</h3><p>Никнейм увидят участники вашего дома.</p></div></div><v-alert v-if="profileError" type="error" variant="tonal" class="form-alert" role="alert">{{ profileError }}</v-alert><v-alert v-if="success" type="success" variant="tonal" class="form-alert">{{ success }}</v-alert><form @submit.prevent="saveProfile"><v-text-field v-model="nickname" label="Никнейм" autocomplete="nickname" variant="outlined" maxlength="80" required :disabled="profileBusy" /><v-text-field :model-value="auth.user.value.username" label="Логин" variant="outlined" readonly /><v-text-field :model-value="auth.user.value.email" label="Email" variant="outlined" readonly /><v-btn type="submit" color="primary" :loading="profileBusy">Сохранить изменения</v-btn></form></v-card>
      <v-card class="surface-card" rounded="xl" elevation="0"><div class="card-heading"><v-icon icon="mdi-lock-outline" color="primary" /><div><h3>{{ forced ? 'Новый пароль администратора' : 'Смена пароля' }}</h3><p>Остальные сессии будут завершены.</p></div></div><v-alert v-if="passwordError" type="error" variant="tonal" class="form-alert" role="alert">{{ passwordError }}</v-alert><form @submit.prevent="changePassword"><v-text-field v-model="current" :label="forced ? 'Временный пароль' : 'Текущий пароль'" type="password" autocomplete="current-password" variant="outlined" maxlength="256" required :disabled="passwordBusy" /><v-text-field v-model="next" label="Новый пароль" type="password" autocomplete="new-password" variant="outlined" minlength="12" maxlength="256" hint="Не менее 12 символов" required :disabled="passwordBusy" /><v-text-field v-model="repeat" label="Повторите новый пароль" type="password" autocomplete="new-password" variant="outlined" minlength="12" maxlength="256" required :disabled="passwordBusy" /><v-btn type="submit" color="primary" :loading="passwordBusy">Сменить пароль</v-btn></form></v-card>
    </div>
  </section>
</template>
