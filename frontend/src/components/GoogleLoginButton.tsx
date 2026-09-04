import { useState } from 'react'

import { oauthStart } from '../api/auth'
import { ApiError } from '../api/client'
import { OAUTH_GOOGLE_CALLBACK_PATH } from '../routes/paths'
import { Button } from './ui/Button'

/**
 * `sessionStorage` 키. `easydoc.` 접두사로 다른 값과 이름이 겹치지 않게 한다.
 *
 * `OAuthCallbackPage`가 같은 키를 읽는다 — 여기서 이름을 바꾸면 그쪽도 같이 바꿔야 한다.
 */
export const OAUTH_STATE_STORAGE_KEY = 'easydoc.oauth.google.state'
export const OAUTH_REDIRECT_URI_STORAGE_KEY = 'easydoc.oauth.google.redirect_uri'

/** 시작 요청 자체가 실패했을 때(네트워크 등) 보여줄 문구. 서버 문구가 있으면 그것을 우선한다. */
const GENERIC_START_ERROR_MESSAGE = '구글 로그인을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 콜백에서 되돌아올 SPA 라우트. */
function googleRedirectUri(): string {
  return `${window.location.origin}${OAUTH_GOOGLE_CALLBACK_PATH}`
}

/**
 * "Google로 계속하기" 버튼. 로그인·가입 화면 공용이다 — 소셜 로그인은 두 화면에서 결과가
 * 똑같이 `TokenResponse`이므로(로그인이든 첫 연결이든) 같은 시작 흐름을 그대로 쓴다.
 *
 * 시작 요청이 422(제공자 미설정 등)로 실패해도 이메일 폼은 그대로 써야 하므로, 이 버튼
 * 아래에만 오류를 남기고 `CredentialsForm`의 오류 상태는 건드리지 않는다.
 */
export function GoogleLoginButton() {
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)

  async function handleClick() {
    setError(null)
    setStarting(true)
    const redirectUri = googleRedirectUri()
    try {
      const { authorization_url: authorizationUrl, state } = await oauthStart('google', redirectUri)
      try {
        window.sessionStorage.setItem(OAUTH_STATE_STORAGE_KEY, state)
        window.sessionStorage.setItem(OAUTH_REDIRECT_URI_STORAGE_KEY, redirectUri)
      } catch {
        // 세션 저장소를 쓸 수 없으면(사생활 보호 모드 등) 콜백에서 state 비교가 실패해
        // 안내가 뜬다 — 여기서 시작 자체를 막을 이유는 아니다.
      }
      window.location.assign(authorizationUrl)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : GENERIC_START_ERROR_MESSAGE)
      setStarting(false)
    }
  }

  return (
    <div className="mt-4">
      <Button
        type="button"
        variant="outline"
        className="h-11"
        fullWidth
        loading={starting}
        onClick={handleClick}
      >
        <svg aria-hidden="true" width="18" height="18" viewBox="0 0 18 18" className="shrink-0">
          <path
            fill="#4285F4"
            d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.9c1.7-1.57 2.7-3.87 2.7-6.62Z"
          />
          <path
            fill="#34A853"
            d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.9-2.26c-.8.54-1.84.86-3.06.86-2.35 0-4.34-1.59-5.05-3.72H.96v2.33A9 9 0 0 0 9 18Z"
          />
          <path
            fill="#FBBC05"
            d="M3.95 10.7A5.4 5.4 0 0 1 3.67 9c0-.59.1-1.17.28-1.7V4.97H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.03l2.99-2.33Z"
          />
          <path
            fill="#EA4335"
            d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.97l2.99 2.33C4.66 5.17 6.65 3.58 9 3.58Z"
          />
        </svg>
        Google로 계속하기
      </Button>
      {error !== null && (
        <p className="form-error mt-2" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
