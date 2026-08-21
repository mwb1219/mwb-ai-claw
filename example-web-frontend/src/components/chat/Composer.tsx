import { useState } from 'react';
import { Send, Square } from 'lucide-react';

interface ComposerProps {
  busy: boolean;
  onSend(message: string): void;
  onStop(): void;
}

/** 输入区：Enter 发送 / Shift+Enter 换行；发送中显示停止按钮 */
export function Composer({ busy, onSend, onStop }: ComposerProps) {
  const [text, setText] = useState('');

  const submit = () => {
    const message = text.trim();
    if (!message || busy) return;
    setText('');
    onSend(message);
  };

  return (
    <div className="composer">
      <textarea
        value={text}
        rows={2}
        placeholder="输入消息，Enter 发送 / Shift+Enter 换行"
        disabled={busy}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
      />
      {busy ? (
        <button className="btn btn-danger send-btn" onClick={onStop} title="停止生成">
          <Square size={14} /> 停止
        </button>
      ) : (
        <button className="btn btn-primary send-btn" onClick={submit} disabled={!text.trim()}>
          <Send size={14} /> 发送
        </button>
      )}
    </div>
  );
}
