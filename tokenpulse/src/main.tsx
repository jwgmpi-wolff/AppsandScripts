import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthContext.tsx'
import { LiveDataProvider } from './data/LiveDataContext.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <LiveDataProvider>
        <App />
      </LiveDataProvider>
    </AuthProvider>
  </StrictMode>,
)
