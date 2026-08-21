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

function Bubble({ msg }: { msg: MessageDTO }) {
  const meta = ROLE_META[msg.role] ?? { label: msg.role.toUpperCase(), icon: Bot };
  const Icon = meta.icon;
  const renderMarkdown = msg.role === 'assistant' || msg.role === 'tool';

  return (
    <div className={`msg msg-${msg.role}`}>
      <div className="avatar">
        <Icon size={15} />
        <span className="avatar-label">{meta.label}</span>
      </div>
      <div className="bubble">
        {renderMarkdown ? <MarkdownView content={msg.content} /> : msg.content}
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

  const showWelcome = messages.length === 0 && !streaming;

  return (
    <div className="messages">
      {showWelcome ? (
        <div className="welcome">
          <div className="welcome-logo">
            <Bot size={40} />
          </div>
          <h2>mwb-ai-claw 控制台</h2>
          <p>输入消息与 Agent 对话，全程 SSE 流式输出，支持 ReAct 推理轨迹与工具调用展示。</p>
          <p className="dim">提示：在右上角「连接」中可配置后端地址、Agent ID 与 API Key。</p>
        </div>
      ) : (
        messages.map((m, i) => <Bubble key={`${m.timestamp}-${i}`} msg={m} />)
      )}
      {streaming ? (
        <div className="msg msg-assistant">
          <div className="avatar">
            <Bot size={15} />
            <span className="avatar-label">AI</span>
          </div>
          <div className="bubble streaming">
            {streamingContent || <span className="thinking-dots">● ● ●</span>}
          </div>
        </div>
      ) : null}
      <div ref={bottomRef} />
    </div>
  );
}
