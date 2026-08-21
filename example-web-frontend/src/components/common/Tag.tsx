import type { ReactNode } from 'react';

type Tone = 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info';

interface TagProps {
  tone?: Tone;
  children: ReactNode;
  title?: string;
}

export function Tag({ tone = 'default', children, title }: TagProps) {
  return (
    <span className={`tag tag-${tone}`} title={title}>
      {children}
    </span>
  );
}
