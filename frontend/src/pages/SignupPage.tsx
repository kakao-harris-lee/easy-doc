import { Link, Navigate, useNavigate } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { MIN_PASSWORD_LENGTH } from '../auth/validation'
import { CredentialsForm } from '../components/CredentialsForm'
import { HOME_PATH } from '../routes/paths'

/** 가입 화면. 가입에 성공하면 이어서 로그인까지 마치고 홈으로 간다. */
export function SignupPage() {
  const { status, signUp } = useAuth()
  const navigate = useNavigate()

  if (status === 'authenticated') {
    return <Navigate to={HOME_PATH} replace />
  }

  return (
    <section className="auth-page" aria-labelledby="signup-heading">
      <h2 id="signup-heading">가입하기</h2>
      <CredentialsForm
        submitLabel="가입하기"
        passwordAutoComplete="new-password"
        passwordHint={`${MIN_PASSWORD_LENGTH}자 이상 입력해 주세요.`}
        onSubmit={async (email, password) => {
          await signUp(email, password)
          navigate(HOME_PATH, { replace: true })
        }}
      />
      <p>
        이미 계정이 있으신가요? <Link to="/login">로그인</Link>
      </p>
    </section>
  )
}
