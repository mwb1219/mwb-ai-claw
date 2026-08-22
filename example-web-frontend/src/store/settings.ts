import { create } from 'zustand';
import { persist } from 'zustand/middleware';

import type { UserInfoDTO } from '../api/types';

export type ThemeMode = 'light' | 'dark';

interface SettingsState {
  /** 后端地址；空 = 相对路径（开发走 Vite 代理到 8080 / 生产同源托管） */
  baseUrl: string;
  /** Agent ID；固定空 = 使用默认 Agent */
  agentId: string;
  /** 认证 API Key（登录 / 注册后签发；REST 走 X-API-Key，SSE 走 ?apiKey=） */
  apiKey: string;
  /** 独立 RAG：当前选中的知识库列表（对话时注入知识库参考，持久化到本地） */
  knowledgeBaseIds: string[];
  theme: ThemeMode;
  /** 当前用户身份（username/name/tenantId），来自 GET /user/current */
  currentUser: UserInfoDTO | null;
  setApiKey(apiKey: string): void;
  setTheme(theme: ThemeMode): void;
  toggleTheme(): void;
  setCurrentUser(user: UserInfoDTO | null): void;
  setKnowledgeBaseIds(ids: string[]): void;
  addKnowledgeBase(id: string): void;
  removeKnowledgeBase(id: string): void;
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
      knowledgeBaseIds: [],
      theme: resolveInitialTheme(),
      currentUser: null,
      setApiKey: (apiKey) => set({ apiKey }),
      setTheme: (theme) => set({ theme }),
      toggleTheme: () => set({ theme: get().theme === 'light' ? 'dark' : 'light' }),
      setCurrentUser: (currentUser) => set({ currentUser }),
      setKnowledgeBaseIds: (knowledgeBaseIds) => set({ knowledgeBaseIds }),
      addKnowledgeBase: (id) => {
        const ids = get().knowledgeBaseIds;
        if (id.trim() && !ids.includes(id.trim())) {
          set({ knowledgeBaseIds: [...ids, id.trim()] });
        }
      },
      removeKnowledgeBase: (id) =>
        set({ knowledgeBaseIds: get().knowledgeBaseIds.filter((x) => x !== id) }),
    }),
    {
      name: 'claw-settings',
      partialize: (state) => ({
        apiKey: state.apiKey,
        knowledgeBaseIds: state.knowledgeBaseIds,
      }),
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
