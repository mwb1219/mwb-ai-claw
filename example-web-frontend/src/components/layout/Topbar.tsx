import { useEffect, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { Bot, Database, LogOut, MessageSquare, ShieldCheck, User } from 'lucide-react';

import { ThemeSwitch } from '../common/ThemeSwitch';
import { userApi } from '../../api/client';
import { useSettings } from '../../store/settings';
import { useSessionStore } from '../../store/session';

/** 顶部栏：品牌 + 导航 + 连接配置（后端地址 / AgentId / APIKey）+ 当前身份 + 退出 + 主题切换 */
export function Topbar() {
  const navigate = useNavigate();
  const { baseUrl, agentId, apiKey, currentUser, setBaseUrl, setAgentId, setApiKey, setCurrentUser } =
    useSettings();
  const [showConfig, setShowConfig] = useState(false);

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
        <button
          type="button"
          className="btn btn-default btn-sm config-toggle"
          onClick={() => setShowConfig((v) => !v)}
          title="连接配置"
        >
          连接
        </button>
        <button type="button" className="btn btn-ghost btn-sm" title="退出登录" onClick={logout}>
          <LogOut size={16} />
        </button>
        <ThemeSwitch />
      </div>

      {showConfig ? (
        <div className="conn-config">
          <label>
            后端地址
            <input
              value={baseUrl}
              placeholder="http://localhost:8080（留空 = 相对路径 / 代理）"
              spellCheck={false}
              onChange={(e) => setBaseUrl(e.target.value)}
            />
          </label>
          <label>
            Agent ID
            <input
              value={agentId}
              placeholder="留空使用默认 Agent"
              spellCheck={false}
              onChange={(e) => setAgentId(e.target.value)}
            />
          </label>
          <label>
            API Key
            <input
              value={apiKey}
              type="password"
              placeholder="认证开启时必填"
              spellCheck={false}
              onChange={(e) => setApiKey(e.target.value)}
            />
          </label>
        </div>
      ) : null}
    </header>
  );
}
