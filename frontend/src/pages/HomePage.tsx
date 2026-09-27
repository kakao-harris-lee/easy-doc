import { useAuth } from '../auth/context'
import { LandingPage } from './LandingPage'
import { UploadPage } from './UploadPage'

/**
 * `/` 한 주소의 두 화면.
 *
 * 로그인한 사람에게는 새 변환(업로드)이 홈이다. 로그인 전에는 누구를 위한 도구인지·어떤
 * 문서를 넣는지·결과가 초안이라는 사실을 가입 전에 읽게 한다 — `/`를 가드로 가리면 그
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
