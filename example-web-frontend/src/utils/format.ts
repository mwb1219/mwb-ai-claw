/** 时间戳格式化：刚刚 / N分钟前 / N小时前 / N天前 / 日期 */
export function formatTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts);
  const diffMs = Date.now() - ts;
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return '刚刚';
  if (diffMin < 60) return `${diffMin}分钟前`;
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return `${diffH}小时前`;
  const diffD = Math.floor(diffH / 24);
  if (diffD < 7) return `${diffD}天前`;
  return d.toLocaleDateString('zh-CN');
}

/** 完整时间（含时分） */
export function formatDateTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.toLocaleDateString('zh-CN')} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 重要度 0-1 → 百分比 */
export function formatImportance(v?: number): string {
  if (v == null) return '-';
  return `${Math.round(v * 100)}%`;
}

/** token 数格式化：1.2k / 345 */
export function formatTokens(n?: number): string {
  if (n == null) return '-';
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}
