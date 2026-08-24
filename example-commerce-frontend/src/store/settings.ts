import { create } from 'zustand';
import { persist } from 'zustand/middleware';

import type { UserInfoDTO } from '../api/types';

export type ThemeMode = 'light' | 'dark';

interface SettingsState {
  /** 后端地址；空 = 相对路径（开发走 Vite 代理到 8080 / 生产同源托管） */
  baseUrl: string;
  /** Agent ID；固定空 = 使用默认 Agent */
  agentId: string;
  /** 对话编排模式：routing（SSE 流式）/ marketing / todo-delegate（REST） */
  orchestrationId: string;
  /** 店铺 API Key（REST 走 X-API-Key，SSE 走 ?apiKey=） */
  apiKey: string;
  /** 独立 RAG：当前选中的知识库列表（对话时注入知识库参考，持久化到本地） */
  knowledgeBaseIds: string[];
  theme: ThemeMode;
  /** 当前用户身份（预留，不作为登录判定） */
  currentUser: UserInfoDTO | null;
  setApiKey(apiKey: string): void;
  setOrchestrationId(orchestrationId: string): void;
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
      orchestrationId: 'routing',
      apiKey: '',
      knowledgeBaseIds: [],
      theme: resolveInitialTheme(),
      currentUser: null,
      setApiKey: (apiKey) => set({ apiKey }),
      setOrchestrationId: (orchestrationId) => set({ orchestrationId }),
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
        orchestrationId: state.orchestrationId,
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

/** 演示用店铺身份预设（与后端 CommerceTenantGateway / CommerceDataStore 对齐） */
export const STORE_PRESETS: { key: string; label: string; tenant: string; user: string; hint: string }[] = [
  { key: 'sk-store-a', label: '店铺 A', tenant: 'store-a', user: 'op-a', hint: '店长 A' },
  { key: 'sk-store-b', label: '店铺 B', tenant: 'store-b', user: 'op-b', hint: '店长 B' },
];

/** 由 API Key 反解当前身份描述（用于顶栏 / 对话页标识，演示多店铺隔离） */
export function labelForApiKey(apiKey: string): string {
  const preset = STORE_PRESETS.find((s) => s.key === apiKey);
  if (preset) return `${preset.label}（${preset.hint}）`;
  return apiKey ? '自定义身份' : '未登录';
}
