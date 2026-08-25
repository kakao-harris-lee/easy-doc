import { Link, Navigate, useNavigate } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { MIN_PASSWORD_LENGTH } from '../auth/validation'
import { CredentialsForm } from '../components/CredentialsForm'
import { Badge } from '../components/ui/Badge'
import { HOME_PATH } from '../routes/paths'

/** 가입 화면. 가입에 성공하면 이어서 로그인까지 마치고 홈으로 간다. */
export function SignupPage() {
  const { status, signUp } = useAuth()
  const navigate = useNavigate()

  if (status === 'authenticated') {
    return <Navigate to={HOME_PATH} replace />
  }

  return (
    <div className="mx-auto grid min-h-[calc(100dvh-6.5rem)] w-full max-w-5xl items-center gap-10 lg:grid-cols-2">
      <section className="mx-auto w-full max-w-sm" aria-labelledby="signup-heading">
        <Badge tone="primary" withIcon={false} className="mb-2">
          계정 만들기
        </Badge>
        <h2 id="signup-heading" className="text-2xl font-extrabold tracking-tight text-foreground">
          가입하기
        </h2>
        <p className="mt-1 text-[15px] leading-relaxed text-muted-foreground">
          업무용 이메일로 나만의 안전한 작업 공간을 시작하세요.
        </p>
        <CredentialsForm
          submitLabel="가입하기"
          passwordAutoComplete="new-password"
          passwordHint={`${MIN_PASSWORD_LENGTH}자 이상 입력해 주세요.`}
          onSubmit={async (email, password) => {
            await signUp(email, password)
            navigate(HOME_PATH, { replace: true })
          }}
        />
        <p className="mt-5 text-center text-sm text-muted-foreground">
          이미 계정이 있으신가요? <Link to="/login">로그인</Link>
        </p>
      </section>
      <aside
        className="relative overflow-hidden rounded-[16px] border border-border bg-primary p-8 text-white"
        aria-label="가입 안내"
      >
        <span
          className="mb-5 flex size-12 items-center justify-center rounded-[12px] bg-white/15 text-xl font-black"
          aria-hidden="true"
        >
          문
        </span>
        <h2 className="text-2xl font-extrabold tracking-tight">
          공공 문서 업무에 맞춘
          <br />
          차분한 작업 공간
        </h2>
        <p className="mt-3 text-[15px] leading-relaxed text-white/80">
          변환 결과는 언제나 AI 초안으로 표시됩니다. 사실관계와 신청 방법을 담당자가 확인한 뒤
          사용하세요.
        </p>
        <Badge className="mt-5 border-white/25 bg-white/10 text-white" withIcon={false}>
          AI 초안 · 사람 검토 필수
        </Badge>
      </aside>
    </div>
  )
}
