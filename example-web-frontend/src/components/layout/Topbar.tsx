import { useEffect } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { Bot, BookOpen, Database, LogOut, MessageSquare, ShieldCheck, User } from 'lucide-react';

import { ThemeSwitch } from '../common/ThemeSwitch';
import { userApi } from '../../api/client';
import { useSettings } from '../../store/settings';
import { useSessionStore } from '../../store/session';

/** 顶部栏：品牌 + 导航 + 当前身份 + 退出 + 主题切换 */
export function Topbar() {
  const navigate = useNavigate();
  const { apiKey, currentUser, setApiKey, setCurrentUser } = useSettings();

  // 登录后刷新当前身份（获取 tenantId / name）
  useEffect(() => {
    if (apiKey) {
      userApi
        .current()
        .then(setCurrentUser)
        .catch(() => {
          /* 忽略：无效 Key 由后续请求暴露 */
        });
    }
  }, [apiKey, setCurrentUser]);

  const logout = () => {
    setApiKey('');
    setCurrentUser(null);
    useSessionStore.getState().reset();
    navigate('/login', { replace: true });
  };

  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-logo" aria-hidden="true">
          <Bot size={20} />
        </span>
        <div className="brand-text">
          <h1>example-web</h1>
          <span className="brand-tag">Web Console</span>
        </div>
      </div>

      <nav className="topnav" aria-label="功能导航">
        <NavLink to="/chat" className="nav-link">
          <MessageSquare size={16} /> 对话
        </NavLink>
        <NavLink to="/memory" className="nav-link">
          <Database size={16} /> 记忆
        </NavLink>
        <NavLink to="/rag" className="nav-link">
          <BookOpen size={16} /> 知识库
        </NavLink>
        <NavLink to="/approval" className="nav-link">
          <ShieldCheck size={16} /> 审批
        </NavLink>
      </nav>

      <div className="topbar-actions">
        {currentUser?.username ? (
          <span className="identity-chip" title="当前身份">
            <User size={14} />
            {currentUser.username}
          </span>
        ) : null}
        <button type="button" className="btn btn-ghost btn-sm" title="退出登录" onClick={logout}>
          <LogOut size={16} />
        </button>
        <ThemeSwitch />
      </div>
    </header>
  );
}
