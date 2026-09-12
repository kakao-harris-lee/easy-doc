import { useAuth } from '../auth/context'
import { LandingPage } from './LandingPage'
import { UploadPage } from './UploadPage'

/**
 * `/` 한 주소의 두 화면.
 *
 * 로그인한 사람에게는 새 변환(업로드)이 홈이다. 로그인 전에는 가치 제안 랜딩을 보여
 * 가입 전에 ChatGPT·Gemini와의 차이를 읽을 수 있게 한다 — `/`를 가드로 가리면 그
 * 설명이 로그인 화면 한 줄로 줄어든다.
 */
export function HomePage() {
  const { status } = useAuth()

  if (status === 'loading') {
    return (
      <p className="route-status" role="status">
        로그인 상태를 확인하는 중입니다…
      </p>
    )
  }

  if (status === 'authenticated') {
    return <UploadPage />
  }

  return <LandingPage />
}
