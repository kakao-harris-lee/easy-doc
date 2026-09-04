/**
 * 명시적 구글 계정 연결(2.10.0 신설, backlog §1.4) 시작 흐름.
 *
 * `GoogleLoginButton`(로그인 시작)과 같은 모양이지만 별개다 — 이쪽은 이미 로그인한
 * 사용자가 자기 계정에 신원을 잇는 것이고(Bearer 필요), 세션 저장소 키와 콜백 주소도
 * 로그인 흐름과 겹치면 안 된다(동시에 두 흐름이 진행 중일 때 서로의 state를 덮어쓸
 * 수 있다).
 */

import { oauthLinkStart } from '../api/auth'
import { OAUTH_GOOGLE_LINK_CALLBACK_PATH } from '../routes/paths'

/** `sessionStorage` 키. `OAuthLinkCallbackPage`가 같은 키를 읽는다. */
export const OAUTH_LINK_STATE_STORAGE_KEY = 'easydoc.oauth.google.link.state'
export const OAUTH_LINK_REDIRECT_URI_STORAGE_KEY = 'easydoc.oauth.google.link.redirect_uri'

/** 연결 콜백에서 되돌아올 SPA 라우트. */
export function googleLinkRedirectUri(): string {
  return `${window.location.origin}${OAUTH_GOOGLE_LINK_CALLBACK_PATH}`
}

/**
 * 연결 인가 URL을 발급받아 state를 저장하고 그리로 이동한다.
 *
 * 실패하면(네트워크·422 등) ApiError를 그대로 올린다 — 호출한 쪽이 문구를 보여준다.
 */
export async function startGoogleLink(): Promise<void> {
  const redirectUri = googleLinkRedirectUri()
  const { authorization_url: authorizationUrl, state } = await oauthLinkStart('google', redirectUri)
  try {
    window.sessionStorage.setItem(OAUTH_LINK_STATE_STORAGE_KEY, state)
    window.sessionStorage.setItem(OAUTH_LINK_REDIRECT_URI_STORAGE_KEY, redirectUri)
  } catch {
    // 세션 저장소를 못 쓰면(사생활 보호 모드 등) 콜백에서 state 비교가 실패해
    // 안내가 뜬다 — 여기서 시작 자체를 막을 이유는 아니다.
  }
  window.location.assign(authorizationUrl)
}
