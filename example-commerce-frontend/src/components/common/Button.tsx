import type { ButtonHTMLAttributes, ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

type Variant = 'primary' | 'default' | 'ghost' | 'danger';
type Size = 'sm' | 'md';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  icon?: LucideIcon;
  children?: ReactNode;
}

export function Button({
  variant = 'default',
  size = 'md',
  icon: Icon,
  className = '',
  children,
  ...rest
}: ButtonProps) {
  const cls = ['btn', `btn-${variant}`, `btn-${size}`, className].join(' ').trim();
  return (
    <button className={cls} {...rest}>
      {Icon ? <Icon size={16} strokeWidth={2} /> : null}
      {children != null ? <span>{children}</span> : null}
    </button>
  );
}
