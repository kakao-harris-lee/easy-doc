import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import { ApiError } from '../api/client'
import { useAuth } from '../auth/context'
import {
  OAUTH_REDIRECT_URI_STORAGE_KEY,
  OAUTH_STATE_STORAGE_KEY,
} from '../components/GoogleLoginButton'
import { HOME_PATH, LOGIN_PATH } from '../routes/paths'

/** 사용자가 구글 동의 화면에서 취소했을 때(제공자가 `error=access_denied`로 되돌린다). */
const CANCELLED_MESSAGE = '구글 로그인을 취소했습니다.'

/** `state`가 없거나, 저장해 둔 값과 다르거나, 시작하지 않고 이 화면에 들어온 경우. */
const STATE_MISMATCH_MESSAGE = '요청이 만료되었거나 이미 사용되었습니다. 다시 시도해 주세요.'

/** 서버 오류를 해석하지 못했을 때(네트워크 등)만 쓰는 문구 — 서버 문구가 있으면 그것을 우선한다. */
const GENERIC_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 다시 시도해 주세요.'

type ViewState =
  | { kind: 'processing' }
  | { kind: 'linked-elsewhere'; message: string }
  | { kind: 'error'; message: string }

type ParsedCallback =
  | { kind: 'cancelled' }
  | { kind: 'invalid' }
  | { kind: 'ready'; code: string; state: string; redirectUri: string }

/**
 * 주소의 쿼리와 저장해 둔 세션 값을 맞춰 본다. 이 화면은 세션 값을 한 번만 소비하고
 * 다시 쓰지 않으므로, 읽자마자 지운다(성공이든 실패든 재사용 불가).
 */
function parseCallback(search: string): ParsedCallback {
  const params = new URLSearchParams(search)

  let storedState: string | null = null
  let storedRedirectUri: string | null = null
  try {
    storedState = window.sessionStorage.getItem(OAUTH_STATE_STORAGE_KEY)
    storedRedirectUri = window.sessionStorage.getItem(OAUTH_REDIRECT_URI_STORAGE_KEY)
    window.sessionStorage.removeItem(OAUTH_STATE_STORAGE_KEY)
    window.sessionStorage.removeItem(OAUTH_REDIRECT_URI_STORAGE_KEY)
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
 * 구글 로그인 콜백 화면 (`/auth/google/callback`).
 *
 * `GoogleLoginButton`이 시작한 흐름을 여기서 마무리한다. 화면을 그리는 동안 사용자가
 * 볼 일은 거의 없다 — 성공하면 곧장 홈으로, 실패하면 이 자리에 사유를 남긴다.
 *
 * 쿼리 해석과 세션 값 소비는 렌더 중(지연 초기값)에 한 번만 한다 — effect 안에서
 * 동기로 setState 하지 않기 위해서다. 실제 서버 호출(비동기)만 effect가 맡는다.
 */
export function OAuthCallbackPage() {
  const { signInWithGoogle } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [parsed] = useState<ParsedCallback>(() => parseCallback(location.search))
  const [view, setView] = useState<ViewState>(() => {
    if (parsed.kind === 'cancelled') {
      return { kind: 'error', message: CANCELLED_MESSAGE }
    }
    if (parsed.kind === 'invalid') {
      return { kind: 'error', message: STATE_MISMATCH_MESSAGE }
    }
    return { kind: 'processing' }
  })
  // 개발 모드의 StrictMode 이중 실행에도 콜백(state 단발 소비)이 두 번 나가지 않게 막는다.
  const startedRef = useRef(false)

  useEffect(() => {
    if (parsed.kind !== 'ready' || startedRef.current) {
      return
    }
    startedRef.current = true

    signInWithGoogle({ code: parsed.code, state: parsed.state, redirectUri: parsed.redirectUri })
      .then(() => {
        navigate(HOME_PATH, { replace: true })
      })
      .catch((caught: unknown) => {
        if (caught instanceof ApiError && caught.status === 409) {
          setView({ kind: 'linked-elsewhere', message: caught.message })
        } else {
          setView({
            kind: 'error',
            message: caught instanceof ApiError ? caught.message : GENERIC_ERROR_MESSAGE,
          })
        }
      })
  }, [parsed, signInWithGoogle, navigate])

  if (view.kind === 'processing') {
    return (
      <p className="route-status" role="status">
        구글 로그인을 처리하는 중입니다…
      </p>
    )
  }

  if (view.kind === 'linked-elsewhere') {
    return (
      <section aria-labelledby="oauth-callback-heading">
        <h1 id="oauth-callback-heading">이미 가입된 이메일입니다</h1>
        <p role="alert">{view.message}</p>
        <p>
          <Link to={LOGIN_PATH}>이메일로 로그인하기</Link>
        </p>
      </section>
    )
  }

  return (
    <section aria-labelledby="oauth-callback-heading">
      <h1 id="oauth-callback-heading">구글 로그인에 실패했습니다</h1>
      <p role="alert">{view.message}</p>
      <p>
        <Link to={LOGIN_PATH}>로그인 화면으로 돌아가기</Link>
      </p>
    </section>
  )
}
