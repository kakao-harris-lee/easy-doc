import { createContext, useContext } from 'react'

import type { UserResponse } from '../api/types'

/**
 * 인증 상태.
 * - `loading`: 저장된 토큰으로 사용자 정보를 확인하는 중 (가드가 판단을 미룬다)
 * - `authenticated` / `anonymous`: 확인 끝
 */
export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

export interface AuthContextValue {
  status: AuthStatus
  user: UserResponse | null
  /** 로그인. 실패하면 ApiError를 그대로 올린다(화면이 문구를 보여준다). */
  signIn: (email: string, password: string) => Promise<void>
  /** 가입 후 이어서 로그인까지 한다 — 가입 응답에는 토큰이 없다. */
  signUp: (email: string, password: string) => Promise<void>
  signOut: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

/** 인증 상태를 읽는다. AuthProvider 밖에서 부르면 즉시 오류로 알린다. */
export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (value === null) {
    throw new Error('useAuth는 AuthProvider 안에서만 사용할 수 있습니다')
  }
  return value
}
