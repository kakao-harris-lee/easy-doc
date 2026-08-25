import { BrowserRouter } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { AppLayout } from './components/AppLayout'
import { FluentThemeProvider } from './components/FluentThemeProvider'
import { AppRoutes } from './routes/AppRoutes'
import { WorkspaceProvider } from './workspace/WorkspaceProvider'

export default function App() {
  return (
    <FluentThemeProvider>
      {/* 작업 공간은 로그인한 뒤에만 읽을 수 있으므로 인증 제공자 안쪽에 둔다. */}
      <AuthProvider>
        <WorkspaceProvider>
          <BrowserRouter>
            <AppLayout>
              <AppRoutes />
            </AppLayout>
          </BrowserRouter>
        </WorkspaceProvider>
      </AuthProvider>
    </FluentThemeProvider>
  )
}
