import {
  canEditCoreUserFields,
  canSetPasswordForUser,
  requiresPasswordForProvider,
} from './user-auth-rules'

describe('user auth rules', () => {
  it('requires password only for LOCAL provider', () => {
    expect(requiresPasswordForProvider('LOCAL')).toBe(true)
    expect(requiresPasswordForProvider('oidc_corp')).toBe(false)
  })

  it('prevents editing protected system user core fields', () => {
    expect(canEditCoreUserFields({ isSystem: true })).toBe(false)
    expect(canEditCoreUserFields({ isSystem: false })).toBe(true)
  })

  it('allows password changes only for LOCAL users', () => {
    expect(canSetPasswordForUser('LOCAL')).toBe(true)
    expect(canSetPasswordForUser('GOOGLE')).toBe(false)
  })
})
