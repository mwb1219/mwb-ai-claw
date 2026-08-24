import { useEffect, useState } from 'react';
import { Activity, Eye, RefreshCw, Search } from 'lucide-react';

import { observabilityApi } from '../api/client';
import type { RunUsage, TraceRun } from '../api/types';
import { Button } from '../components/common/Button';
import { Card } from '../components/common/Card';
import { Empty } from '../components/common/Empty';
import { Loading } from '../components/common/Loading';
import { Tag } from '../components/common/Tag';

const STEP_TONE: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  thought: 'info',
  action: 'warning',
  observation: 'success',
  info: 'primary',
};
const STEP_LABEL: Record<string, string> = {
  thought: 'Thought',
  action: 'Action',
  observation: 'Observation',
  info: 'Info',
};

/** 耗时毫秒 → 可读文本 */
function formatDuration(ms?: number): string {
  if (ms == null) return '-';
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

/**
 * 可观测性面板（T5 用例）：
 * - 运行记录：按日期列出每次 Agent 执行的用量摘要（运行用量 store：local JSONL | db 落 claw_run_usage 表）
 * - 全链路 trace：运行记录携带 traceId，点击即可还原该次执行的 Thought / Action / Observation 逐步明细；
 *   也支持直接输入 traceId 查询（trace store：local | db 落 claw_trace 表）
 */
export function ObservabilityPage() {
  const [date, setDate] = useState('');
  const [runs, setRuns] = useState<RunUsage[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const [traceIdInput, setTraceIdInput] = useState('');
  const [trace, setTrace] = useState<TraceRun | null>(null);
  const [traceLoading, setTraceLoading] = useState(false);

  const loadRuns = async (d?: string) => {
    setLoading(true);
    setError('');
    try {
      setRuns(await observabilityApi.listRuns(d));
    } catch (err) {
      setError((err as Error).message);
      setRuns([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadRuns();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const queryTrace = async (traceId: string) => {
    const id = traceId.trim();
    if (!id) return;
    setTraceLoading(true);
    setError('');
    try {
      setTrace(await observabilityApi.getTrace(id));
    } catch (err) {
      setError((err as Error).message);
      setTrace(null);
    } finally {
      setTraceLoading(false);
    }
  };

  const stats = runs
    ? {
        total: runs.length,
        success: runs.filter((r) => r.success).length,
        failed: runs.filter((r) => !r.success).length,
        avgMs: runs.length
          ? Math.round(runs.reduce((s, r) => s + (r.durationMs ?? 0), 0) / runs.length)
          : 0,
      }
    : null;

  return (
    <div className="page observability-page">
      <div className="page-head">
        <h2>可观测性</h2>
        <div className="page-head-actions">
          <span className="text-secondary">运行记录 · 全链路 trace（逐次还原 Thought / Action / Observation）</span>
          <Button size="sm" icon={RefreshCw} onClick={() => void loadRuns(date || undefined)}>
            刷新
          </Button>
        </div>
      </div>

      {error ? <div className="alert alert-error">{error}</div> : null}

      {/* 今日/所选日期统计 */}
      <div className="stat-grid">
        <div className="stat-cell">
          <span className="stat-label">{date ? `RUNS ${date}` : 'RUNS 今日运行'}</span>
          <span className="stat-value">{stats?.total ?? '-'}</span>
          <span className="stat-sub">每次 Agent 执行一条摘要</span>
        </div>
        <div className="stat-cell">
          <span className="stat-label">SUCCESS 成功</span>
          <span className="stat-value stat-ok">{stats?.success ?? '-'}</span>
          <span className="stat-sub">执行成功的次数</span>
        </div>
        <div className="stat-cell">
          <span className="stat-label">FAILED 失败</span>
          <span className="stat-value stat-err">{stats?.failed ?? '-'}</span>
          <span className="stat-sub">失败可查 errorCode</span>
        </div>
        <div className="stat-cell">
          <span className="stat-label">AVG 平均耗时</span>
          <span className="stat-value">{stats ? formatDuration(stats.avgMs) : '-'}</span>
          <span className="stat-sub">平均单次执行耗时</span>
        </div>
      </div>

      {/* 运行记录列表 */}
      <Card
        title="运行记录"
        actions={
          <div className="runs-filter">
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              title="按日期过滤（缺省今天）"
            />
            <Button size="sm" variant="ghost" icon={Search} onClick={() => void loadRuns(date || undefined)}>
              查询
            </Button>
          </div>
        }
      >
        {loading ? (
          <Loading text="加载运行记录…" />
        ) : runs && runs.length === 0 ? (
          <Empty text="该日期暂无运行记录，去「对话」页执行一次对话后刷新查看" />
        ) : (
          <div className="run-list">
            {(runs || []).map((r, i) => (
              <div key={`${r.traceId ?? r.sessionId}-${i}`} className="run-row">
                <div className="run-head">
                  <Tag tone={r.success ? 'success' : 'danger'}>{r.success ? 'SUCCESS' : 'FAILED'}</Tag>
                  <span className="run-orchestration">{r.orchestration || '-'}</span>
                  <span className="mono run-trace-id" title={r.traceId}>
                    trace: {r.traceId ? r.traceId.slice(0, 16) : '-'}
                  </span>
                  <span className="text-faint">{r.ts ? r.ts.replace('T', ' ') : '-'}</span>
                </div>
                <div className="run-meta text-faint">
                  session {r.sessionId || '-'} · agent {r.agentId || '-'} · {r.model || '-'} ·{' '}
                  {r.steps ?? 0} steps · {formatDuration(r.durationMs)}
                  {r.errorCode ? ` · ${r.errorCode}` : ''}
                </div>
                <div className="run-actions">
                  <Button
                    size="sm"
                    variant="ghost"
                    icon={Eye}
                    disabled={!r.traceId}
                    onClick={() => {
                      if (r.traceId) {
                        setTraceIdInput(r.traceId);
                        void queryTrace(r.traceId);
                      }
                    }}
                  >
                    查看 trace
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* 全链路 trace 详情 */}
      <Card
        title="全链路 trace"
        actions={
          <div className="trace-query">
            <input
              value={traceIdInput}
              placeholder="输入 traceId（或从运行记录点击进入）"
              spellCheck={false}
              onChange={(e) => setTraceIdInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && void queryTrace(traceIdInput)}
            />
            <Button
              size="sm"
              variant="primary"
              icon={Search}
              disabled={!traceIdInput.trim() || traceLoading}
              onClick={() => void queryTrace(traceIdInput)}
            >
              查询
            </Button>
          </div>
        }
      >
        {traceLoading ? (
          <Loading text="还原 trace…" />
        ) : !trace ? (
          <Empty text="输入 traceId 或点击运行记录中的「查看 trace」还原一次执行的逐步明细" />
        ) : (
          <div className="trace-detail">
            <div className="trace-meta">
              <Tag tone={trace.success ? 'success' : 'danger'}>
                {trace.success ? 'SUCCESS' : 'FAILED'}
              </Tag>
              <span className="mono">{trace.traceId}</span>
              <span className="text-faint">
                {trace.orchestration || '-'} · {trace.model || '-'} · {trace.steps?.length ?? 0} steps ·{' '}
                {formatDuration(trace.durationMs)}
                {trace.errorCode ? ` · ${trace.errorCode}` : ''}
              </span>
            </div>
            <div className="timeline">
              {(trace.steps || []).map((step) => (
                <div key={step.index} className={`trace-step trace-${step.type}`}>
                  <Tag tone={STEP_TONE[step.type] ?? 'primary'}>{STEP_LABEL[step.type] ?? step.type}</Tag>
                  <div className="trace-body">{step.content}</div>
                </div>
              ))}
            </div>
          </div>
        )}
      </Card>

      {/* 能力说明 */}
      <Card title="存储与扩展（example-web 演示）" className="ext-card">
        <div className="ext-grid">
          <div className="ext-item">
            <div className="ext-head">
              <Activity size={16} />
              <span>运行记录存储可切换</span>
            </div>
            <p className="ext-desc">
              <code>agent.observability.run-usage-store</code> 切换 <code>local</code>
              （JSONL 文件，零依赖）或 <code>db</code>（落{' '}
              <code>claw_run_usage</code> 表，与会话/记忆/RAG 同库，多实例共享，生产推荐）。
              本示例默认 <code>db</code>（PostgreSQL）。
            </p>
          </div>
          <div className="ext-item">
            <div className="ext-head">
              <Activity size={16} />
              <span>trace 存储可切换</span>
            </div>
            <p className="ext-desc">
              <code>agent.observability.trace.store</code> 切换 <code>local</code>
              （每个 traceId 一个 JSON 文件）或 <code>db</code>（落{' '}
              <code>claw_trace</code> 表，生产推荐）。运行记录与 trace 通过{' '}
              <code>traceId</code> 关联：<code>/runs</code> 列表 →{' '}
              <code>/trace/&#123;traceId&#125;</code> 逐步还原。
            </p>
          </div>
        </div>
      </Card>
    </div>
  );
}
