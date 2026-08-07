import { Link, Route, Routes } from 'react-router-dom'

import { ConversionPage } from '../pages/ConversionPage'
import { HistoryPage } from '../pages/HistoryPage'
import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
import { UploadPage } from '../pages/UploadPage'
import { RequireAuth } from './RequireAuth'
import { CONVERSION_PATH, HISTORY_PATH, HOME_PATH, LOGIN_PATH, SIGNUP_PATH } from './paths'

/** 라우팅 표. 테스트에서 임의의 라우터로 감쌀 수 있도록 App과 분리한다. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path={LOGIN_PATH} element={<LoginPage />} />
      <Route path={SIGNUP_PATH} element={<SignupPage />} />
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
            <h2 id="not-found-heading">찾을 수 없는 화면입니다</h2>
            <p>
              주소를 다시 확인해 주세요. <Link to={HOME_PATH}>홈으로 가기</Link>
            </p>
          </section>
        }
      />
    </Routes>
  )
}
