import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { CredentialsForm } from '../components/CredentialsForm'
import { Badge } from '../components/ui/Badge'
import { HOME_PATH, type FromLocationState } from '../routes/paths'

/** 로그인 화면. */
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
    <div className="mx-auto grid min-h-[calc(100dvh-6.5rem)] w-full max-w-5xl items-center gap-10 lg:grid-cols-2">
      <section className="mx-auto w-full max-w-sm" aria-labelledby="login-heading">
        <Badge tone="primary" withIcon={false} className="mb-2">
          다시 오신 것을 환영합니다
        </Badge>
        <h2 id="login-heading" className="text-2xl font-extrabold tracking-tight text-foreground">
          로그인
        </h2>
        <p className="mt-1 text-[15px] leading-relaxed text-muted-foreground">
          계정으로 로그인하고 쉬운 우리말 문서 작업을 이어가세요.
        </p>
        <CredentialsForm
          submitLabel="로그인"
          passwordAutoComplete="current-password"
          onSubmit={async (email, password) => {
            await signIn(email, password)
            navigate(from, { replace: true })
          }}
        />
        <p className="mt-5 text-center text-sm text-muted-foreground">
          아직 계정이 없으신가요? <Link to="/signup">가입하기</Link>
        </p>
      </section>
      <aside
        className="relative overflow-hidden rounded-[16px] border border-border bg-primary p-8 text-white"
        aria-label="서비스 안내"
      >
        <span
          className="mb-5 flex size-12 items-center justify-center rounded-[12px] bg-white/15 text-xl font-black"
          aria-hidden="true"
        >
          ✓
        </span>
        <h2 className="text-2xl font-extrabold tracking-tight">
          어려운 안내문을
          <br />더 읽기 쉽게
        </h2>
        <p className="mt-3 text-[15px] leading-relaxed text-white/80">
          AI가 만든 쉬운 글 초안을 원문과 비교하고, 담당자가 직접 검수한 뒤 내려받을 수 있습니다.
        </p>
        <ul className="mt-6 flex list-disc flex-col gap-2 pl-5 text-sm text-white/80">
          <li>원문과 결과를 나란히 비교</li>
          <li>개인정보를 가린 뒤 안전하게 변환</li>
          <li>DOCX·HWPX·TXT 형식으로 내려받기</li>
        </ul>
      </aside>
    </div>
  )
}
