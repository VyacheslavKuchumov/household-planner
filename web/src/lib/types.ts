export type AccountStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'REJECTED'
export interface Profile {
  id: string
  username: string
  email: string
  nickname: string
  status: AccountStatus
  isAdmin: boolean
  passwordChangeRequired: boolean
  createdAt: string
}
export interface SessionPayload { accessToken: string; expiresIn: number; user: Profile }
export interface UserPage { items: Profile[]; total: number; offset: number; limit: number }
export interface AuditEntry { id: string; actor: string | null; target: string | null; action: string; occurredAt: string }
export interface Household { id: string; name: string; timezone: string; role: string; memberCount: number }
export const statusLabels: Record<AccountStatus, string> = {
  PENDING: 'Ожидает подтверждения', ACTIVE: 'Активен', SUSPENDED: 'Доступ приостановлен', REJECTED: 'Заявка отклонена',
}
export const statusColors: Record<AccountStatus, string> = {
  PENDING: 'warning', ACTIVE: 'success', SUSPENDED: 'error', REJECTED: 'secondary',
}
