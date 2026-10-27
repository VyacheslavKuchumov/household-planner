import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const session = { accessToken: 'renewed-token', expiresIn: 600, user: { id: 'u1', status: 'ACTIVE' } }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }) }
beforeEach(() => vi.resetModules())
afterEach(() => vi.unstubAllGlobals())
describe('authenticated API client', () => {
  it('serializes concurrent refreshes and retries protected requests with the new access token', async () => {
    let refreshCount = 0
    const fetch = vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/csrf')) return json({ csrfToken: 'csrf' })
      if (url.endsWith('/refresh')) { refreshCount++; return json(session) }
      return new Headers(init.headers).get('Authorization') === 'Bearer renewed-token' ? json({ ok: true }) : json({ code: 'UNAUTHENTICATED', message: 'expired' }, 401)
    })
    vi.stubGlobal('fetch', fetch)
    const client = await import('./api')
    const values = await Promise.all([client.api<{ ok: boolean }>('/api/me/profile'), client.api<{ ok: boolean }>('/api/me/profile')])
    expect(values.every(value => value.ok)).toBe(true)
    expect(refreshCount).toBe(1)
    expect(fetch.mock.calls.every(([, init]) => init.credentials === 'include')).toBe(true)
  })
  it('does not turn a network failure into a successful operation or discard the account', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const client = await import('./api')
    const listener = vi.fn()
    client.onSessionChanged(listener)
    await expect(client.api('/api/admin/users')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 })
    expect(listener).not.toHaveBeenCalled()
  })
  it('does not refresh a failed password login', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ code: 'INVALID_CREDENTIALS', message: 'Неверный пароль' }, 401))
    vi.stubGlobal('fetch', fetch)
    const client = await import('./api')
    await expect(client.api('/api/auth/login', { method: 'POST', body: '{}' }, false)).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('stops retrying and clears a revoked session', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/csrf') ? json({ csrfToken: 'csrf' }) : json({ code: 'UNAUTHENTICATED', message: 'revoked' }, 401)))
    const client = await import('./api')
    const listener = vi.fn(); client.onSessionChanged(listener)
    await expect(client.api('/api/me/profile')).rejects.toMatchObject({ status: 401 })
    expect(listener).toHaveBeenCalledWith(null)
  })
})
