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
        <Route element={<Layout />}>
          <Route path="/" element={<LiveDataGate><DashboardPage /></LiveDataGate>} />
          <Route path="/usage" element={<LiveDataGate><UsageExplorerPage /></LiveDataGate>} />
          <Route path="/projects" element={<LiveDataGate><ProjectsPage /></LiveDataGate>} />
          <Route path="/models" element={<LiveDataGate><ModelsPage /></LiveDataGate>} />
          <Route path="/alerts" element={<LiveDataGate><AlertsPage /></LiveDataGate>} />
          <Route path="/recommendations" element={<LiveDataGate><RecommendationsPage /></LiveDataGate>} />
        </Route>
      </Routes>
    </HashRouter>
  );
}
