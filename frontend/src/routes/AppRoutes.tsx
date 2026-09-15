import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'

import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
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

const AccountSettingsPage = lazy(() =>
  import('../pages/AccountSettingsPage').then((module) => ({
    default: module.AccountSettingsPage,
  })),
)
const AdminPage = lazy(() =>
  import('../pages/AdminPage').then((module) => ({ default: module.AdminPage })),
)
const BillingCallbackPage = lazy(() =>
  import('../pages/BillingCallbackPage').then((module) => ({
    default: module.BillingCallbackPage,
  })),
)
const ConversionPage = lazy(() =>
  import('../pages/ConversionPage').then((module) => ({ default: module.ConversionPage })),
)
const EmailVerificationPage = lazy(() =>
  import('../pages/EmailVerificationPage').then((module) => ({
    default: module.EmailVerificationPage,
  })),
)
const GuidePage = lazy(() =>
  import('../pages/GuidePage').then((module) => ({ default: module.GuidePage })),
)
const HistoryPage = lazy(() =>
  import('../pages/HistoryPage').then((module) => ({ default: module.HistoryPage })),
)
const HomePage = lazy(() =>
  import('../pages/HomePage').then((module) => ({ default: module.HomePage })),
)
const NotFoundPage = lazy(() =>
  import('../pages/NotFoundPage').then((module) => ({ default: module.NotFoundPage })),
)
const OAuthCallbackPage = lazy(() =>
  import('../pages/OAuthCallbackPage').then((module) => ({ default: module.OAuthCallbackPage })),
)
const OAuthLinkCallbackPage = lazy(() =>
  import('../pages/OAuthLinkCallbackPage').then((module) => ({
    default: module.OAuthLinkCallbackPage,
  })),
)
const PrivacyPolicyPage = lazy(() =>
  import('../pages/PrivacyPolicyPage').then((module) => ({ default: module.PrivacyPolicyPage })),
)
const ResetPasswordPage = lazy(() =>
  import('../pages/ResetPasswordPage').then((module) => ({ default: module.ResetPasswordPage })),
)
const TermsPage = lazy(() =>
  import('../pages/TermsPage').then((module) => ({ default: module.TermsPage })),
)
const UsagePage = lazy(() =>
  import('../pages/UsagePage').then((module) => ({ default: module.UsagePage })),
)

export function AppRoutes() {
  return (
    <Suspense fallback={<p role="status">화면을 불러오는 중입니다.</p>}>
      <Routes>
        <Route
          path="/billing/callback"
          element={
            <RequireAuth>
              <BillingCallbackPage />
            </RequireAuth>
          }
        />
        <Route path={LOGIN_PATH} element={<LoginPage />} />
        <Route path={SIGNUP_PATH} element={<SignupPage />} />
        <Route path={RESET_PASSWORD_PATH} element={<ResetPasswordPage />} />
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
        <Route path={HOME_PATH} element={<HomePage />} />
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
    </Suspense>
  )
}
