/** 인증 엔드포인트 (app/api/auth.py). */

import { requestJson, requestVoid } from './client'
import type {
  ConfirmEmailVerificationRequest,
  CredentialsRequest,
  OAuthCallbackRequest,
  OAuthProvider,
  OAuthStartRequest,
  OAuthStartResponse,
  TokenResponse,
  UserResponse,
} from './types'

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

/** POST /auth/oauth/{provider}/start — 소셜 로그인 인가 URL을 발급받는다. 인증 불필요. */
export function oauthStart(
  provider: OAuthProvider,
  redirectUri: string,
): Promise<OAuthStartResponse> {
  const body: OAuthStartRequest = { redirect_uri: redirectUri }
  return requestJson<OAuthStartResponse>(`/auth/oauth/${provider}/start`, {
    method: 'POST',
    body,
    auth: false,
  })
}

/**
 * POST /auth/oauth/{provider}/callback — 인가 코드를 액세스 토큰으로 바꾼다.
 *
 * 성공 응답은 `login`과 같은 `TokenResponse`다. `redirectUri`는 `oauthStart`에 보냈던
 * 값과 같아야 한다(state 단발 소비 조건, 계약 `x-social-login.state`).
 */
export function oauthCallback(
  provider: OAuthProvider,
  params: { code: string; state: string; redirectUri: string },
): Promise<TokenResponse> {
  const body: OAuthCallbackRequest = {
    code: params.code,
    state: params.state,
    redirect_uri: params.redirectUri,
  }
  return requestJson<TokenResponse>(`/auth/oauth/${provider}/callback`, {
    method: 'POST',
    body,
    auth: false,
  })
}

/**
 * POST /auth/oauth/{provider}/link/start — 인증된 사용자의 계정에 소셜 신원을 이으려고
 * 제공자 인가 URL을 발급받는다. Bearer 필요(계약 `x-social-login.explicit_linking`).
 *
 * 응답 모양은 `oauthStart`와 같다(`OAuthStartResponse`) — 갈리는 것은 서버가 `state`에
 * 호출자 id를 함께 싣는다는 점뿐이고, 그 사실은 응답 본문에 드러나지 않는다.
 */
export function oauthLinkStart(
  provider: OAuthProvider,
  redirectUri: string,
): Promise<OAuthStartResponse> {
  const body: OAuthStartRequest = { redirect_uri: redirectUri }
  return requestJson<OAuthStartResponse>(`/auth/oauth/${provider}/link/start`, {
    method: 'POST',
    body,
  })
}

/**
 * POST /auth/oauth/{provider}/link/callback — 인가 코드를 검증하고 소셜 신원을 호출자
 * 계정에 잇는다. Bearer 필요, 성공은 204(본문 없음) — 로그인과 달리 토큰을 새로
 * 발급하지 않는다(호출한 쪽이 이미 인증돼 있다).
 *
 * 같은 신원을 같은 사용자에 다시 연결해도 멱등하게 204다.
 */
export function oauthLinkCallback(
  provider: OAuthProvider,
  params: { code: string; state: string; redirectUri: string },
): Promise<void> {
  const body: OAuthCallbackRequest = {
    code: params.code,
    state: params.state,
    redirect_uri: params.redirectUri,
  }
  return requestVoid(`/auth/oauth/${provider}/link/callback`, { method: 'POST', body })
}

/**
 * POST /auth/email-verification/request — 인증 코드를 (재)발급해 로그인 이메일로 보낸다.
 *
 * 본문은 없다 — 대상 이메일은 토큰의 사용자로 고정이다. 재요청 쿨다운(60초) 안에
 * 다시 부르면 429가 오고, `ApiError.retryAfterSeconds`에 남은 초가 실린다.
 */
export function requestEmailVerification(): Promise<void> {
  return requestVoid('/auth/email-verification/request', { method: 'POST' })
}

/**
 * POST /auth/email-verification/confirm — 발급된 인증 코드를 확인한다.
 *
 * 성공(204) 이후에는 `readMe.email_verified`가 참이 된다 — 호출한 쪽이 이어서
 * 사용자 정보를 다시 읽어야 화면 상태가 맞는다.
 */
export function confirmEmailVerification(code: string): Promise<void> {
  const body: ConfirmEmailVerificationRequest = { code }
  return requestVoid('/auth/email-verification/confirm', { method: 'POST', body })
}
