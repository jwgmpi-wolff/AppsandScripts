import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { Layout } from './components/Layout';
import { DashboardPage } from './pages/DashboardPage';
import { UsageExplorerPage } from './pages/UsageExplorerPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { ModelsPage } from './pages/ModelsPage';
import { AlertsPage } from './pages/AlertsPage';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/usage" element={<UsageExplorerPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/models" element={<ModelsPage />} />
          <Route path="/alerts" element={<AlertsPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
