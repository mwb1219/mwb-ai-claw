import { create } from 'zustand';
import { persist } from 'zustand/middleware';

import type { UserInfoDTO } from '../api/types';

export type ThemeMode = 'light' | 'dark';

interface SettingsState {
  /** 后端地址；留空 = 相对路径（开发走 Vite 代理 / 生产同源托管） */
  baseUrl: string;
  agentId: string;
  /** 认证 API Key（登录 / 注册后签发；REST 走 X-API-Key，SSE 走 ?apiKey=） */
  apiKey: string;
  theme: ThemeMode;
  /** 当前用户身份（username/name/tenantId），来自 GET /user/current */
  currentUser: UserInfoDTO | null;
  setBaseUrl(baseUrl: string): void;
  setAgentId(agentId: string): void;
  setApiKey(apiKey: string): void;
  setTheme(theme: ThemeMode): void;
  toggleTheme(): void;
  setCurrentUser(user: UserInfoDTO | null): void;
}

/** 解析初始主题：localStorage → 系统偏好 → light */
function resolveInitialTheme(): ThemeMode {
  try {
    const saved = localStorage.getItem('claw-theme');
    if (saved === 'light' || saved === 'dark') {
      return saved;
    }
  } catch {
    /* ignore */
  }
  if (
    typeof window !== 'undefined' &&
    window.matchMedia &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  ) {
    return 'dark';
  }
  return 'light';
}

export const useSettings = create<SettingsState>()(
  persist(
    (set, get) => ({
      baseUrl: '',
      agentId: '',
      apiKey: '',
      theme: resolveInitialTheme(),
      currentUser: null,
      setBaseUrl: (baseUrl) => set({ baseUrl: baseUrl.replace(/\/+$/, '') }),
      setAgentId: (agentId) => set({ agentId }),
      setApiKey: (apiKey) => set({ apiKey }),
      setTheme: (theme) => set({ theme }),
      toggleTheme: () => set({ theme: get().theme === 'light' ? 'dark' : 'light' }),
      setCurrentUser: (currentUser) => set({ currentUser }),
    }),
    {
      name: 'claw-settings',
      partialize: (state) => ({ baseUrl: state.baseUrl, agentId: state.agentId, apiKey: state.apiKey }),
    },
  ),
);

/** 应用主题到 <html data-theme>（含持久化 claw-theme） */
export function applyTheme(theme: ThemeMode) {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem('claw-theme', theme);
  } catch {
    /* ignore */
  }
}
