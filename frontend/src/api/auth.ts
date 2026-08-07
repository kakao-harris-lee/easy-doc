/** 인증 엔드포인트 (app/api/auth.py). */

import { requestJson } from './client'
import type { CredentialsRequest, TokenResponse, UserResponse } from './types'

/** POST /auth/signup — 계정을 만든다. 토큰은 발급되지 않는다(가입 후 로그인). */
export function signup(credentials: CredentialsRequest): Promise<UserResponse> {
  return requestJson<UserResponse>('/auth/signup', {
    method: 'POST',
    body: credentials,
    auth: false,
  })
}

/** POST /auth/login — 자격증명을 확인하고 액세스 토큰을 받는다. */
export function login(credentials: CredentialsRequest): Promise<TokenResponse> {
  return requestJson<TokenResponse>('/auth/login', {
    method: 'POST',
    body: credentials,
    auth: false,
  })
}

/** GET /auth/me — 저장된 토큰이 가리키는 사용자를 조회한다. */
export function fetchMe(signal?: AbortSignal): Promise<UserResponse> {
  return requestJson<UserResponse>('/auth/me', { signal })
}
