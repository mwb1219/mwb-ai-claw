import { NavLink, useNavigate } from 'react-router-dom';
import { Bot, BookOpen, Database, LogOut, MessageSquare, ShieldCheck, Store } from 'lucide-react';

import { ThemeSwitch } from '../common/ThemeSwitch';
import { labelForApiKey, useSettings } from '../../store/settings';
import { useSessionStore } from '../../store/session';

/** 顶部栏：品牌 + 导航 + 当前店铺身份 + 切换/退出 + 主题切换 */
export function Topbar() {
  const navigate = useNavigate();
  const { apiKey, setApiKey } = useSettings();

  const logout = () => {
    setApiKey('');
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
          <h1>example-commerce</h1>
          <span className="brand-tag">电商营销运营助手</span>
        </div>
      </div>

      <nav className="topnav" aria-label="功能导航">
        <NavLink to="/chat" className="nav-link">
          <MessageSquare size={16} /> 对话
        </NavLink>
        <NavLink to="/rag" className="nav-link">
          <BookOpen size={16} /> 知识库
        </NavLink>
        <NavLink to="/approval" className="nav-link">
          <ShieldCheck size={16} /> 审批
        </NavLink>
        <NavLink to="/memory" className="nav-link">
          <Database size={16} /> 记忆
        </NavLink>
      </nav>

      <div className="topbar-actions">
        <span className="identity-chip" title="当前店铺身份（按 X-API-Key 区分，多店铺隔离）">
          <Store size={14} />
          {labelForApiKey(apiKey)}
        </span>
        <button type="button" className="btn btn-ghost btn-sm" title="切换店铺" onClick={logout}>
          <LogOut size={16} />
        </button>
        <ThemeSwitch />
      </div>
    </header>
  );
}
