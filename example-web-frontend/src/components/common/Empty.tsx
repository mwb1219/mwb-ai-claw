import type { ReactNode } from 'react';

interface EmptyProps {
  text?: string;
  children?: ReactNode;
}

export function Empty({ text = '暂无数据', children }: EmptyProps) {
  return (
    <div className="empty">
      <div className="empty-text">{text}</div>
      {children}
    </div>
  );
}
