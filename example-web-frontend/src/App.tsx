import { useEffect } from 'react';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';

import { Topbar } from './components/layout/Topbar';
import { Sidebar } from './components/layout/Sidebar';
import { StatusBar } from './components/layout/StatusBar';
import { ChatPage } from './pages/ChatPage';
import { MemoryPage } from './pages/MemoryPage';
import { ApprovalPage } from './pages/ApprovalPage';
import { LoginPage } from './pages/LoginPage';
import { applyTheme, useSettings } from './store/settings';

/** 登录保护：无 apiKey 时重定向到登录页 */
function RequireAuth({ children }: { children: React.ReactElement }) {
  const apiKey = useSettings((s) => s.apiKey);
  if (!apiKey) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

/** 主布局：登录后进入的完整控制台（顶栏 + 侧栏 + 主区 + 状态条） */
function MainLayout() {
  return (
    <div className="app">
      <Topbar />
      <div className="app-body">
        <Sidebar />
        <main className="app-main">
          <Routes>
            <Route path="/" element={<Navigate to="/chat" replace />} />
            <Route path="/chat" element={<ChatPage />} />
            <Route path="/memory" element={<MemoryPage />} />
            <Route path="/approval" element={<ApprovalPage />} />
          </Routes>
        </main>
      </div>
      <StatusBar />
    </div>
  );
}

export default function App() {
  const theme = useSettings((s) => s.theme);

  // 应用主题到 <html data-theme>（启动即应用一次）
  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  return (
    <HashRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/*"
          element={
            <RequireAuth>
              <MainLayout />
            </RequireAuth>
          }
        />
      </Routes>
    </HashRouter>
  );
}
