'use client'

import * as React from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { ToastProvider } from './ui/toast'
import { AuthProvider, useAuth, type Role } from './lib/auth'

import { UserShell } from './shells/user-shell'
import { AdminShell } from './shells/admin-shell'
import { ShowcaseShell } from './shells/showcase-shell'

import { LandingPage } from './pages/landing'
import { LoginPage } from './pages/login'
import { Showcase } from './pages/showcase'

import { NewConversionPage } from './pages/user/new-conversion'
import { HistoryPage } from './pages/user/history'
import { BillingPage } from './pages/user/billing'
import { TeamPage } from './pages/user/team'

import { AdminDashboard } from './pages/admin/dashboard'
import { AdminCustomers } from './pages/admin/customers'
import { AdminSubscriptions } from './pages/admin/subscriptions'
import { AdminUsage } from './pages/admin/usage'
import { AdminConversions } from './pages/admin/conversions'
import { AdminAdjustments } from './pages/admin/adjustments'
import { AdminNotices } from './pages/admin/notices'
import { AdminAudit } from './pages/admin/audit'

/**
 * Guards a route: requires a logged-in user, and optionally restricts to
 * specific roles. Unauthenticated users are sent to /login (remembering the
 * target), and users lacking the role are sent to their own home.
 */
function Protected({
  allow,
  children,
}: {
  allow?: Role[]
  children: React.ReactElement
}) {
  const { user } = useAuth()
  const location = useLocation()

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (allow && !allow.includes(user.role)) {
    return <Navigate to="/app" replace />
  }
  return children
}

function shell(Shell: React.ComponentType<{ children: React.ReactNode }>, Page: React.ComponentType) {
  return (
    <Shell>
      <Page />
    </Shell>
  )
}

export function AppRouter() {
  return (
    <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<LandingPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/showcase" element={shell(ShowcaseShell, Showcase)} />

            {/* User-facing application — any signed-in role */}
            <Route path="/app" element={<Protected>{shell(UserShell, NewConversionPage)}</Protected>} />
            <Route path="/app/history" element={<Protected>{shell(UserShell, HistoryPage)}</Protected>} />
            <Route path="/app/billing" element={<Protected>{shell(UserShell, BillingPage)}</Protected>} />
            {/* Team management — 기관 담당자 전용 (admin 도 접근 가능) */}
            <Route
              path="/app/team"
              element={<Protected allow={['org', 'admin']}>{shell(UserShell, TeamPage)}</Protected>}
            />

            {/* Internal admin console — 플랫폼 운영자 전용 */}
            <Route path="/admin" element={<Protected allow={['admin']}>{shell(AdminShell, AdminDashboard)}</Protected>} />
            <Route path="/admin/customers" element={<Protected allow={['admin']}>{shell(AdminShell, AdminCustomers)}</Protected>} />
            <Route path="/admin/subscriptions" element={<Protected allow={['admin']}>{shell(AdminShell, AdminSubscriptions)}</Protected>} />
            <Route path="/admin/usage" element={<Protected allow={['admin']}>{shell(AdminShell, AdminUsage)}</Protected>} />
            <Route path="/admin/conversions" element={<Protected allow={['admin']}>{shell(AdminShell, AdminConversions)}</Protected>} />
            <Route path="/admin/adjustments" element={<Protected allow={['admin']}>{shell(AdminShell, AdminAdjustments)}</Protected>} />
            <Route path="/admin/notices" element={<Protected allow={['admin']}>{shell(AdminShell, AdminNotices)}</Protected>} />
            <Route path="/admin/audit" element={<Protected allow={['admin']}>{shell(AdminShell, AdminAudit)}</Protected>} />

            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </AuthProvider>
  )
}
