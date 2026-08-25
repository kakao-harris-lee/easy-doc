'use client'

import * as React from 'react'

export type Role = 'individual' | 'org' | 'admin'

export interface AuthUser {
  id: string
  name: string
  email: string
  org: string
  role: Role
  /** Short label shown in the account menu badge. */
  roleLabel: string
}

/** Demo accounts for the prototype. No real credentials are checked. */
export const demoAccounts: (AuthUser & { password: string })[] = [
  {
    id: 'u-admin',
    name: '최유진',
    email: 'admin@easyread.go.kr',
    org: '플랫폼 운영팀',
    role: 'admin',
    roleLabel: '플랫폼 운영자',
    password: 'demo',
  },
  {
    id: 'u-org',
    name: '김서연',
    email: 'manager@seoul.go.kr',
    org: '서울특별시 복지정책과',
    role: 'org',
    roleLabel: '기관 담당자',
    password: 'demo',
  },
  {
    id: 'u-individual',
    name: '이도현',
    email: 'user@example.com',
    org: '개인 사용자',
    role: 'individual',
    roleLabel: '개인 사용자',
    password: 'demo',
  },
]

export function homePathForRole(role: Role): string {
  if (role === 'admin') return '/admin'
  // 기관 담당자와 개인 사용자 모두 변환 작업 공간으로 진입합니다.
  return '/app'
}

interface AuthContextValue {
  user: AuthUser | null
  /** Returns the user on success, or null when credentials do not match. */
  login: (email: string, password: string) => AuthUser | null
  /** Convenience for the demo quick-login buttons. */
  loginAs: (role: Role) => AuthUser | null
  logout: () => void
}

const AuthContext = React.createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = React.useState<AuthUser | null>(null)

  const login = React.useCallback((email: string, password: string) => {
    const match = demoAccounts.find(
      (a) => a.email.toLowerCase() === email.trim().toLowerCase() && a.password === password,
    )
    if (!match) return null
    const { password: _pw, ...safe } = match
    setUser(safe)
    return safe
  }, [])

  const loginAs = React.useCallback((role: Role) => {
    const match = demoAccounts.find((a) => a.role === role)
    if (!match) return null
    const { password: _pw, ...safe } = match
    setUser(safe)
    return safe
  }, [])

  const logout = React.useCallback(() => setUser(null), [])

  const value = React.useMemo(
    () => ({ user, login, loginAs, logout }),
    [user, login, loginAs, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = React.useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
