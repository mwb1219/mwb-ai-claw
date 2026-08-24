import type { ReactNode } from 'react';

interface CardProps {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}

/** 面板卡片：标题栏（可选）+ 内容区 */
export function Card({ title, actions, children, className = '' }: CardProps) {
  return (
    <section className={`card ${className}`.trim()}>
      {title != null || actions != null ? (
        <header className="card-head">
          {title != null ? <h3 className="card-title">{title}</h3> : null}
          {actions != null ? <div className="card-actions">{actions}</div> : null}
        </header>
      ) : null}
      <div className="card-body">{children}</div>
    </section>
  );
}
