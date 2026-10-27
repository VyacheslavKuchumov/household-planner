import type { SessionPayload } from './types'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId?: string) {
    super(message)
    this.name = 'ApiError'
  }
}
const base = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
let accessToken: string | null = null
let refreshPromise: Promise<SessionPayload> | null = null
let sessionListener: (value: SessionPayload | null) => void = () => {}
export function onSessionChanged(listener: typeof sessionListener) { sessionListener = listener }
export function acceptSession(value: SessionPayload | null) {
  accessToken = value?.accessToken || null
  sessionListener(value)
}
async function decode<T>(response: Response): Promise<T> {
  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    throw new ApiError(response.status, payload?.code || 'HTTP_ERROR',
      payload?.message || 'Сервис временно недоступен', payload?.requestId)
  }
  return payload as T
}
async function fetchJson<T>(path: string, options: RequestInit = {}): Promise<T> {
  try {
    const response = await fetch(`${base}${path}`, { ...options, credentials: 'include' })
    return await decode<T>(response)
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(0, 'NETWORK_ERROR', 'Не удалось связаться с сервером. Проверьте подключение')
  }
}
export function refreshSession(): Promise<SessionPayload> {
  if (!refreshPromise) {
    const rotate = async () => {
      const { csrfToken } = await fetchJson<{ csrfToken: string }>('/api/auth/csrf')
      const value = await fetchJson<SessionPayload>('/api/auth/refresh', {
        method: 'POST', headers: { 'X-CSRF-Token': csrfToken },
      })
      acceptSession(value)
      return value
    }
    // Refresh cookies are shared between tabs: serialize their rotation as well as requests in this tab.
    const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
    refreshPromise = (locks ? locks.request('household-session-refresh', rotate) : rotate())
      .catch(error => {
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) acceptSession(null)
        throw error
      }).finally(() => { refreshPromise = null })
  }
  return refreshPromise
}
export async function api<T>(path: string, options: RequestInit = {}, authenticated = true, retry = true): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body) headers.set('Content-Type', 'application/json')
  if (authenticated && accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  try {
    return await fetchJson<T>(path, { ...options, headers })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && authenticated) {
      if (retry) {
        await refreshSession()
        return api<T>(path, options, authenticated, false)
      }
      acceptSession(null)
    }
    throw error
  }
}
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Не удалось выполнить действие'
}
