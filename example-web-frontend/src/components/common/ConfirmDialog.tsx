import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import type { ReactNode } from 'react';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmText?: string;
  danger?: boolean;
  onConfirm(): void;
  onCancel(): void;
}

/** 轻量确认对话框：二次确认（删除会话 / 审批拒绝等危险操作） */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmText = '确认',
  danger = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onCancel]);

  if (!open) return null;

  return createPortal(
    <div className="dialog-mask" onMouseDown={onCancel}>
      <div className="dialog" role="dialog" aria-modal="true" onMouseDown={(e) => e.stopPropagation()}>
        <h3 className="dialog-title">{title}</h3>
        <div className="dialog-body">{message}</div>
        <div className="dialog-actions">
          <button className="btn btn-default" onClick={onCancel}>
            取消
          </button>
          <button className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm}>
            {confirmText}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
