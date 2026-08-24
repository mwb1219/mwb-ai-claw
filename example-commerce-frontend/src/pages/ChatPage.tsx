import { useEffect, useRef } from 'react';
import { Store } from 'lucide-react';

import { chatApi, sessionApi } from '../api/client';
import { chatStream } from '../api/sse';
import type { StreamHandle } from '../api/types';
import type { MessageDTO } from '../api/types';
import { useSessionStore } from '../store/session';
import { useChatStore } from '../store/chat';
import type { ToolCallItem } from '../store/chat';
import { labelForApiKey, useSettings } from '../store/settings';
import { MessageList } from '../components/chat/MessageList';
import { Composer } from '../components/chat/Composer';
import { KnowledgeBaseSelector } from '../components/chat/KnowledgeBaseSelector';
import { TraceTimeline } from '../components/chat/TraceTimeline';

function makeMessage(role: string, content: string): MessageDTO {
  return { role, content, timestamp: Date.now() };
}

const MAX_OBSERVATION_LEN = 500;

/** 从会话消息重建推理轨迹（仅当持久化轨迹缺失时降级使用） */
function rebuildTrace(messages: MessageDTO[]): string[] {
  const steps: string[] = [];
  messages.forEach((m) => {
    if (m.role === 'assistant') {
      if (m.content && m.content.trim()) steps.push(`[Thought] ${m.content}`);
      (m.toolCalls || []).forEach((tc) =>
        steps.push(`[Action] 调用工具: ${tc.name} 参数: ${tc.arguments || '{}'}`),
      );
    } else if (m.role === 'tool' && m.content) {
      const obs =
        m.content.length > MAX_OBSERVATION_LEN ? `${m.content.slice(0, MAX_OBSERVATION_LEN)}…` : m.content;
      steps.push(`[Observation] ${obs}`);
    }
  });
  return steps;
}

/** 需走 REST 完整对话的编排（流式接口不接收 orchestrationId） */
const REST_ONLY_ORCHESTRATIONS = ['marketing', 'todo-delegate'];

/** 对话页（核心）：会话切换加载 + SSE 流式对话 / REST 编排对话 + 推理轨迹/店铺隔离 */
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
  const apiKey = useSettings((s) => s.apiKey);
  const orchestrationId = useSettings((s) => s.orchestrationId);
  const setOrchestrationId = useSettings((s) => s.setOrchestrationId);

  const streamRef = useRef<StreamHandle | null>(null);
  const skipLoadRef = useRef(false);
  const autoCreatedRef = useRef(false);

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
    useChatStore.getState().clearTrace();
    sessionApi
      .get(currentSessionId)
      .then((s) => {
        if (cancelled) return;
        setMessages(s.messages || []);
        const traceRaw = s.traceSteps && s.traceSteps.length > 0 ? s.traceSteps : rebuildTrace(s.messages || []);
        useChatStore.getState().restoreTrace(traceRaw);
        const calls: ToolCallItem[] = [];
        (s.messages || []).forEach((m) =>
          (m.toolCalls || []).forEach((tc) =>
            calls.push({ id: tc.id, name: tc.name, args: tc.arguments || '' }),
          ),
        );
        useChatStore.getState().setToolCalls(calls);
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
    const sid = useSessionStore.getState().currentSessionId;
    if (autoCreatedRef.current && sid) {
      autoCreatedRef.current = false;
      sessionApi
        .get(sid)
        .then((d) => upsertSession(d))
        .catch(() => {});
    }
  };

  /** REST 完整对话（支持编排选择，返回完整回复 + 轨迹，不流式） */
  const runRestChat = (message: string, sessionIdAtSend: string | null) => {
    const knowledgeBaseIds = useSettings.getState().knowledgeBaseIds;
    chatApi
      .run({
        message,
        sessionId: sessionIdAtSend || undefined,
        orchestrationId,
        knowledgeBaseIds: knowledgeBaseIds.length > 0 ? knowledgeBaseIds : undefined,
      })
      .then((resp) => {
        useSessionStore.getState().setMessages([
          ...useSessionStore.getState().messages,
          makeMessage('assistant', resp.reply || ''),
        ]);
        if (resp.traceSteps && resp.traceSteps.length > 0) {
          useChatStore.getState().restoreTrace(resp.traceSteps);
        }
        if (resp.sessionId) {
          if (!sessionIdAtSend) {
            skipLoadRef.current = true;
            selectSession(resp.sessionId);
          }
          useSessionStore.getState().upsertSession({
            sessionId: resp.sessionId,
            agentId: resp.agentId,
            title: '',
            status: 'ACTIVE',
            createTime: Date.now(),
            updateTime: Date.now(),
            messages: [],
          });
        }
        setStatus('完成', 'ok');
      })
      .catch((err) => {
        setStatus(`对话失败：${(err as Error).message}`, 'err');
        useSessionStore.getState().setMessages([
          ...useSessionStore.getState().messages,
          makeMessage('system', `对话失败：${(err as Error).message}`),
        ]);
      })
      .finally(() => {
        useChatStore.getState().setStreaming(false);
        useChatStore.getState().setStreamingContent('');
        useChatStore.getState().setBusy(false);
      });
  };

  const handleSend = (message: string) => {
    const sessionIdAtSend = currentSessionId;
    autoCreatedRef.current = !sessionIdAtSend;
    setMessages([...messages, makeMessage('user', message)]);
    clearTrace();
    clearToolCalls();
    setBusy(true);
    setStreaming(true);
    setStreamingContent('');
    setStatus('正在与 Agent 通信…', 'busy');

    if (REST_ONLY_ORCHESTRATIONS.includes(orchestrationId)) {
      runRestChat(message, sessionIdAtSend);
      return;
    }

    streamRef.current = chatStream(
      { message, sessionId: sessionIdAtSend || undefined, agentId: agentId || undefined },
      {
        onSession: async (sid) => {
          try {
            const detail = await sessionApi.get(sid);
            upsertSession(detail);
          } catch {
            upsertSession({
              sessionId: sid,
              agentId: '',
              title: '',
              status: 'ACTIVE',
              createTime: Date.now(),
              updateTime: Date.now(),
              messages: [],
            });
          }
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
        <div className="commerce-toolbar">
          <span className="commerce-store-chip" title="当前店铺（多店铺隔离演示）">
            <Store size={14} />
            {labelForApiKey(apiKey)}
          </span>
          <label className="commerce-mode">
            编排
            <select
              value={orchestrationId}
              onChange={(e) => setOrchestrationId(e.target.value)}
              aria-label="选择编排模式"
            >
              <option value="routing">routing（SSE 流式）</option>
              <option value="marketing">marketing（REST · 营销方案）</option>
              <option value="todo-delegate">todo-delegate（REST · 委派）</option>
            </select>
          </label>
        </div>
        <KnowledgeBaseSelector />
        <MessageList messages={messages} />
        <Composer busy={busy} onSend={handleSend} onStop={handleStop} />
      </section>
      <aside className="trace-panel">
        <TraceTimeline />
      </aside>
    </div>
  );
}