<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../composables/useAuth'
import { errorMessage } from '../lib/api'
const auth = useAuth(), router = useRouter()
const form = reactive({ username: '', email: '', nickname: '', password: '' })
const repeat = ref(''), busy = ref(false), error = ref('')
async function submit() {
  error.value = ''
  if (form.password !== repeat.value) { error.value = 'Пароли не совпадают'; return }
  busy.value = true
  try { await auth.register(form); form.password = ''; repeat.value = ''; await router.replace('/profile') }
  catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
</script>
<template>
  <div class="auth-card"><div class="section-kicker">НАЧНЁМ ЗНАКОМСТВО</div><h1>Создать аккаунт</h1><p class="form-intro">После регистрации откроется личный кабинет. Доступ к пространству подтвердит администратор.</p><v-alert v-if="error" type="error" variant="tonal" class="form-alert" role="alert">{{ error }}</v-alert>
    <form @submit.prevent="submit"><v-text-field v-model="form.nickname" label="Никнейм" autocomplete="nickname" variant="outlined" maxlength="80" required :disabled="busy" />
      <v-text-field v-model="form.username" label="Логин" autocomplete="username" variant="outlined" pattern="[a-zA-Z][a-zA-Z0-9_]{2,31}" hint="3–32 латинских символа: буквы, цифры и _" maxlength="32" required :disabled="busy" />
      <v-text-field v-model="form.email" label="Email" type="email" autocomplete="email" variant="outlined" maxlength="254" required :disabled="busy" />
      <v-text-field v-model="form.password" label="Пароль" type="password" autocomplete="new-password" variant="outlined" minlength="12" maxlength="256" hint="Не менее 12 символов" required :disabled="busy" />
      <v-text-field v-model="repeat" label="Повторите пароль" type="password" autocomplete="new-password" variant="outlined" minlength="12" maxlength="256" required :disabled="busy" />
      <v-btn type="submit" color="primary" block size="large" :loading="busy">Зарегистрироваться</v-btn></form><p class="auth-crosslink">Уже есть аккаунт? <router-link to="/login">Войти</router-link></p>
  </div>
</template>
