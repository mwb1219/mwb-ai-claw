import { useEffect, useState } from 'react';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';

import { ApiError, userApi } from './api/client';
import { Topbar } from './components/layout/Topbar';
import { Sidebar } from './components/layout/Sidebar';
import { StatusBar } from './components/layout/StatusBar';
import { ChatPage } from './pages/ChatPage';
import { MemoryPage } from './pages/MemoryPage';
import { RagPage } from './pages/RagPage';
import { ApprovalPage } from './pages/ApprovalPage';
import { LoginPage } from './pages/LoginPage';
import { applyTheme, useSettings } from './store/settings';

/** 认证错误判定：后端认证失败（errCode）或 HTTP 401/403 */
function isAuthError(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    (err.errCode === 'B_AGENT_AUTH_FAILED' || err.errCode === 'UNAUTHORIZED')
  );
}

/** 登录保护：进入主界面前校验登录态，未登录（含 Key 失效）重定向到登录页 */
function RequireAuth({ children }: { children: React.ReactElement }) {
  const apiKey = useSettings((s) => s.apiKey);
  const setApiKey = useSettings((s) => s.setApiKey);
  const setCurrentUser = useSettings((s) => s.setCurrentUser);
  const [checking, setChecking] = useState(() => !!apiKey);

  useEffect(() => {
    if (!apiKey) {
      setCurrentUser(null);
      setChecking(false);
      return;
    }
    let cancelled = false;
    setChecking(true);
    userApi
      .current()
      .then((user) => {
        if (!cancelled) setCurrentUser(user);
      })
      .catch((err: unknown) => {
        // 仅当凭证确实无效时登出；网络故障（后端暂不可达）不清除 Key，避免误登出
        if (!cancelled && isAuthError(err)) {
          setApiKey('');
          setCurrentUser(null);
        }
      })
      .finally(() => {
        if (!cancelled) setChecking(false);
      });
    return () => {
      cancelled = true;
    };
  }, [apiKey, setApiKey, setCurrentUser]);

  if (checking) {
    return (
      <div className="auth-loading">
        <span className="auth-loading-dots">
          <span />
          <span />
          <span />
        </span>
        校验登录状态…
      </div>
    );
  }
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
            <Route path="/rag" element={<RagPage />} />
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
