import { useChatStore } from '../../store/chat';

/** 底部状态条：请求 / 流式 / 错误状态 */
export function StatusBar() {
  const status = useChatStore((s) => s.status);
  if (!status.text) return null;
  return (
    <div className={`statusbar status-${status.type}`} aria-live="polite">
      {status.text}
    </div>
  );
}
