import { HashRouter, Routes, Route } from 'react-router-dom';
import { Layout } from './components/Layout';
import { DashboardPage } from './pages/DashboardPage';
import { UsageExplorerPage } from './pages/UsageExplorerPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { ModelsPage } from './pages/ModelsPage';
import { AlertsPage } from './pages/AlertsPage';
import { RecommendationsPage } from './pages/RecommendationsPage';
import { LiveDataGate } from './components/LiveDataGate';

export default function App() {
  return (
    <HashRouter>
      <Routes>
        <Route element={<LiveDataGate><Layout /></LiveDataGate>}>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/usage" element={<UsageExplorerPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/models" element={<ModelsPage />} />
          <Route path="/alerts" element={<AlertsPage />} />
          <Route path="/recommendations" element={<RecommendationsPage />} />
        </Route>
      </Routes>
    </HashRouter>
  );
}
