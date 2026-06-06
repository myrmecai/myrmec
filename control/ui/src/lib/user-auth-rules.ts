import type { User } from './api'

export function requiresPasswordForProvider(providerCode: string): boolean {
  return providerCode.trim().toUpperCase() === 'LOCAL'
}

export function canEditCoreUserFields(user: Pick<User, 'isSystem'>): boolean {
  return !user.isSystem
}

export function canSetPasswordForUser(providerCode: string): boolean {
  return requiresPasswordForProvider(providerCode)
}
