import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bot, LogIn, UserPlus } from 'lucide-react';

import { authApi } from '../api/client';
import { useSessionStore } from '../store/session';
import { useSettings } from '../store/settings';
import { Button } from '../components/common/Button';

/** 登录 / 注册页：单租户用户体系入口 */
export function LoginPage() {
  const navigate = useNavigate();
  const { setApiKey, setCurrentUser } = useSettings();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    if (!username.trim() || !password) {
      setError('请输入用户名与密码');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const user =
        mode === 'login'
          ? await authApi.login({ username: username.trim(), password })
          : await authApi.register({ username: username.trim(), password, name: name.trim() });
      setApiKey(user.apiKey);
      setCurrentUser({
        username: user.username,
        name: user.name,
        tenantId: '',
        authEnabled: true,
      });
      useSessionStore.getState().reset();
      navigate('/chat', { replace: true });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="brand-logo" aria-hidden="true">
            <Bot size={22} />
          </span>
          <div className="brand-text">
            <h1>example-web</h1>
            <span className="brand-tag">用户登录</span>
          </div>
        </div>

        <div className="login-tabs">
          <button
            type="button"
            className={`login-tab${mode === 'login' ? ' active' : ''}`}
            onClick={() => {
              setMode('login');
              setError('');
            }}
          >
            <LogIn size={14} /> 登录
          </button>
          <button
            type="button"
            className={`login-tab${mode === 'register' ? ' active' : ''}`}
            onClick={() => {
              setMode('register');
              setError('');
            }}
          >
            <UserPlus size={14} /> 注册
          </button>
        </div>

        <form
          className="login-form"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          {mode === 'register' ? (
            <label>
              显示名（可留空）
              <input
                value={name}
                placeholder="昵称"
                spellCheck={false}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
          ) : null}
          <label>
            用户名
            <input
              value={username}
              placeholder="唯一登录名"
              autoComplete="username"
              spellCheck={false}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label>
            密码
            <input
              value={password}
              type="password"
              placeholder="密码"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              spellCheck={false}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>

          {error ? <div className="alert alert-error">{error}</div> : null}

          <Button type="submit" variant="primary" disabled={loading}>
            {loading ? '处理中…' : mode === 'login' ? '登录' : '注册并登录'}
          </Button>
        </form>
      </div>
    </div>
  );
}
