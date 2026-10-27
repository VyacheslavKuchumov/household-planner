import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import LoginView from './LoginView.vue'
const mocks = vi.hoisted(() => ({ login: vi.fn(), user: { value: null as null | { status: string; passwordChangeRequired: boolean } } }))
vi.mock('../composables/useAuth', () => ({ useAuth: () => mocks }))
beforeEach(() => { mocks.login.mockReset(); mocks.user.value = null })
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: ['/login', '/profile', '/change-password', '/'].map(path => ({ path, component: { template: '<div />' } })) })
  await router.push('/login'); await router.isReady()
  const wrapper = mount(LoginView, { global: { plugins: [createVuetify({ components, directives }), router] } })
  await wrapper.get('input[autocomplete="username"]').setValue('alice')
  await wrapper.get('input[autocomplete="current-password"]').setValue('a-valid-password')
  return { wrapper, router }
}
describe('login form', () => {
  it('shows a rejected login without navigating', async () => {
    mocks.login.mockRejectedValue(new Error('Неверный логин или пароль'))
    const { wrapper, router } = await render()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('Неверный логин или пароль')
    expect(router.currentRoute.value.path).toBe('/login')
    wrapper.unmount()
  })
  it('sends bootstrap users directly to compulsory password change', async () => {
    mocks.login.mockImplementation(async () => { mocks.user.value = { status: 'ACTIVE', passwordChangeRequired: true } })
    const { wrapper, router } = await render()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(mocks.login).toHaveBeenCalledWith('alice', 'a-valid-password')
    expect(router.currentRoute.value.path).toBe('/change-password')
    wrapper.unmount()
  })
  it('sends pending accounts to the personal cabinet', async () => {
    mocks.login.mockImplementation(async () => { mocks.user.value = { status: 'PENDING', passwordChangeRequired: false } })
    const { wrapper, router } = await render()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/profile')
    wrapper.unmount()
  })
})
