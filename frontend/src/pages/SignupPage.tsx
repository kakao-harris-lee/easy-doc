import { Link, Navigate, useNavigate } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { MIN_PASSWORD_LENGTH } from '../auth/validation'
import { AuthIntro } from '../components/AuthIntro'
import { CredentialsForm } from '../components/CredentialsForm'
import { SocialLoginButton } from '../components/SocialLoginButton'
import { EMAIL_VERIFICATION_PATH, HOME_PATH, LOGIN_PATH } from '../routes/paths'

/**
 * 가입 화면. 가입에 성공하면 이어서 로그인까지 마치고 홈으로 간다.
 *
 * 배치와 DOM 순서의 근거는 [LoginPage]와 같다 — 인증 화면은 작업 공간 맥락이 없어
 * `PageHeader` 대신 §6.1의 2단 구성을 쓴다.
 */
export function SignupPage() {
  const { status, signUp } = useAuth()
  const navigate = useNavigate()

  if (status === 'authenticated') {
    return <Navigate to={HOME_PATH} replace />
  }

  return (
    <div className="mx-auto grid w-full max-w-5xl items-center gap-8 py-2 lg:grid-cols-[minmax(0,1fr)_440px] lg:gap-12">
      <section
        className="w-full max-w-[440px] justify-self-center lg:order-2"
        aria-labelledby="signup-heading"
      >
        <div className="rounded-[16px] border border-border bg-card p-6 shadow-[0_8px_28px_rgba(35,31,70,0.06)] sm:p-8">
          <h1
            id="signup-heading"
            className="text-[28px] font-extrabold leading-9 tracking-tight text-foreground"
          >
            가입하기
          </h1>
          <p className="mt-2 text-sm leading-[22px] text-muted-foreground">
            업무용 이메일로 가입하면 기본 작업 공간이 하나 만들어집니다.
          </p>
          <CredentialsForm
            submitLabel="가입하기"
            passwordAutoComplete="new-password"
            passwordHint={`${MIN_PASSWORD_LENGTH}자 이상 입력해 주세요.`}
            onSubmit={async (email, password) => {
              await signUp(email, password)
              // 이메일·비밀번호 가입은 미인증 상태로 시작한다 — 곧장 홈이 아니라
              // 인증 화면으로 보낸다. 구글·카카오 가입은 이 경로를 타지 않는다(늘
              // 인증됨) — 네이버는 미검증으로 가입할 수 있어 같은 화면으로 보낸다
              // (`OAuthCallbackPage`, 2026-09-05 결정).
              navigate(EMAIL_VERIFICATION_PATH, { replace: true })
            }}
          />
          <div className="mt-5 flex items-center gap-3" aria-hidden="true">
            <span className="h-px flex-1 bg-border" />
            <span className="text-xs text-muted-foreground">또는</span>
            <span className="h-px flex-1 bg-border" />
          </div>
          <SocialLoginButton provider="google" />
          <SocialLoginButton provider="kakao" />
          <SocialLoginButton provider="naver" />
          <p className="mt-5 text-center text-sm text-muted-foreground">
            이미 계정이 있으신가요? <Link to={LOGIN_PATH}>로그인</Link>
          </p>
        </div>
      </section>
      <AuthIntro
        headingId="signup-intro-heading"
        summary="어려운 공공 안내문을 쉬운 우리말 초안으로 바꾸고, 담당자가 검수해 문서로 내려받습니다."
      />
    </div>
  )
}
