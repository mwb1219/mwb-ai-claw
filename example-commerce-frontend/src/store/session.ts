import { create } from 'zustand';

import type { MessageDTO, SessionDTO } from '../api/types';

interface SessionState {
  sessions: SessionDTO[];
  currentSessionId: string | null;
  /** 当前会话的消息（供对话页渲染） */
  messages: MessageDTO[];
  loading: boolean;
  setSessions(sessions: SessionDTO[]): void;
  upsertSession(session: SessionDTO): void;
  removeSession(sessionId: string): void;
  selectSession(sessionId: string | null): void;
  setMessages(messages: MessageDTO[]): void;
  setLoading(loading: boolean): void;
  /** 清空当前会话状态（切换用户身份 / 退出登录时调用） */
  reset(): void;
}

export const useSessionStore = create<SessionState>((set) => ({
  sessions: [],
  currentSessionId: null,
  messages: [],
  loading: false,

  setSessions: (sessions) => set({ sessions }),
  upsertSession: (session) =>
    set((state) => {
      const exists = state.sessions.some((s) => s.sessionId === session.sessionId);
      return {
        sessions: exists
          ? state.sessions.map((s) => (s.sessionId === session.sessionId ? session : s))
          : [session, ...state.sessions],
      };
    }),
  removeSession: (sessionId) =>
    set((state) => ({
      sessions: state.sessions.filter((s) => s.sessionId !== sessionId),
      currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId,
    })),
  selectSession: (sessionId) => set({ currentSessionId: sessionId }),
  setMessages: (messages) => set({ messages }),
  setLoading: (loading) => set({ loading }),
  reset: () => set({ sessions: [], currentSessionId: null, messages: [], loading: false }),
}));
