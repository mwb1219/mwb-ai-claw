import { useState } from 'react';
import { Tag } from '../common/Tag';

/** 子代理执行结果（spawn_agent 工具 Observation 反序列化后的核心字段） */
export interface SubAgentResult {
  agentId?: string;
  reply?: string;
  traceSteps?: string[];
  stepsUsed?: number;
  tokens?: number;
  durationMs?: number;
  success?: boolean;
  error?: string;
  cancelled?: boolean;
}

/**
 * 尝试把 observation 的 body 解析为子代理结果 JSON。
 * 若包含 {@code agentId}（spawn_agent 工具返回的子代理标识）才算命中，避免误判其它工具输出。
 */
export function tryParseSubAgent(body: string): SubAgentResult | null {
  const trimmed = (body || '').trim();
  if (!trimmed.startsWith('{')) return null;
  try {
    const obj = JSON.parse(trimmed);
    if (obj && typeof obj === 'object' && 'agentId' in obj) {
      return obj as SubAgentResult;
    }
  } catch {
    /* 不是合法 JSON，按普通观察结果处理 */
  }
  return null;
}

/** 推理轨迹里的一张子代理卡片：状态 + reply + 元信息 + 可展开的子代理自身轨迹 */
export function SubAgentCard({ data }: { data: SubAgentResult }) {
  const [showTrace, setShowTrace] = useState(false);
  const cancelled = data.cancelled === true;
  const failed = !cancelled && data.success === false;
  const tone = cancelled ? 'warning' : failed ? 'danger' : 'success';
  const statusText = cancelled ? '已取消' : failed ? '失败' : '成功';
  const traceCount = data.traceSteps?.length ?? 0;

  return (
    <div className={`subagent-card ${cancelled ? 'is-cancelled' : failed ? 'is-error' : 'is-success'}`}>
      <div className="subagent-head">
        <Tag tone={tone}>{statusText}</Tag>
        <span className="subagent-title">子代理</span>
        {data.agentId ? <span className="subagent-id">{data.agentId}</span> : null}
      </div>

      {data.error ? <div className="subagent-error">{data.error}</div> : null}

      {data.reply ? (
        <div className="subagent-reply">
          <span className="subagent-reply-label">结论</span>
          {data.reply}
        </div>
      ) : null}

      <div className="subagent-meta">
        {typeof data.stepsUsed === 'number' ? <span>步数 {data.stepsUsed}</span> : null}
        {typeof data.tokens === 'number' ? <span>tokens {data.tokens}</span> : null}
        {typeof data.durationMs === 'number' ? <span>耗时 {data.durationMs}ms</span> : null}
      </div>

      {traceCount > 0 ? (
        <button
          type="button"
          className="subagent-toggle"
          onClick={() => setShowTrace((v) => !v)}
        >
          {showTrace ? '收起' : '查看'}子代理推理轨迹（{traceCount}）
        </button>
      ) : null}

      {showTrace && data.traceSteps ? (
        <ul className="subagent-trace">
          {data.traceSteps.map((step, i) => (
            <li key={i} className="subagent-trace-step">
              {step}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
