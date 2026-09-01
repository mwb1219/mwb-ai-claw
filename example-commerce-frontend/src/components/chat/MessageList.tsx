import { useEffect, useRef } from 'react';
import { Bot, User, Wrench } from 'lucide-react';

import type { MessageDTO } from '../../api/types';
import { MarkdownView } from './MarkdownView';
import { useChatStore } from '../../store/chat';

interface MessageListProps {
  messages: MessageDTO[];
}

const ROLE_META: Record<string, { label: string; icon: typeof Bot }> = {
  user: { label: 'YOU', icon: User },
  assistant: { label: 'AI', icon: Bot },
  tool: { label: 'TOOL', icon: Wrench },
  system: { label: 'SYS', icon: Bot },
};

/** 合并后的消息组：连续同角色消息只展示一个头像，内容按序拼接 */
function Bubble({ role, items }: { role: string; items: MessageDTO[] }) {
  const meta = ROLE_META[role] ?? { label: role.toUpperCase(), icon: Bot };
  const Icon = meta.icon;
  const renderMarkdown = role === 'assistant';

  return (
    <div className={`msg msg-${role}`}>
      <div className="avatar">
        <Icon size={15} />
        <span className="avatar-label">{meta.label}</span>
      </div>
      <div className="bubble">
        {items.map((m, i) => (
          <div className="bubble-seg" key={`${m.timestamp}-${i}`}>
            {renderMarkdown ? <MarkdownView content={m.content} /> : m.content}
          </div>
        ))}
      </div>
    </div>
  );
}

/** 消息列表：历史消息 + 流式中的助手气泡 + 欢迎页 */
export function MessageList({ messages }: MessageListProps) {
  const streaming = useChatStore((s) => s.streaming);
  const streamingContent = useChatStore((s) => s.streamingContent);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamingContent, streaming]);

  // 过滤工具调用消息：只展示对话内容（user/assistant/system），工具调用在轨迹面板展示
  const visibleMessages = messages.filter((m) => m.role !== 'tool');

  // 连续相同角色且归档状态一致的合并为一组；归档边界作为硬断点，
  // 避免「已归档历史」与「活动热窗」相邻且同角色时被并成一个气泡、分隔线与真实边界错位。
  const groups: { role: string; archived: boolean; items: MessageDTO[] }[] = [];
  for (const m of visibleMessages) {
    const last = groups[groups.length - 1];
    if (last && last.role === m.role && last.archived === Boolean(m.archived)) {
      last.items.push(m);
    } else {
      groups.push({ role: m.role, archived: Boolean(m.archived), items: [m] });
    }
  }

  const showWelcome = groups.length === 0 && !streaming;
  // 仅当同时存在「已归档历史区」与「未归档活动区」时才插分隔线：
  // 全归档（无活动区）或全未归档（无历史区）场景下分隔没有意义，避免顶部出现孤立分隔线。
  const hasActiveRegion = groups.some((g) => !g.archived);

  return (
    <div className="messages">
      {showWelcome ? (
        <div className="welcome">
          <div className="welcome-logo">
            <Bot size={40} />
          </div>
          <h2>mwb-ai-claw 控制台</h2>
          <p>输入消息与 Agent 对话，全程 SSE 流式输出，支持 ReAct 推理轨迹与工具调用展示。</p>
        </div>
      ) : (
        (() => {
          // 归档边界：首个已归档消息组之前插入「归档历史」分隔说明，
          // 明确区分活动热窗（未归档）与已滚出热窗的跨会话档案原文。
          let archiveCrossed = false;
          return groups.map((g, i) => {
            const showDivider = g.archived && !archiveCrossed && hasActiveRegion;
            if (g.archived) archiveCrossed = true;
            return (
              <div key={i}>
                {showDivider && (
                  <div className="archive-divider" role="separator">
                    <span className="archive-divider-line" />
                    <span className="archive-divider-label">归档历史</span>
                    <span className="archive-divider-line" />
                  </div>
                )}
                <Bubble role={g.role} items={g.items} />
              </div>
            );
          });
        })()
      )}
      {streaming ? (
        <div className="msg msg-assistant">
          <div className="avatar">
            <Bot size={15} />
            <span className="avatar-label">AI</span>
          </div>
          <div className="bubble streaming">
            {streamingContent ? (
              <>
                {streamingContent}
                <span className="stream-dots" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </span>
              </>
            ) : (
              <span className="thinking">
                <span className="thinking-dots" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </span>
                <span className="thinking-text">思考中</span>
              </span>
            )}
          </div>
        </div>
      ) : null}
      <div ref={bottomRef} />
    </div>
  );
}
