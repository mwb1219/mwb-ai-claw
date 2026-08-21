import { useState } from 'react';
import { Copy, MessageSquarePlus, Pencil, RefreshCw, Trash2 } from 'lucide-react';

import { useSessionStore } from '../../store/session';
import { useChatStore } from '../../store/chat';
import { sessionApi } from '../../api/client';
import type { SessionDTO } from '../../api/types';
import { formatTime } from '../../utils/format';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { Empty } from '../common/Empty';

/** 会话侧边栏：新建 / 刷新 / 切换 / 重命名 / 复制 / 删除 */
export function Sidebar() {
  const sessions = useSessionStore((s) => s.sessions);
  const currentSessionId = useSessionStore((s) => s.currentSessionId);
  const setSessions = useSessionStore((s) => s.setSessions);
  const selectSession = useSessionStore((s) => s.selectSession);
  const upsertSession = useSessionStore((s) => s.upsertSession);
  const removeSession = useSessionStore((s) => s.removeSession);

  const [deleting, setDeleting] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState('');

  const refresh = async () => {
    try {
      const list = await sessionApi.list();
      setSessions(list);
    } catch (err) {
      alert(`加载会话失败：${(err as Error).message}`);
    }
  };

  const create = () => {
    // 本地新建：不调后端创建接口，回到空白会话；首次发送消息时由后端自动创建会话
    useSessionStore.getState().selectSession(null);
    useSessionStore.getState().setMessages([]);
    useChatStore.getState().clearTrace();
  };

  const startRename = (s: SessionDTO) => {
    setEditing(s.sessionId);
    setEditingTitle(s.title || '');
  };

  const submitRename = async () => {
    if (!editing) return;
    const title = editingTitle.trim();
    if (!title) {
      setEditing(null);
      return;
    }
    try {
      const updated = await sessionApi.rename(editing, title);
      upsertSession(updated);
    } catch (err) {
      alert(`重命名失败：${(err as Error).message}`);
    } finally {
      setEditing(null);
    }
  };

  const onDuplicate = async (sessionId: string) => {
    try {
      const s = await sessionApi.duplicate(sessionId);
      upsertSession(s);
    } catch (err) {
      alert(`复制失败：${(err as Error).message}`);
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
                {editing === s.sessionId ? (
                  <input
                    className="session-rename-input"
                    value={editingTitle}
                    autoFocus
                    onChange={(e) => setEditingTitle(e.target.value)}
                    onClick={(e) => e.stopPropagation()}
                    onBlur={submitRename}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') submitRename();
                      if (e.key === 'Escape') setEditing(null);
                    }}
                  />
                ) : (
                  <span className="session-item-title">{s.title || '未命名会话'}</span>
                )}
                <span className="session-item-time">{formatTime(s.updateTime || s.createTime)}</span>
              </div>
              <div className="session-actions">
                <button
                  className="btn-icon"
                  title="复制会话"
                  aria-label="复制会话"
                  onClick={(e) => {
                    e.stopPropagation();
                    onDuplicate(s.sessionId);
                  }}
                >
                  <Copy size={14} />
                </button>
                <button
                  className="btn-icon"
                  title="重命名"
                  aria-label="重命名"
                  onClick={(e) => {
                    e.stopPropagation();
                    startRename(s);
                  }}
                >
                  <Pencil size={14} />
                </button>
                <button
                  className="btn-icon"
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
