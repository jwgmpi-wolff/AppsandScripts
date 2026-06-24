import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { LiveDataProvider } from './data/LiveDataContext.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <LiveDataProvider>
      <App />
    </LiveDataProvider>
  </StrictMode>,
)
