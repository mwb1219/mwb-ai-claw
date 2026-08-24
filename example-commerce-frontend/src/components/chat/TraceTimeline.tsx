import { useChatStore } from '../../store/chat';
import { Empty } from '../common/Empty';
import { Tag } from '../common/Tag';

/** 推理轨迹时间线 + 工具调用片段（右侧栏） */
export function TraceTimeline() {
  const traceSteps = useChatStore((s) => s.traceSteps);
  const toolCalls = useChatStore((s) => s.toolCalls);
  const busy = useChatStore((s) => s.busy);

  if (traceSteps.length === 0 && toolCalls.length === 0) {
    return <Empty text={busy ? 'Agent 推理中…' : '执行对话后，Thought / Action / Observation 将在此展示'} />;
  }

  return (
    <div className="timeline">
      {toolCalls.length > 0 ? (
        <div className="tool-calls">
          <h4 className="timeline-title">工具调用</h4>
          {toolCalls.map((t) => (
            <div key={t.id} className="tool-call">
              <div className="tool-call-head">
                <Tag tone="primary">{t.name}</Tag>
              </div>
              {t.args ? (
                <pre className="tool-call-args">{t.args}</pre>
              ) : (
                <div className="tool-call-pending">执行中…</div>
              )}
            </div>
          ))}
        </div>
      ) : null}
      {traceSteps.length > 0 ? (
        <div className="trace-list">
          <h4 className="timeline-title">推理轨迹</h4>
          {traceSteps.map((step) => (
            <div key={step.id} className={`trace-step trace-${step.type}`}>
              <Tag tone={step.type === 'error' ? 'danger' : step.type === 'action' ? 'warning' : 'info'}>
                {step.label}
              </Tag>
              <div className="trace-body">{step.body}</div>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
