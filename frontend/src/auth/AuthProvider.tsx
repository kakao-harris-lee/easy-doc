import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

import { fetchMe, login, oauthCallback, signup } from '../api/auth'
import { setUnauthorizedHandler } from '../api/client'
import { clearToken, readToken, writeToken } from '../api/token'
import type { UserResponse } from '../api/types'
import { AuthContext } from './context'
import type { AuthContextValue, AuthStatus } from './context'

/** 취소된 요청인지 — 화면 전환·언마운트로 끊긴 요청은 로그아웃 사유가 아니다. */
function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

/**
 * 인증 상태를 앱 전체에 제공한다.
 *
 * 새로고침해도 로그인이 유지되어야 하므로, 시작할 때 저장된 토큰으로 `/auth/me`를
 * 한 번 확인한다. 토큰이 만료됐다면 그 401을 API 클라이언트가 받아 토큰을 버리고
 * 아래 핸들러로 알려준다 — 만료 감지 경로가 화면마다 따로 있지 않다.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  // 토큰이 아예 없으면 확인할 것도 없다 — 첫 화면에서 불필요한 로딩을 보이지 않는다.
  const [status, setStatus] = useState<AuthStatus>(() =>
    readToken() === null ? 'anonymous' : 'loading',
  )

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null)
      setStatus('anonymous')
    })
    return () => setUnauthorizedHandler(null)
  }, [])

  useEffect(() => {
    if (readToken() === null) {
      return
    }
    const controller = new AbortController()
    fetchMe(controller.signal)
      .then((me) => {
        setUser(me)
        setStatus('authenticated')
      })
      .catch((error: unknown) => {
        if (!isAbort(error)) {
          setStatus('anonymous')
        }
      })
    return () => controller.abort()
  }, [])

  /** 새로 받은 액세스 토큰을 저장하고 그 토큰의 사용자로 인증 상태를 채운다. */
  const applyToken = useCallback(async (accessToken: string) => {
    writeToken(accessToken)
    const me = await fetchMe()
    setUser(me)
    setStatus('authenticated')
  }, [])

  const signIn = useCallback(
    async (email: string, password: string) => {
      const token = await login({ email, password })
      await applyToken(token.access_token)
    },
    [applyToken],
  )

  const signUp = useCallback(
    async (email: string, password: string) => {
      await signup({ email, password })
      await signIn(email, password)
    },
    [signIn],
  )

  const signInWithGoogle = useCallback(
    async (params: { code: string; state: string; redirectUri: string }) => {
      const token = await oauthCallback('google', params)
      await applyToken(token.access_token)
    },
    [applyToken],
  )

  const signOut = useCallback(() => {
    clearToken()
    setUser(null)
    setStatus('anonymous')
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, signIn, signUp, signInWithGoogle, signOut }),
    [status, user, signIn, signUp, signInWithGoogle, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
