import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { AuthIntro } from '../components/AuthIntro'
import { CredentialsForm } from '../components/CredentialsForm'
import { GoogleLoginButton } from '../components/GoogleLoginButton'
import { HOME_PATH, SIGNUP_PATH, type FromLocationState } from '../routes/paths'

/**
 * 로그인 화면.
 *
 * `PageHeader`를 쓰지 않는다 — 그 컴포넌트는 작업 공간 맥락 라벨과 오른쪽 대표 행동을
 * 전제하는데, 인증 화면에는 아직 작업 공간이 없고 대표 행동은 폼의 제출 버튼 자체다.
 * 대신 §6.1이 정한 2단 배치(왼쪽 설명, 오른쪽 440px 인증 카드)를 따른다.
 *
 * DOM 순서는 폼이 먼저다. 모바일에서 폼을 먼저 보여야 하고(§6.1), 그래야 화면의 첫
 * 제목(h1)이 설명 영역보다 앞에 온다. 데스크톱의 좌우 배치는 `order`로만 바꾼다.
 */
export function LoginPage() {
  const { status, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  // 가드가 보내온 원래 목적지가 있으면 로그인 후 그리로 돌아간다.
  const from = (location.state as FromLocationState | null)?.from ?? HOME_PATH

  if (status === 'authenticated') {
    return <Navigate to={from} replace />
  }

  return (
    <div className="mx-auto grid w-full max-w-5xl items-center gap-8 py-2 lg:grid-cols-[minmax(0,1fr)_440px] lg:gap-12">
      <section
        className="w-full max-w-[440px] justify-self-center lg:order-2"
        aria-labelledby="login-heading"
      >
        <div className="rounded-[16px] border border-border bg-card p-6 shadow-[0_8px_28px_rgba(35,31,70,0.06)] sm:p-8">
          <h1
            id="login-heading"
            className="text-[28px] font-extrabold leading-9 tracking-tight text-foreground"
          >
            로그인
          </h1>
          <p className="mt-2 text-sm leading-[22px] text-muted-foreground">
            마지막으로 보던 작업 공간에서 문서 변환과 검수를 이어서 합니다.
          </p>
          {/* 실패 사유는 폼 맨 위에 남는 문단으로 표시된다(§6.1) — CredentialsForm이
              토스트가 아니라 화면에 유지되는 오류를 그린다. */}
          <CredentialsForm
            submitLabel="로그인"
            passwordAutoComplete="current-password"
            onSubmit={async (email, password) => {
              await signIn(email, password)
              navigate(from, { replace: true })
            }}
          />
          <div className="mt-5 flex items-center gap-3" aria-hidden="true">
            <span className="h-px flex-1 bg-border" />
            <span className="text-xs text-muted-foreground">또는</span>
            <span className="h-px flex-1 bg-border" />
          </div>
          <GoogleLoginButton />
          <p className="mt-5 text-center text-sm text-muted-foreground">
            아직 계정이 없으신가요? <Link to={SIGNUP_PATH}>가입하기</Link>
          </p>
        </div>
      </section>
      <AuthIntro
        headingId="login-intro-heading"
        summary="어려운 공공 안내문을 쉬운 우리말 초안으로 바꾸고, 담당자가 검수해 문서로 내려받습니다."
      />
    </div>
  )
}
