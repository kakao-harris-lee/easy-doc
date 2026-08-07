import { BrowserRouter } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { AppLayout } from './components/AppLayout'
import { AppRoutes } from './routes/AppRoutes'

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppLayout>
          <AppRoutes />
        </AppLayout>
      </BrowserRouter>
    </AuthProvider>
  )
}
