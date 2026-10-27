<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import { useAuth } from '../composables/useAuth'
const route = useRoute(), router = useRouter(), auth = useAuth()
const token = ref(typeof route.query.token === 'string' ? route.query.token : '')
const password = ref(''), repeat = ref(''), busy = ref(false), error = ref(''), done = ref(false)
watch(() => route.query.token, value => {
  if (typeof value === 'string' && value) { token.value = value; done.value = false; error.value = ''; router.replace({ path: '/reset-password' }) }
}, { immediate: true })
async function submit() {
  error.value = ''
  if (password.value !== repeat.value) { error.value = 'Пароли не совпадают'; return }
  busy.value = true
  try {
    await api('/api/auth/password-reset/complete', { method: 'POST', body: JSON.stringify({ token: token.value, newPassword: password.value }) }, false)
    password.value = ''; repeat.value = ''; token.value = ''; auth.clear(); done.value = true
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
</script>
<template>
  <div class="auth-card"><div class="section-kicker">ВОССТАНОВЛЕНИЕ ДОСТУПА</div><h1>{{ done ? 'Пароль обновлён' : 'Новый пароль' }}</h1><p class="form-intro">{{ done ? 'Все предыдущие сессии завершены. Теперь можно войти с новым паролем.' : 'Ссылка действует 30 минут и используется только один раз.' }}</p><v-alert v-if="error" type="error" variant="tonal" class="form-alert" role="alert">{{ error }}</v-alert><v-btn v-if="done" color="primary" to="/login" block>Перейти ко входу</v-btn><template v-else-if="token"><form @submit.prevent="submit"><v-text-field v-model="password" label="Новый пароль" type="password" autocomplete="new-password" variant="outlined" minlength="12" maxlength="256" required :disabled="busy" hint="Не менее 12 символов" /><v-text-field v-model="repeat" label="Повторите новый пароль" type="password" autocomplete="new-password" variant="outlined" minlength="12" maxlength="256" required :disabled="busy" /><v-btn type="submit" color="primary" block size="large" :loading="busy">Установить пароль</v-btn></form></template><v-alert v-else type="warning" variant="tonal">Ссылка отсутствует. Откройте ссылку из письма или запросите новую у администратора.</v-alert><p class="auth-crosslink"><router-link to="/login">Вернуться ко входу</router-link></p></div>
</template>
