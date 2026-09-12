import { Route, Routes } from 'react-router-dom'

import { AccountSettingsPage } from '../pages/AccountSettingsPage'
import { AdminPage } from '../pages/AdminPage'
import { ConversionPage } from '../pages/ConversionPage'
import { EmailVerificationPage } from '../pages/EmailVerificationPage'
import { GuidePage } from '../pages/GuidePage'
import { HistoryPage } from '../pages/HistoryPage'
import { LoginPage } from '../pages/LoginPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { OAuthCallbackPage } from '../pages/OAuthCallbackPage'
import { OAuthLinkCallbackPage } from '../pages/OAuthLinkCallbackPage'
import { PrivacyPolicyPage } from '../pages/PrivacyPolicyPage'
import { ResetPasswordPage } from '../pages/ResetPasswordPage'
import { SignupPage } from '../pages/SignupPage'
import { TermsPage } from '../pages/TermsPage'
import { UploadPage } from '../pages/UploadPage'
import { UsagePage } from '../pages/UsagePage'
import { RequireAdmin } from './RequireAdmin'
import { RequireAuth } from './RequireAuth'
import {
  ACCOUNT_SETTINGS_PATH,
  ADMIN_PATH,
  CONVERSION_PATH,
  EMAIL_VERIFICATION_PATH,
  GUIDE_PATH,
  HISTORY_PATH,
  HOME_PATH,
  LOGIN_PATH,
  OAUTH_CALLBACK_PATH,
  OAUTH_LINK_CALLBACK_PATH,
  PRIVACY_PATH,
  RESET_PASSWORD_PATH,
  SIGNUP_PATH,
  TERMS_PATH,
  USAGE_PATH,
} from './paths'

/** 라우팅 표. 테스트에서 임의의 라우터로 감쌀 수 있도록 App과 분리한다. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path={LOGIN_PATH} element={<LoginPage />} />
      <Route path={SIGNUP_PATH} element={<SignupPage />} />
      <Route path={RESET_PASSWORD_PATH} element={<ResetPasswordPage />} />
      {/* 로그인 없이 열린다 — 가입 전에도 약관·방침을 읽을 수 있어야 한다(P0-13). */}
      <Route path={TERMS_PATH} element={<TermsPage />} />
      <Route path={PRIVACY_PATH} element={<PrivacyPolicyPage />} />
      <Route path={GUIDE_PATH} element={<GuidePage />} />
      <Route path={OAUTH_CALLBACK_PATH} element={<OAuthCallbackPage />} />
      <Route
        path={OAUTH_LINK_CALLBACK_PATH}
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
        path={USAGE_PATH}
        element={
          <RequireAuth>
            <UsagePage />
          </RequireAuth>
        }
      />
      <Route
        path={ACCOUNT_SETTINGS_PATH}
        element={
          <RequireAuth>
            <AccountSettingsPage />
          </RequireAuth>
        }
      />
      <Route
        path={ADMIN_PATH}
        element={
          <RequireAuth>
            <RequireAdmin>
              <AdminPage />
            </RequireAdmin>
          </RequireAuth>
        }
      />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
