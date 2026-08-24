import { useEffect, useMemo, useState } from 'react';
import { RefreshCw, Search } from 'lucide-react';

import { memoryApi } from '../api/client';
import type { MemoryOverview, MemoryPage } from '../api/types';
import { Card } from '../components/common/Card';
import { Tag } from '../components/common/Tag';
import { Empty } from '../components/common/Empty';
import { Loading } from '../components/common/Loading';
import { Button } from '../components/common/Button';
import { formatImportance, formatTime, formatTokens } from '../utils/format';

type TabKey = 'facts' | 'summaries' | 'archives' | 'search';

const TYPE_TONE: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  FACT: 'primary',
  SUMMARY: 'success',
  ARCHIVE: 'warning',
  HOT: 'info',
  RETRIEVED: 'danger',
};

/** 记忆可视化面板：总览统计 + 事实/摘要/归档/检索调试 */
export function MemoryPage() {
  const [overview, setOverview] = useState<MemoryOverview | null>(null);
  const [tab, setTab] = useState<TabKey>('facts');
  const [facts, setFacts] = useState<MemoryPage[]>([]);
  const [summaries, setSummaries] = useState<MemoryPage[]>([]);
  const [archives, setArchives] = useState<MemoryPage[]>([]);
  const [sessionFilter, setSessionFilter] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // 检索调试
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(5);
  const [searchResults, setSearchResults] = useState<MemoryPage[] | null>(null);
  const [searching, setSearching] = useState(false);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [ov, fs] = await Promise.all([memoryApi.overview(), memoryApi.facts()]);
      setOverview(ov);
      setFacts(fs);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  // 加载摘要/归档（sessionFilter 变化时）
  useEffect(() => {
    if (tab !== 'summaries' && tab !== 'archives') return;
    let cancelled = false;
    const filter = sessionFilter || undefined;
    (tab === 'summaries' ? memoryApi.summaries(filter) : memoryApi.archive(filter))
      .then((list) => {
        if (!cancelled) {
          if (tab === 'summaries') setSummaries(list);
          else setArchives(list);
        }
      })
      .catch((err) => setError((err as Error).message));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, sessionFilter]);

  const sessionOptions = useMemo(() => {
    const set = new Set<string>();
    facts.forEach((f) => f.sessionId && set.add(f.sessionId));
    archives.forEach((a) => a.sessionId && set.add(a.sessionId));
    return Array.from(set).sort();
  }, [facts, archives]);

  const doSearch = async () => {
    if (!query.trim()) return;
    setSearching(true);
    try {
      setSearchResults(await memoryApi.search(query.trim(), topK));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSearching(false);
    }
  };

  const stats = overview?.stats;

  return (
    <div className="page memory-page">
      <div className="page-head">
        <h2>记忆面板</h2>
        <div className="page-head-actions">
          <span className="text-secondary">分层记忆 {overview ? (overview.enabled ? '已启用' : '已关闭') : ''}</span>
          <Button size="sm" icon={RefreshCw} onClick={() => void load()}>
            刷新
          </Button>
        </div>
      </div>

      {error ? <div className="alert alert-error">{error}</div> : null}
      {loading && !overview ? <Loading text="加载记忆总览…" /> : null}

      {overview ? (
        <div className="memory-overview">
          <Card title="分层统计" className="stats-card">
            <div className="stat-grid">
              <div className="stat-cell">
                <span className="stat-label">FACTS 长期事实</span>
                <span className="stat-value">{stats?.facts[0] ?? 0}</span>
                <span className="stat-sub">{formatTokens(stats?.facts[1])} tokens</span>
              </div>
              <div className="stat-cell">
                <span className="stat-label">SUMMARIES 中期摘要</span>
                <span className="stat-value">{stats?.summaries[0] ?? 0}</span>
                <span className="stat-sub">{formatTokens(stats?.summaries[1])} tokens</span>
              </div>
              <div className="stat-cell">
                <span className="stat-label">ARCHIVES 档案归档</span>
                <span className="stat-value">{stats?.archives[0] ?? 0}</span>
                <span className="stat-sub">{formatTokens(stats?.archives[1])} tokens</span>
              </div>
            </div>
          </Card>
          <Card title="提炼与检索" className="stats-card">
            <div className="synthesis-grid">
              <div>
                <span className="stat-label">提炼队列</span>
                <span className="stat-value">{overview.synthesis.pendingTasks}</span>
                <span className="stat-sub">pending tasks</span>
              </div>
              <div>
                <span className="stat-label">提炼缓存</span>
                <span className="stat-value mono">
                  {(overview.synthesis.cache as { size?: number })?.size ?? '-'}
                </span>
                <span className="stat-sub">
                  {(overview.synthesis.cache as { hits?: number })?.hits != null
                    ? `命中 ${(overview.synthesis.cache as { hits?: number }).hits}`
                    : 'entries'}
                </span>
              </div>
            </div>
          </Card>
          {stats?.archiveBySession && Object.keys(stats.archiveBySession).length > 0 ? (
            <Card title="归档按会话分布" className="stats-card">
              <div className="archive-dist">
                {Object.entries(stats.archiveBySession)
                  .sort((a, b) => b[1] - a[1])
                  .map(([sid, count]) => (
                    <div key={sid} className="dist-row">
                      <span className="mono dist-sid">{sid.slice(0, 14)}…</span>
                      <span className="dist-bar">
                        <span
                          className="dist-bar-fill"
                          style={{ width: `${Math.min(100, (count / Math.max(...Object.values(stats.archiveBySession))) * 100)}%` }}
                        />
                      </span>
                      <span className="dist-count">{count}</span>
                    </div>
                  ))}
              </div>
            </Card>
          ) : null}
        </div>
      ) : null}

      <Card
        className="memory-tabs-card"
        title="分层内容"
        actions={
          <div className="memory-filter">
            <label className="filter-label">
              会话过滤
              <select value={sessionFilter} onChange={(e) => setSessionFilter(e.target.value)}>
                <option value="">全部</option>
                {sessionOptions.map((sid) => (
                  <option key={sid} value={sid}>
                    {sid.slice(0, 16)}…
                  </option>
                ))}
              </select>
            </label>
          </div>
        }
      >
        <div className="tabs">
          <button className={`tab${tab === 'facts' ? ' active' : ''}`} onClick={() => setTab('facts')}>
            事实
          </button>
          <button className={`tab${tab === 'summaries' ? ' active' : ''}`} onClick={() => setTab('summaries')}>
            摘要
          </button>
          <button className={`tab${tab === 'archives' ? ' active' : ''}`} onClick={() => setTab('archives')}>
            归档
          </button>
          <button className={`tab${tab === 'search' ? ' active' : ''}`} onClick={() => setTab('search')}>
            检索调试
          </button>
        </div>

        {tab === 'facts' ? (
          <MemoryList
            items={facts}
            empty="暂无长期记忆事实"
            render={(p) => (
              <div className="fact-row">
                <div className="fact-head">
                  <Tag tone={TYPE_TONE[p.type]}>{p.type}</Tag>
                  {p.key ? <span className="mono fact-key">{p.key}</span> : null}
                  {p.importance != null ? (
                    <span className="fact-imp">
                      <span className="imp-bar">
                        <span
                          className="imp-bar-fill"
                          style={{ width: `${Math.round(p.importance * 100)}%` }}
                        />
                      </span>
                      <span className="imp-label">{formatImportance(p.importance)}</span>
                    </span>
                  ) : null}
                </div>
                <div className="fact-content">{p.content}</div>
                <div className="fact-meta text-faint">
                  v{p.version ?? 1} · {formatTime(p.createTime)}
                  {p.sessionId ? ` · ${p.sessionId.slice(0, 12)}…` : ''}
                </div>
              </div>
            )}
          />
        ) : null}

        {tab === 'summaries' ? (
          <MemoryList
            items={summaries}
            empty="暂无中期摘要"
            render={(p) => (
              <div className="fact-row">
                <div className="fact-head">
                  <Tag tone="success">SUMMARY</Tag>
                  <span className="mono fact-key">
                    [{p.blockStart ?? '-'}–{p.blockEnd ?? '-'}]
                  </span>
                  <span className="text-faint">{formatTokens(p.tokenCount)} tokens</span>
                </div>
                <div className="fact-content">{p.content}</div>
                <div className="fact-meta text-faint">
                  {formatTime(p.createTime)} · {p.sessionId ? p.sessionId.slice(0, 12) : ''}…
                </div>
              </div>
            )}
          />
        ) : null}

        {tab === 'archives' ? (
          <MemoryList
            items={archives}
            empty="暂无档案归档"
            render={(p) => (
              <div className="fact-row">
                <div className="fact-head">
                  <Tag tone="warning">ARCHIVE</Tag>
                  <span className="mono fact-key">[{p.blockStart ?? '-'}–{p.blockEnd ?? '-'}]</span>
                  <span className="text-faint">{formatTokens(p.tokenCount)} tokens</span>
                </div>
                <div className="fact-content">{p.content}</div>
                <div className="fact-meta text-faint">{formatTime(p.createTime)}</div>
              </div>
            )}
          />
        ) : null}

        {tab === 'search' ? (
          <div className="search-debug">
            <div className="search-form">
              <input
                value={query}
                placeholder="输入检索关键词，测试 hybrid 召回"
                spellCheck={false}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && void doSearch()}
              />
              <select value={topK} onChange={(e) => setTopK(Number(e.target.value))}>
                {[3, 5, 10, 20].map((n) => (
                  <option key={n} value={n}>
                    topK {n}
                  </option>
                ))}
              </select>
              <Button variant="primary" icon={Search} disabled={!query.trim() || searching} onClick={() => void doSearch()}>
                检索
              </Button>
            </div>
            {searching ? <Loading text="检索中…" /> : null}
            {searchResults ? (
              searchResults.length === 0 ? (
                <Empty text="无召回结果" />
              ) : (
                <div className="search-results">
                  {searchResults.map((p, i) => (
                    <div key={`${p.pageId}-${i}`} className="fact-row">
                      <div className="fact-head">
                        <Tag tone={TYPE_TONE[p.type] ?? 'info'}>{p.type}</Tag>
                        {p.key ? <span className="mono fact-key">{p.key}</span> : null}
                        {p.importance != null ? (
                          <span className="text-faint">{formatImportance(p.importance)}</span>
                        ) : null}
                      </div>
                      <div className="fact-content">{p.content}</div>
                      <div className="fact-meta text-faint">
                        {formatTime(p.createTime)}
                        {p.sessionId ? ` · ${p.sessionId.slice(0, 12)}…` : ''}
                      </div>
                    </div>
                  ))}
                </div>
              )
            ) : null}
          </div>
        ) : null}
      </Card>
    </div>
  );
}

function MemoryList({
  items,
  empty,
  render,
}: {
  items: MemoryPage[];
  empty: string;
  render(p: MemoryPage): React.ReactNode;
}) {
  if (items.length === 0) return <Empty text={empty} />;
  return <div className="memory-list">{items.map((p, i) => <div key={`${p.pageId}-${i}`}>{render(p)}</div>)}</div>;
}
