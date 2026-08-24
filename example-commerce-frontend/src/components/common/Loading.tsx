interface LoadingProps {
  text?: string;
}

export function Loading({ text = '加载中…' }: LoadingProps) {
  return (
    <div className="loading">
      <span className="dots">
        <span />
        <span />
        <span />
      </span>
      <span className="loading-text">{text}</span>
    </div>
  );
}
