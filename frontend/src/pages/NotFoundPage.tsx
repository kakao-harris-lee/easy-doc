import { Link } from 'react-router-dom'

import { HOME_PATH } from '../routes/paths'

/**
 * 찾을 수 없는 화면 — `AppRoutes`의 catch-all과 지원하지 않는 소셜 로그인 `:provider`
 * 세그먼트(`OAuthCallbackPage`·`OAuthLinkCallbackPage`)가 함께 쓴다. 계약이 `enum` 밖
 * provider를 "예약됨"과 "완전히 모름"으로 가르지 않는 것과 같은 원칙이다 — 클라이언트가
 * 취할 조치가 같다(존재하지 않는 화면).
 */
export function NotFoundPage() {
  return (
    <section aria-labelledby="not-found-heading">
      {/* 다른 화면과 같이 본문의 첫 제목은 h1이다(§11) — 주소를 잘못 친 사람도
          낭독기 목차로 "여기가 어디인지"를 물을 수 있어야 한다. */}
      <h1 id="not-found-heading">찾을 수 없는 화면입니다</h1>
      <p>
        주소를 다시 확인해 주세요. <Link to={HOME_PATH}>홈으로 가기</Link>
      </p>
    </section>
  )
}
