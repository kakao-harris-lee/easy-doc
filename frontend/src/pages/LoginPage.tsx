import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { CredentialsForm } from '../components/CredentialsForm'
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
    <section className="auth-page" aria-labelledby="login-heading">
      <h2 id="login-heading">로그인</h2>
      <CredentialsForm
        submitLabel="로그인"
        passwordAutoComplete="current-password"
        onSubmit={async (email, password) => {
          await signIn(email, password)
          navigate(from, { replace: true })
        }}
      />
      <p>
        아직 계정이 없으신가요? <Link to="/signup">가입하기</Link>
      </p>
    </section>
  )
}
