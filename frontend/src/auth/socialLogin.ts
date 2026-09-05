/**
 * 소셜 로그인 제공자 공용 헬퍼 — 로그인 시작·명시적 계정 연결 시작, `sessionStorage` 키,
 * 콜백 라우트를 `OAuthProvider` 매개변수 하나로 일반화한다(카카오 2.13.0 신설,
 * backlog §1.4). `GoogleLoginButton`·`googleLink.ts`가 구글 하나만 보고 하드코딩하던
 * 것을 여기로 모았다 — 값 자체(구글의 키·경로 문자열)는 바뀌지 않는다: 기존 테스트가
 * 그대로 통과해야 한다(byte-identical).
 */

import { oauthLinkStart, oauthStart } from '../api/auth'
import type { OAuthProvider } from '../api/types'

/** 화면에 보여줄 제공자 이름. */
export const PROVIDER_DISPLAY_NAME: Record<OAuthProvider, string> = {
  google: '구글',
  kakao: '카카오',
}

/** "OO로 계속하기" 버튼 라벨. 구글만 영문 브랜드명을 쓴다(기존 문구 유지). */
export const PROVIDER_LOGIN_LABEL: Record<OAuthProvider, string> = {
  google: 'Google로 계속하기',
  kakao: '카카오로 계속하기',
}

const SUPPORTED_PROVIDERS: readonly OAuthProvider[] = ['google', 'kakao']

/** 라우트 `:provider` 세그먼트·`?link=` 쿼리 값이 지원하는 제공자인지 확인한다. */
export function isOAuthProvider(value: string | null | undefined): value is OAuthProvider {
  return SUPPORTED_PROVIDERS.includes(value as OAuthProvider)
}

/**
 * 로그인 시작 `sessionStorage` 키. `easydoc.` 접두사로 다른 값과 이름이 겹치지 않게
 * 한다. `OAuthCallbackPage`가 같은 키를 읽는다 — 여기서 규칙을 바꾸면 그쪽도 같이
 * 바꿔야 한다.
 */
export function oauthStateStorageKey(provider: OAuthProvider): string {
  return `easydoc.oauth.${provider}.state`
}
export function oauthRedirectUriStorageKey(provider: OAuthProvider): string {
  return `easydoc.oauth.${provider}.redirect_uri`
}

/**
 * 명시적 계정 연결(backlog §1.4) 시작 `sessionStorage` 키. 로그인 흐름과 접두사를
 * 나누는 이유는 이미 로그인한 사용자가 자기 계정에 신원을 잇는 것이고(Bearer 필요),
 * 두 흐름이 동시에 진행 중이어도 서로의 state를 덮어쓰면 안 되기 때문이다.
 * `OAuthLinkCallbackPage`가 같은 키를 읽는다.
 */
export function oauthLinkStateStorageKey(provider: OAuthProvider): string {
  return `easydoc.oauth.${provider}.link.state`
}
export function oauthLinkRedirectUriStorageKey(provider: OAuthProvider): string {
  return `easydoc.oauth.${provider}.link.redirect_uri`
}

/** 로그인 콜백에서 되돌아올 SPA 라우트. */
export function oauthCallbackPath(provider: OAuthProvider): string {
  return `/auth/${provider}/callback`
}

/** 계정 연결 콜백에서 되돌아올 SPA 라우트. */
export function oauthLinkCallbackPath(provider: OAuthProvider): string {
  return `/auth/${provider}/link/callback`
}

function absoluteRedirectUri(path: string): string {
  return `${window.location.origin}${path}`
}

/**
 * 로그인(또는 첫 연결) 인가 URL을 발급받아 state를 저장하고 그리로 이동한다.
 *
 * 실패하면(네트워크·422 등) ApiError를 그대로 올린다 — 호출한 쪽(`SocialLoginButton`)이
 * 문구를 보여준다.
 */
export async function startSocialLogin(provider: OAuthProvider): Promise<void> {
  const redirectUri = absoluteRedirectUri(oauthCallbackPath(provider))
  const { authorization_url: authorizationUrl, state } = await oauthStart(provider, redirectUri)
  try {
    window.sessionStorage.setItem(oauthStateStorageKey(provider), state)
    window.sessionStorage.setItem(oauthRedirectUriStorageKey(provider), redirectUri)
  } catch {
    // 세션 저장소를 쓸 수 없으면(사생활 보호 모드 등) 콜백에서 state 비교가 실패해
    // 안내가 뜬다 — 여기서 시작 자체를 막을 이유는 아니다.
  }
  window.location.assign(authorizationUrl)
}

/**
 * 연결 인가 URL을 발급받아 state를 저장하고 그리로 이동한다.
 *
 * 실패하면(네트워크·422 등) ApiError를 그대로 올린다 — 호출한 쪽(`SocialLinkStatus`,
 * `LoginPage`)이 문구를 보여준다.
 */
export async function startSocialLink(provider: OAuthProvider): Promise<void> {
  const redirectUri = absoluteRedirectUri(oauthLinkCallbackPath(provider))
  const { authorization_url: authorizationUrl, state } = await oauthLinkStart(provider, redirectUri)
  try {
    window.sessionStorage.setItem(oauthLinkStateStorageKey(provider), state)
    window.sessionStorage.setItem(oauthLinkRedirectUriStorageKey(provider), redirectUri)
  } catch {
    // 세션 저장소를 못 쓰면(사생활 보호 모드 등) 콜백에서 state 비교가 실패해
    // 안내가 뜬다 — 여기서 시작 자체를 막을 이유는 아니다.
  }
  window.location.assign(authorizationUrl)
}
