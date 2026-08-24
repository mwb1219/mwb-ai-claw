import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bot, Check, Store } from 'lucide-react';

import { STORE_PRESETS, useSettings } from '../store/settings';
import { useSessionStore } from '../store/session';
import { Button } from '../components/common/Button';

/** 店铺选择页：选择店铺 API Key 进入（多店铺 / 多租户隔离演示，无需密码） */
export function LoginPage() {
  const navigate = useNavigate();
  const setApiKey = useSettings((s) => s.setApiKey);
  const [customKey, setCustomKey] = useState('');

  const enter = (key: string) => {
    if (!key.trim()) return;
    setApiKey(key.trim());
    useSessionStore.getState().reset();
    navigate('/chat', { replace: true });
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="brand-logo" aria-hidden="true">
            <Bot size={22} />
          </span>
          <div className="brand-text">
            <h1>example-commerce</h1>
            <span className="brand-tag">电商营销运营助手 · 选择店铺进入</span>
          </div>
        </div>

        <div className="store-grid">
          {STORE_PRESETS.map((s) => (
            <button type="button" key={s.key} className="store-card" onClick={() => enter(s.key)}>
              <span className="store-icon">
                <Store size={16} />
              </span>
              <div className="store-info">
                <div className="store-name">
                  {s.label} · {s.hint}
                </div>
                <div className="store-meta">
                  tenant={s.tenant} · user={s.user}
                </div>
                <div className="store-key">{s.key}</div>
              </div>
              <Check size={16} className="store-enter" />
            </button>
          ))}
        </div>

        <div className="store-custom">
          <div className="store-custom-title">或使用自定义店铺 API Key</div>
          <div className="store-custom-row">
            <input
              value={customKey}
              placeholder="粘贴店铺 API Key"
              spellCheck={false}
              onChange={(e) => setCustomKey(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') enter(customKey);
              }}
            />
            <Button
              type="button"
              variant="primary"
              disabled={!customKey.trim()}
              onClick={() => enter(customKey)}
            >
              进入
            </Button>
          </div>
        </div>

        <p className="store-tip">
          sk-store-a / sk-store-b 对应两间店铺，数据（商品 / 订单 / 活动）互相隔离，用于演示多租户能力。
        </p>
      </div>
    </div>
  );
}