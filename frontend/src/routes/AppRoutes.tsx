import { Link, Route, Routes } from 'react-router-dom'

import { ConversionPage } from '../pages/ConversionPage'
import { EmailVerificationPage } from '../pages/EmailVerificationPage'
import { HistoryPage } from '../pages/HistoryPage'
import { LoginPage } from '../pages/LoginPage'
import { OAuthCallbackPage } from '../pages/OAuthCallbackPage'
import { OAuthLinkCallbackPage } from '../pages/OAuthLinkCallbackPage'
import { SignupPage } from '../pages/SignupPage'
import { UploadPage } from '../pages/UploadPage'
import { RequireAuth } from './RequireAuth'
import {
  CONVERSION_PATH,
  EMAIL_VERIFICATION_PATH,
  HISTORY_PATH,
  HOME_PATH,
  LOGIN_PATH,
  OAUTH_GOOGLE_CALLBACK_PATH,
  OAUTH_GOOGLE_LINK_CALLBACK_PATH,
  SIGNUP_PATH,
} from './paths'

/** 라우팅 표. 테스트에서 임의의 라우터로 감쌀 수 있도록 App과 분리한다. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path={LOGIN_PATH} element={<LoginPage />} />
      <Route path={SIGNUP_PATH} element={<SignupPage />} />
      <Route path={OAUTH_GOOGLE_CALLBACK_PATH} element={<OAuthCallbackPage />} />
      <Route
        path={OAUTH_GOOGLE_LINK_CALLBACK_PATH}
        element={
          <RequireAuth>
            <OAuthLinkCallbackPage />
          </RequireAuth>
        }
      />
      <Route
        path={EMAIL_VERIFICATION_PATH}
        element={
          <RequireAuth>
            <EmailVerificationPage />
          </RequireAuth>
        }
      />
      <Route
        path={HOME_PATH}
        element={
          <RequireAuth>
            <UploadPage />
          </RequireAuth>
        }
      />
      <Route
        path={CONVERSION_PATH}
        element={
          <RequireAuth>
            <ConversionPage />
          </RequireAuth>
        }
      />
      <Route
        path={HISTORY_PATH}
        element={
          <RequireAuth>
            <HistoryPage />
          </RequireAuth>
        }
      />
      <Route
        path="*"
        element={
          <section aria-labelledby="not-found-heading">
            {/* 다른 화면과 같이 본문의 첫 제목은 h1이다(§11) — 주소를 잘못 친 사람도
                낭독기 목차로 "여기가 어디인지"를 물을 수 있어야 한다. */}
            <h1 id="not-found-heading">찾을 수 없는 화면입니다</h1>
            <p>
              주소를 다시 확인해 주세요. <Link to={HOME_PATH}>홈으로 가기</Link>
            </p>
          </section>
        }
      />
    </Routes>
  )
}
