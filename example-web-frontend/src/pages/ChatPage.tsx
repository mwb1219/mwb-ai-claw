import { useEffect, useRef } from 'react';

import { sessionApi } from '../api/client';
import { chatStream } from '../api/sse';
import type { StreamHandle } from '../api/types';
import type { MessageDTO, SessionDTO } from '../api/types';
import { useSessionStore } from '../store/session';
import { useChatStore } from '../store/chat';
import { useSettings } from '../store/settings';
import { MessageList } from '../components/chat/MessageList';
import { Composer } from '../components/chat/Composer';
import { TraceTimeline } from '../components/chat/TraceTimeline';

function makeMessage(role: string, content: string): MessageDTO {
  return { role, content, timestamp: Date.now() };
}

/** 对话页（核心）：会话切换加载 + SSE 流式对话 + 推理轨迹/工具调用 */
export function ChatPage() {
  const currentSessionId = useSessionStore((s) => s.currentSessionId);
  const messages = useSessionStore((s) => s.messages);
  const setMessages = useSessionStore((s) => s.setMessages);
  const selectSession = useSessionStore((s) => s.selectSession);
  const upsertSession = useSessionStore((s) => s.upsertSession);
  const setSessions = useSessionStore((s) => s.setSessions);

  const busy = useChatStore((s) => s.busy);
  const setBusy = useChatStore((s) => s.setBusy);
  const setStreaming = useChatStore((s) => s.setStreaming);
  const setStreamingContent = useChatStore((s) => s.setStreamingContent);
  const appendStreamingContent = useChatStore((s) => s.appendStreamingContent);
  const addTraceStep = useChatStore((s) => s.addTraceStep);
  const clearTrace = useChatStore((s) => s.clearTrace);
  const clearToolCalls = useChatStore((s) => s.clearToolCalls);
  const startToolCall = useChatStore((s) => s.startToolCall);
  const appendToolArgs = useChatStore((s) => s.appendToolArgs);
  const setStatus = useChatStore((s) => s.setStatus);

  const agentId = useSettings((s) => s.agentId);

  const streamRef = useRef<StreamHandle | null>(null);
  /** 发送时是否处于自动创建会话（首帧 session 事件后跳过加载，保留本地消息） */
  const skipLoadRef = useRef(false);

  // 切换会话 → 加载会话详情消息
  useEffect(() => {
    if (!currentSessionId) {
      setMessages([]);
      return;
    }
    if (skipLoadRef.current) {
      skipLoadRef.current = false;
      return;
    }
    let cancelled = false;
    setBusy(true);
    sessionApi
      .get(currentSessionId)
      .then((s) => {
        if (!cancelled) setMessages(s.messages || []);
      })
      .catch((err) => setStatus(`加载会话失败：${(err as Error).message}`, 'err'))
      .finally(() => {
        if (!cancelled) setBusy(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentSessionId]);

  // 启动时加载会话列表
  useEffect(() => {
    sessionApi
      .list()
      .then(setSessions)
      .catch((err) => setStatus(`加载会话列表失败：${(err as Error).message}`, 'err'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const finishStream = () => {
    const finalContent = useChatStore.getState().streamingContent;
    useChatStore.getState().setStreaming(false);
    useChatStore.getState().setStreamingContent('');
    useChatStore.getState().setBusy(false);
    if (finalContent) {
      useSessionStore.getState().setMessages([
        ...useSessionStore.getState().messages,
        makeMessage('assistant', finalContent),
      ]);
    }
  };

  const handleSend = (message: string) => {
    const sessionIdAtSend = currentSessionId;
    // 追加用户消息
    setMessages([...messages, makeMessage('user', message)]);
    clearTrace();
    clearToolCalls();
    setBusy(true);
    setStreaming(true);
    setStreamingContent('');
    setStatus('正在与 Agent 通信…', 'busy');

    streamRef.current = chatStream(
      { message, sessionId: sessionIdAtSend || undefined, agentId: agentId || undefined },
      {
        onSession: (sid) => {
          const session: SessionDTO = {
            sessionId: sid,
            agentId: '',
            title: '',
            status: 'ACTIVE',
            createTime: Date.now(),
            updateTime: Date.now(),
            messages: [],
          };
          upsertSession(session);
          if (!sessionIdAtSend) {
            skipLoadRef.current = true;
            selectSession(sid);
          }
        },
        onStep: (step) => addTraceStep(step),
        onToken: (token) => appendStreamingContent(token),
        onToolName: (name) => startToolCall(name),
        onToolArgs: (delta) => appendToolArgs(delta),
        onDone: () => {
          finishStream();
          setStatus('流式完成', 'ok');
        },
        onError: (msg) => {
          finishStream();
          setStatus(msg, 'err');
          useSessionStore.getState().setMessages([
            ...useSessionStore.getState().messages,
            makeMessage('system', `流式错误：${msg}`),
          ]);
        },
      },
    );
  };

  const handleStop = () => {
    streamRef.current?.close();
    const hasContent = useChatStore.getState().streamingContent.length > 0;
    finishStream();
    if (hasContent) {
      useSessionStore.getState().setMessages([
        ...useSessionStore.getState().messages,
        makeMessage('system', '（已停止生成）'),
      ]);
    }
    setStatus('已停止', '');
  };

  return (
    <div className="chat-layout">
      <section className="chat-main">
        <MessageList messages={messages} />
        <Composer busy={busy} onSend={handleSend} onStop={handleStop} />
      </section>
      <aside className="trace-panel">
        <TraceTimeline />
      </aside>
    </div>
  );
}
