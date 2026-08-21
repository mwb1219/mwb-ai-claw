import { useState } from 'react';
import { MessageSquarePlus, RefreshCw, Trash2 } from 'lucide-react';

import { useSessionStore } from '../../store/session';
import { sessionApi } from '../../api/client';
import { formatTime } from '../../utils/format';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { Empty } from '../common/Empty';

/** 会话侧边栏：新建 / 刷新 / 切换 / 删除 */
export function Sidebar() {
  const sessions = useSessionStore((s) => s.sessions);
  const currentSessionId = useSessionStore((s) => s.currentSessionId);
  const setSessions = useSessionStore((s) => s.setSessions);
  const selectSession = useSessionStore((s) => s.selectSession);
  const removeSession = useSessionStore((s) => s.removeSession);

  const [deleting, setDeleting] = useState<string | null>(null);

  const refresh = async () => {
    try {
      const list = await sessionApi.list();
      setSessions(list);
    } catch (err) {
      alert(`加载会话失败：${(err as Error).message}`);
    }
  };

  const create = async () => {
    try {
      const s = await sessionApi.create();
      useSessionStore.getState().upsertSession(s);
      selectSession(s.sessionId);
    } catch (err) {
      alert(`创建会话失败：${(err as Error).message}`);
    }
  };

  const onDelete = async () => {
    if (!deleting) return;
    try {
      await sessionApi.remove(deleting);
      removeSession(deleting);
    } catch (err) {
      alert(`删除失败：${(err as Error).message}`);
    } finally {
      setDeleting(null);
    }
  };

  // 会话排序：最后更新优先
  const sorted = [...sessions].sort(
    (a, b) => (b.updateTime || b.createTime || 0) - (a.updateTime || a.createTime || 0),
  );

  return (
    <aside className="sidebar">
      <div className="sidebar-head">
        <h2>会话</h2>
        <div className="sidebar-actions">
          <button className="btn-icon" title="新建会话" aria-label="新建会话" onClick={create}>
            <MessageSquarePlus size={16} />
          </button>
          <button className="btn-icon" title="刷新会话列表" aria-label="刷新会话列表" onClick={refresh}>
            <RefreshCw size={16} />
          </button>
        </div>
      </div>
      <div className="session-list">
        {sorted.length === 0 ? (
          <Empty text="暂无会话，点击 + 新建" />
        ) : (
          sorted.map((s) => (
            <div
              key={s.sessionId}
              className={`session-item${s.sessionId === currentSessionId ? ' active' : ''}`}
              onClick={() => selectSession(s.sessionId)}
            >
              <div className="session-item-main">
                <span className="session-item-title">{s.title || '未命名会话'}</span>
                <span className="session-item-time">{formatTime(s.updateTime || s.createTime)}</span>
              </div>
              <button
                className="btn-icon session-del"
                title="删除会话"
                aria-label="删除会话"
                onClick={(e) => {
                  e.stopPropagation();
                  setDeleting(s.sessionId);
                }}
              >
                <Trash2 size={14} />
              </button>
            </div>
          ))
        )}
      </div>
      <ConfirmDialog
        open={deleting != null}
        title="删除会话"
        message="确定要删除此会话？此操作不可恢复。"
        confirmText="删除"
        danger
        onConfirm={onDelete}
        onCancel={() => setDeleting(null)}
      />
    </aside>
  );
}
