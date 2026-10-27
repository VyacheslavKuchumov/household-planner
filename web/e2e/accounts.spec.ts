import { test, expect } from '@playwright/test'

test('account lifecycle: bootstrap, registration, approval, password recovery and revocation', async ({ browser, request }) => {
  const adminContext = await browser.newContext()
  const memberContext = await browser.newContext()
  const admin = await adminContext.newPage(), member = await memberContext.newPage()
  const suffix = Date.now().toString()
  const username = `user_${suffix}`, email = `${username}@example.test`
  const password = 'member-original-password-123'
  const adminPassword = 'changed-admin-password-123'
  const apiBase = process.env.E2E_API_URL || 'http://127.0.0.1:8080'
  const mailpit = process.env.MAILPIT_URL || 'http://127.0.0.1:8025'
  try {
    await admin.goto('/#/login')
    await admin.getByLabel('Логин или email').fill('admin')
    await admin.getByLabel('Пароль', { exact: true }).fill('admin')
    await admin.getByRole('button', { name: 'Войти', exact: true }).click()
    await expect(admin).toHaveURL(/change-password/)
    await expect(admin.getByRole('link', { name: 'Пользователи' })).toHaveCount(0)
    await admin.getByLabel('Временный пароль', { exact: true }).fill('admin')
    await admin.getByLabel('Новый пароль', { exact: true }).fill(adminPassword)
    await admin.getByLabel('Повторите новый пароль', { exact: true }).fill(adminPassword)
    await admin.getByRole('button', { name: 'Сменить пароль', exact: true }).click()
    await expect(admin).toHaveURL(/login/)
    await admin.getByLabel('Логин или email').fill('admin')
    await admin.getByLabel('Пароль', { exact: true }).fill(adminPassword)
    await admin.getByRole('button', { name: 'Войти', exact: true }).click()
    await expect(admin).toHaveURL(/#\/$/)

    await member.goto('/#/register')
    await member.getByLabel('Никнейм', { exact: true }).fill('Новый участник')
    await member.getByLabel('Логин', { exact: true }).fill(username)
    await member.getByLabel('Email', { exact: true }).fill(email)
    await member.getByLabel('Пароль', { exact: true }).fill(password)
    await member.getByLabel('Повторите пароль', { exact: true }).fill(password)
    const loginResponse = member.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 200)
    await member.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click()
    const memberToken = (await (await loginResponse).json()).accessToken as string
    await expect(member).toHaveURL(/profile/)
    await expect(member.getByText('Аккаунт ожидает подтверждения', { exact: true })).toBeVisible()
    const deniedAdmin = await request.get(`${apiBase}/api/admin/users`, { headers: { Authorization: `Bearer ${memberToken}` } })
    expect(deniedAdmin.status()).toBe(403)
    const deniedWorkspace = await request.get(`${apiBase}/api/workspace`, { headers: { Authorization: `Bearer ${memberToken}` } })
    expect(deniedWorkspace.status()).toBe(403)
    await member.getByLabel('Никнейм', { exact: true }).fill('Мой новый никнейм')
    await member.getByRole('button', { name: 'Сохранить изменения', exact: true }).click()
    await expect(member.getByText('Никнейм сохранён', { exact: true })).toBeVisible()

    await admin.getByRole('link', { name: 'Пользователи', exact: true }).click()
    const row = admin.getByRole('row').filter({ hasText: email })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: 'Подтвердить', exact: true }).click()
    await admin.getByRole('button', { name: 'Подтвердить действие', exact: true }).click()
    await expect(row).toHaveCount(0)
    await member.reload()
    await expect(member.getByRole('link', { name: 'Моё пространство', exact: true })).toBeVisible()
    await member.getByRole('link', { name: 'Моё пространство', exact: true }).click()
    await member.getByRole('button', { name: 'Создать первое домохозяйство', exact: true }).click()
    await member.getByLabel('Название', { exact: true }).fill('Дом для E2E')
    await member.getByRole('button', { name: 'Создать', exact: true }).click()
    await expect(member.getByRole('heading', { name: 'Дом для E2E' })).toBeVisible()
    await member.reload()
    await expect(member.getByRole('heading', { name: 'Дом для E2E' })).toBeVisible()
    expect(await member.evaluate(() => Object.keys(localStorage).some(key => /token|password|admin-accounts/i.test(key)))).toBe(false)

    // Refreshing the whole page must retain server-backed session and profile state.
    await member.getByRole('link', { name: 'Личный кабинет', exact: true }).click()
    await expect(member.getByLabel('Никнейм', { exact: true })).toHaveValue('Мой новый никнейм')
    await member.getByLabel('Текущий пароль', { exact: true }).fill(password)
    await member.getByLabel('Новый пароль', { exact: true }).fill('member-next-password-123')
    await member.getByLabel('Повторите новый пароль', { exact: true }).fill('member-next-password-123')
    await member.getByRole('button', { name: 'Сменить пароль', exact: true }).click()
    await expect(member).toHaveURL(/login/)

    // The administrator sends a real SMTP message to the local Mailpit server.
    await admin.getByLabel('Статус аккаунта', { exact: true }).click()
    await admin.getByRole('option', { name: 'Активные', exact: true }).click()
    const activeRow = admin.getByRole('row').filter({ hasText: email })
    await expect(activeRow).toBeVisible()
    await activeRow.getByRole('button', { name: 'Сброс пароля', exact: true }).click()
    await admin.getByRole('button', { name: 'Подтвердить действие', exact: true }).click()
    await expect(admin.getByText('Письмо со ссылкой отправлено', { exact: true })).toBeVisible()
    let messageId = ''
    await expect.poll(async () => {
      const list = await (await request.get(`${mailpit}/api/v1/messages`)).json()
      messageId = list.messages?.find((message: { To: { Address: string }[]; ID: string }) => message.To?.some(to => to.Address === email))?.ID || ''
      return messageId
    }).not.toBe('')
    const message = await (await request.get(`${mailpit}/api/v1/message/${messageId}`)).json()
    const link = String(message.Text).match(/http[^\s]+#\/reset-password\?token=[A-Za-z0-9_-]+/)?.[0]
    expect(link).toBeTruthy()
    await member.goto(link!)
    await member.getByLabel('Новый пароль', { exact: true }).fill('member-recovered-password-123')
    await member.getByLabel('Повторите новый пароль', { exact: true }).fill('member-recovered-password-123')
    await member.getByRole('button', { name: 'Установить пароль', exact: true }).click()
    await expect(member.getByRole('heading', { name: 'Пароль обновлён', exact: true })).toBeVisible()
    await member.getByRole('link', { name: 'Перейти ко входу', exact: true }).click()
    await member.getByLabel('Логин или email').fill(username)
    await member.getByLabel('Пароль', { exact: true }).fill('member-recovered-password-123')
    await member.getByRole('button', { name: 'Войти', exact: true }).click()
    await expect(member).toHaveURL(/#\/$/)
    await activeRow.getByRole('button', { name: 'Приостановить', exact: true }).click()
    await admin.getByRole('button', { name: 'Подтвердить действие', exact: true }).click()
    await member.reload()
    await expect(member).toHaveURL(/login/)
    await admin.getByRole('link', { name: 'Журнал действий', exact: true }).click()
    await expect(admin.getByText('Аккаунт подтверждён', { exact: true })).toBeVisible()
    await expect(admin.getByText('Пароль восстановлен', { exact: true })).toBeVisible()
  } finally {
    await memberContext.close(); await adminContext.close()
  }
})

test('protected API denies requests without an authenticated session', async ({ request }) => {
  const response = await request.get(`${process.env.E2E_API_URL || 'http://127.0.0.1:8080'}/api/admin/users`)
  expect(response.status()).toBe(401)
})
