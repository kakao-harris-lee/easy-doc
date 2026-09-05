import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'

import { oauthLinkCallback } from '../api/auth'
import { ApiError } from '../api/client'
import type { OAuthProvider } from '../api/types'
import { useAuth } from '../auth/context'
import {
  isOAuthProvider,
  oauthLinkRedirectUriStorageKey,
  oauthLinkStateStorageKey,
  PROVIDER_DISPLAY_NAME,
} from '../auth/socialLogin'
import { HOME_PATH, type HomeNoticeState } from '../routes/paths'
import { NotFoundPage } from './NotFoundPage'

/** `state`가 없거나, 저장해 둔 값과 다르거나, 시작하지 않고 이 화면에 들어온 경우. */
const STATE_MISMATCH_MESSAGE = '요청이 만료되었거나 이미 사용되었습니다. 다시 시도해 주세요.'

/** 서버 오류를 해석하지 못했을 때(네트워크 등)만 쓰는 문구 — 서버 문구가 있으면 그것을 우선한다. */
const GENERIC_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 다시 시도해 주세요.'

type ViewState = { kind: 'processing' } | { kind: 'error'; message: string }

type ParsedCallback =
  | { kind: 'cancelled' }
  | { kind: 'invalid' }
  | { kind: 'ready'; code: string; state: string; redirectUri: string }

/**
 * 주소의 쿼리와 저장해 둔 세션 값을 맞춰 본다. `OAuthCallbackPage.parseCallback`과 같은
 * 모양이지만 연결 전용 세션 키를 읽는다 — 로그인 흐름이 동시에 진행 중이어도 서로의
 * state를 건드리지 않는다. 이 화면도 세션 값을 한 번만 소비하고 다시 쓰지 않으므로,
 * 읽자마자 지운다(성공이든 실패든 재사용 불가).
 */
function parseCallback(search: string, provider: OAuthProvider): ParsedCallback {
  const params = new URLSearchParams(search)

  let storedState: string | null = null
  let storedRedirectUri: string | null = null
  try {
    storedState = window.sessionStorage.getItem(oauthLinkStateStorageKey(provider))
    storedRedirectUri = window.sessionStorage.getItem(oauthLinkRedirectUriStorageKey(provider))
    window.sessionStorage.removeItem(oauthLinkStateStorageKey(provider))
    window.sessionStorage.removeItem(oauthLinkRedirectUriStorageKey(provider))
  } catch {
    // 세션 저장소를 못 쓰면(사생활 보호 모드 등) storedState가 null로 남아 아래에서
    // "만료되었거나 이미 사용되었습니다"로 처리된다.
  }

  if (params.get('error') !== null) {
    return { kind: 'cancelled' }
  }

  const code = params.get('code')
  const returnedState = params.get('state')

  if (
    code === null ||
    returnedState === null ||
    storedState === null ||
    storedRedirectUri === null ||
    returnedState !== storedState
  ) {
    return { kind: 'invalid' }
  }

  return { kind: 'ready', code, state: returnedState, redirectUri: storedRedirectUri }
}

/**
 * 소셜 계정 연결 콜백 화면 (`/auth/:provider/link/callback`).
 *
 * `SocialLinkStatus`(계정 메뉴의 "OO 계정 연결" 버튼) 또는 `LoginPage`(로그인 콜백의
 * 409 뒤 로그인 성공 직후)가 시작한 흐름을 여기서 마무리한다. `RequireAuth`가
 * 감싸므로(routes/AppRoutes.tsx) Bearer 없는 접근은 이 화면에 오기 전에 로그인
 * 화면으로 걸러진다.
 *
 * `:provider`가 지원 목록 밖이면 `NotFoundPage`를 그린다 — `OAuthCallbackPage`와 같은
 * 원칙이고, 판정은 다른 훅보다 먼저 return 하지 않는다(Hooks 호출 순서 유지).
 *
 * 로그인 콜백과 갈리는 지점: 성공 응답이 새 토큰이 아니라 204(본문 없음)다 — 이미
 * 인증된 사용자의 계정에 신원만 잇는다. 성공하면 `refreshMe`로 `identities`를
 * 최신화한 뒤 홈으로 돌아가고, 한 번만 보여줄 안내를 라우터 state에 싣는다(진짜
 * "한 번"인 이유: 라우터 state는 새로고침하면 사라진다).
 *
 * 400(state 불일치)·401(토큰 무효·코드 거절)·409(다른 계정에 이미 연결됨 등)·
 * 502(제공자 불통)를 한 화면으로 묶는다 — 넷 다 "서버가 준 사유를 그대로 보여주고
 * 홈으로 돌아갈 길을 남긴다"는 같은 처리이므로 갈래를 더 나눌 이유가 없다.
 */
export function OAuthLinkCallbackPage() {
  const { provider: providerParam } = useParams<{ provider: string }>()
  const providerValid = isOAuthProvider(providerParam)
  const provider: OAuthProvider = isOAuthProvider(providerParam) ? providerParam : 'google'
  const providerName = PROVIDER_DISPLAY_NAME[provider]
  const { refreshMe } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [parsed] = useState<ParsedCallback>(() =>
    providerValid ? parseCallback(location.search, provider) : { kind: 'invalid' },
  )
  const [view, setView] = useState<ViewState>(() => {
    if (parsed.kind === 'cancelled') {
      return { kind: 'error', message: `${providerName} 계정 연결을 취소했습니다.` }
    }
    if (parsed.kind === 'invalid') {
      return { kind: 'error', message: STATE_MISMATCH_MESSAGE }
    }
    return { kind: 'processing' }
  })
  // 개발 모드의 StrictMode 이중 실행에도 콜백(state 단발 소비)이 두 번 나가지 않게 막는다.
  const startedRef = useRef(false)

  useEffect(() => {
    if (!providerValid || parsed.kind !== 'ready' || startedRef.current) {
      return
    }
    startedRef.current = true

    oauthLinkCallback(provider, {
      code: parsed.code,
      state: parsed.state,
      redirectUri: parsed.redirectUri,
    })
      .then(() => refreshMe())
      .then(() => {
        const state: HomeNoticeState = { notice: `${providerName} 계정을 연결했습니다` }
        navigate(HOME_PATH, { replace: true, state })
      })
      .catch((caught: unknown) => {
        setView({
          kind: 'error',
          message: caught instanceof ApiError ? caught.message : GENERIC_ERROR_MESSAGE,
        })
      })
  }, [provider, providerValid, providerName, parsed, refreshMe, navigate])

  if (!providerValid) {
    return <NotFoundPage />
  }

  if (view.kind === 'processing') {
    return (
      <p className="route-status" role="status">
        {providerName} 계정을 연결하는 중입니다…
      </p>
    )
  }

  return (
    <section aria-labelledby="oauth-link-callback-heading">
      <h1 id="oauth-link-callback-heading">{providerName} 계정을 연결하지 못했습니다</h1>
      <p role="alert">{view.message}</p>
      <p>
        <Link to={HOME_PATH}>홈으로 돌아가기</Link>
      </p>
    </section>
  )
}
