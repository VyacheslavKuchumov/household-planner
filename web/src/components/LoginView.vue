<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../composables/useAuth'
import { errorMessage } from '../lib/api'
const auth = useAuth(), router = useRouter(), route = useRoute()
const login = ref(''), password = ref(''), busy = ref(false), error = ref(''), visible = ref(false)
async function submit() {
  error.value = ''; busy.value = true
  try {
    await auth.login(login.value, password.value)
    password.value = ''
    await router.replace(auth.user.value?.passwordChangeRequired ? '/change-password' : auth.user.value?.status === 'PENDING' ? '/profile' : '/')
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
</script>
<template>
  <div class="auth-card"><div class="section-kicker">С ВОЗВРАЩЕНИЕМ</div><h1>Войти в аккаунт</h1><p class="form-intro">Ваш дом, планы и свободное время — рядом.</p>
    <v-alert v-if="route.query.changed" type="success" variant="tonal" class="form-alert">Пароль обновлён. Войдите с новым паролем.</v-alert>
    <v-alert v-if="error" type="error" variant="tonal" class="form-alert" role="alert">{{ error }}</v-alert>
    <form @submit.prevent="submit"><v-text-field v-model="login" label="Логин или email" autocomplete="username" variant="outlined" required maxlength="254" :disabled="busy" />
      <v-text-field v-model="password" label="Пароль" :type="visible ? 'text' : 'password'" autocomplete="current-password" variant="outlined" required maxlength="256" :disabled="busy" :append-inner-icon="visible ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" @click:append-inner="visible = !visible" />
      <v-btn type="submit" color="primary" block size="large" :loading="busy">Войти</v-btn></form>
    <p class="auth-crosslink">Нет аккаунта? <router-link to="/register">Зарегистрироваться</router-link></p><p class="subtle-note">Забыли пароль? Обратитесь к администратору: он отправит ссылку для восстановления.</p>
  </div>
</template>
