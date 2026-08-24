import { useCallback, useEffect, useState } from 'react';
import { Check, RefreshCw, X } from 'lucide-react';

import { approvalApi } from '../api/client';
import type { PendingApprovalDTO } from '../api/types';
import { Card } from '../components/common/Card';
import { Tag } from '../components/common/Tag';
import { Empty } from '../components/common/Empty';
import { Loading } from '../components/common/Loading';
import { Button } from '../components/common/Button';
import { formatTime } from '../utils/format';

/** 人工审批面板：待审批节点列表 + 通过 / 拒绝 */
export function ApprovalPage() {
  const [tasks, setTasks] = useState<PendingApprovalDTO[]>([]);
  const [sessionFilter, setSessionFilter] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [acting, setActing] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setTasks(await approvalApi.pendingTasks(sessionFilter || undefined));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  }, [sessionFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const act = async (task: PendingApprovalDTO, action: 'approve' | 'reject') => {
    if (acting) return;
    if (action === 'reject' && !window.confirm(`拒绝审批「${task.task}」？该层将降级直执行。`)) return;
    setActing(`${task.sessionId}:${task.layerKey}:${action}`);
    try {
      await approvalApi[action]({ sessionId: task.sessionId, layerKey: task.layerKey, action });
      // 局部刷新
      setTasks(await approvalApi.pendingTasks(sessionFilter || undefined));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setActing(null);
    }
  };

  return (
    <div className="page approval-page">
      <div className="page-head">
        <h2>审批面板</h2>
        <div className="page-head-actions">
          <input
            className="filter-input"
            value={sessionFilter}
            placeholder="按 sessionId 过滤（可留空）"
            spellCheck={false}
            onChange={(e) => setSessionFilter(e.target.value)}
          />
          <Button size="sm" icon={RefreshCw} onClick={() => void load()}>
            刷新
          </Button>
        </div>
      </div>

      {error ? <div className="alert alert-error">{error}</div> : null}
      {loading ? <Loading text="加载待审批任务…" /> : null}

      {!loading && tasks.length === 0 ? (
        <Empty text="暂无待审批任务" />
      ) : (
        <div className="approval-list">
          {tasks.map((t) => (
            <Card
              key={`${t.sessionId}:${t.layerKey}`}
              title={
                <span className="approval-title">
                  <Tag tone="warning">待审批</Tag>
                  <span className="mono layer-key">{t.layerKey}</span>
                </span>
              }
              actions={<span className="text-faint">{formatTime(t.createdAt)}</span>}
            >
              <p className="approval-task">{t.task}</p>
              <div className="approval-todos">
                <span className="text-secondary">计划 {t.todoCount} 项：</span>
                <ul>
                  {t.todoTitles.map((todo, i) => (
                    <li key={i}>{todo}</li>
                  ))}
                </ul>
              </div>
              <div className="approval-meta text-faint mono">sessionId: {t.sessionId}</div>
              <div className="approval-actions">
                <Button
                  variant="primary"
                  size="sm"
                  icon={Check}
                  disabled={acting != null}
                  onClick={() => void act(t, 'approve')}
                >
                  {acting === `${t.sessionId}:${t.layerKey}:approve` ? '处理中…' : '审批通过'}
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  icon={X}
                  disabled={acting != null}
                  onClick={() => void act(t, 'reject')}
                >
                  {acting === `${t.sessionId}:${t.layerKey}:reject` ? '处理中…' : '审批拒绝'}
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
