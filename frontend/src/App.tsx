import { BrowserRouter } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { AppLayout } from './components/AppLayout'
import { FluentThemeProvider } from './components/FluentThemeProvider'
import { AppRoutes } from './routes/AppRoutes'
import { WorkspaceProvider } from './workspace/WorkspaceProvider'

export default function App() {
  return (
    <FluentThemeProvider>
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
