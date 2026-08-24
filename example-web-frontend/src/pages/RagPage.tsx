import { useEffect, useState } from 'react';
import {
  Boxes,
  FilePlus2,
  PackageOpen,
  Plus,
  RefreshCw,
  RotateCw,
  Search,
  Trash2,
} from 'lucide-react';

import { ragApi } from '../api/client';
import type { RagDocument, RagSearchResult } from '../api/types';
import { Button } from '../components/common/Button';
import { Card } from '../components/common/Card';
import { ConfirmDialog } from '../components/common/ConfirmDialog';
import { Empty } from '../components/common/Empty';
import { Loading } from '../components/common/Loading';
import { Tag } from '../components/common/Tag';
import { useSettings } from '../store/settings';
import { formatDateTime } from '../utils/format';

const STATUS_TONE: Record<string, 'primary' | 'success' | 'warning' | 'danger'> = {
  READY: 'success',
  PROCESSING: 'primary',
  FAILED: 'danger',
};

/**
 * 知识库 RAG 管理面板：
 * - 知识库选择（写入 settings，对话时透传后端注入知识库参考）
 * - 文档摄入 / 列表 / 重建 / 删除
 * - 检索调试
 * - 扩展能力说明（example-web 注册的自定义 Chunker / Reranker）
 */
export function RagPage() {
  const knowledgeBaseIds = useSettings((s) => s.knowledgeBaseIds);
  const addKnowledgeBase = useSettings((s) => s.addKnowledgeBase);

  /** 当前操作的知识库（页内选中态，默认第一个） */
  const [currentKb, setCurrentKb] = useState('');
  const [kbInput, setKbInput] = useState('');

  const [docs, setDocs] = useState<RagDocument[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // 摄入表单（文件上传）
  const [docName, setDocName] = useState('');
  const [docId, setDocId] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [ingesting, setIngesting] = useState(false);

  // 检索调试
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(5);
  const [minScore, setMinScore] = useState(0.2);
  const [results, setResults] = useState<RagSearchResult[] | null>(null);
  const [searching, setSearching] = useState(false);

  const [deleting, setDeleting] = useState<RagDocument | null>(null);
  const [reindexing, setReindexing] = useState<RagDocument | null>(null);

  // 无知识库时同步默认值
  useEffect(() => {
    if (!currentKb && knowledgeBaseIds.length > 0) {
      setCurrentKb(knowledgeBaseIds[0]);
    }
    if (knowledgeBaseIds.length === 0) {
      setCurrentKb('');
    }
  }, [knowledgeBaseIds, currentKb]);

  const loadDocs = async (kb: string) => {
    if (!kb) return;
    setLoading(true);
    setError('');
    try {
      setDocs(await ragApi.list(kb));
    } catch (err) {
      setError((err as Error).message);
      setDocs([]);
    } finally {
      setLoading(false);
    }
  };

  // 当前知识库变化 → 重新加载文档
  useEffect(() => {
    void loadDocs(currentKb);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentKb]);

  const addKb = () => {
    const id = kbInput.trim();
    if (!id) return;
    addKnowledgeBase(id);
    setKbInput('');
    setCurrentKb(id);
  };

  const ingest = async () => {
    if (!currentKb || !file) return;
    setIngesting(true);
    setError('');
    try {
      await ragApi.upload(currentKb, file, docId, docName);
      setDocName('');
      setDocId('');
      setFile(null);
      await loadDocs(currentKb);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setIngesting(false);
    }
  };

  const doReindex = async (doc: RagDocument) => {
    setReindexing(doc);
    setError('');
    try {
      await ragApi.reindex(doc.knowledgeBaseId, doc.documentId);
      await loadDocs(currentKb);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setReindexing(null);
    }
  };

  const doDelete = async () => {
    if (!deleting) return;
    setError('');
    try {
      await ragApi.remove(deleting.knowledgeBaseId, deleting.documentId);
      await loadDocs(currentKb);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setDeleting(null);
    }
  };

  const doSearch = async () => {
    if (!query.trim()) return;
    setSearching(true);
    setError('');
    try {
      setResults(
        await ragApi.search({
          knowledgeBaseIds,
          text: query.trim(),
          topK,
          minScore,
        }),
      );
    } catch (err) {
      setError((err as Error).message);
      setResults([]);
    } finally {
      setSearching(false);
    }
  };

  return (
    <div className="page rag-page">
      <div className="page-head">
        <h2>知识库 RAG</h2>
        <div className="page-head-actions">
          <span className="text-secondary">独立知识库 · 写入 + 检索 + 上下文注入</span>
          <Button size="sm" icon={RefreshCw} onClick={() => void loadDocs(currentKb)}>
            刷新
          </Button>
        </div>
      </div>

      {error ? <div className="alert alert-error">{error}</div> : null}

      {/* 知识库选择 */}
      <Card title="知识库" className="kb-select-card">
        <div className="kb-list">
          {knowledgeBaseIds.length === 0 ? (
            <Empty text="尚未添加知识库，输入知识库 ID 后回车添加" />
          ) : (
            knowledgeBaseIds.map((id) => (
              <button
                key={id}
                className={`kb-chip${id === currentKb ? ' active' : ''}`}
                onClick={() => setCurrentKb(id)}
                title="切换当前知识库"
              >
                <PackageOpen size={14} />
                {id}
              </button>
            ))
          )}
        </div>
        <div className="kb-add-form">
          <input
            value={kbInput}
            placeholder="知识库 ID（如 product-docs）"
            spellCheck={false}
            onChange={(e) => setKbInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') addKb();
            }}
          />
          <Button variant="primary" icon={Plus} onClick={addKb} disabled={!kbInput.trim()}>
            添加
          </Button>
        </div>
      </Card>

      {/* 文档摄入 + 列表 */}
      <div className="rag-cols">
        <Card title="文档摄入" className="ingest-card">
          <div className="ingest-form">
            <label className="form-field">
              <span className="form-label">知识库</span>
              <input value={currentKb} readOnly disabled placeholder="先在上方选择" />
            </label>
            <label className="form-field">
              <span className="form-label">文档 ID（可选）</span>
              <input
                value={docId}
                placeholder="留空自动生成"
                spellCheck={false}
                onChange={(e) => setDocId(e.target.value)}
              />
            </label>
            <label className="form-field">
              <span className="form-label">文档名称（可选）</span>
              <input
                value={docName}
                placeholder="展示用名称"
                onChange={(e) => setDocName(e.target.value)}
              />
            </label>
            <label className="form-field form-field-wide">
              <span className="form-label">文档文件</span>
              <input
                type="file"
                accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              {file ? (
                <span className="text-faint">
                  {file.name}（{(file.size / 1024).toFixed(1)} KB）
                </span>
              ) : (
                <span className="text-faint">
                  支持 Markdown / 纯文本 / PDF / Word(.docx)，提交后自动解析 → 切分 → 向量化 → 写入 PGVector，无大小限制
                </span>
              )}
            </label>
            <Button
              variant="primary"
              icon={FilePlus2}
              disabled={!currentKb || !file || ingesting}
              onClick={() => void ingest()}
            >
              {ingesting ? '上传中…' : '上传文档'}
            </Button>
          </div>
        </Card>

        <Card
          title={`文档列表${currentKb ? ` · ${currentKb}` : ''}`}
          className="docs-card"
        >
          {loading ? (
            <Loading text="加载文档…" />
          ) : !currentKb ? (
            <Empty text="先添加并选择知识库" />
          ) : docs && docs.length === 0 ? (
            <Empty text="该知识库暂无文档" />
          ) : (
            <div className="doc-list">
              {(docs || []).map((doc) => (
                <div key={doc.documentId} className="doc-row">
                  <div className="doc-head">
                    <Tag tone={STATUS_TONE[doc.status] ?? 'default'}>{doc.status}</Tag>
                    <span className="doc-name">{doc.name || doc.documentId}</span>
                    <span className="mono doc-id">{doc.documentId}</span>
                  </div>
                  <div className="doc-meta text-faint">
                    v{doc.version} · {doc.chunkCount} chunks ·{' '}
                    {formatDateTime(doc.updateTime)}
                  </div>
                  {doc.lastError ? (
                    <div className="doc-error">{doc.lastError}</div>
                  ) : null}
                  <div className="doc-actions">
                    <Button
                      size="sm"
                      variant="ghost"
                      icon={RotateCw}
                      disabled={reindexing?.documentId === doc.documentId}
                      onClick={() => void doReindex(doc)}
                    >
                      {reindexing?.documentId === doc.documentId ? '重建中…' : '重建索引'}
                    </Button>
                    <Button
                      size="sm"
                      variant="danger"
                      icon={Trash2}
                      onClick={() => setDeleting(doc)}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      {/* 检索调试 */}
      <Card title="检索调试" className="rag-search-card">
        <div className="search-form">
          <input
            value={query}
            placeholder="输入查询文本，测试独立 RAG 向量检索"
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
          <select value={minScore} onChange={(e) => setMinScore(Number(e.target.value))}>
            {[0, 0.1, 0.2, 0.3, 0.5].map((n) => (
              <option key={n} value={n}>
                ≥ {n}
              </option>
            ))}
          </select>
          <Button
            variant="primary"
            icon={Search}
            disabled={!query.trim() || searching}
            onClick={() => void doSearch()}
          >
            检索
          </Button>
        </div>
        {searching ? <Loading text="检索中…" /> : null}
        {results ? (
          results.length === 0 ? (
            <Empty text="无召回结果（确认已摄入文档且 Embedding 可用）" />
          ) : (
            <div className="search-results">
              {results.map((r, i) => (
                <div key={`${r.chunkId}-${i}`} className="fact-row">
                  <div className="fact-head">
                    <Tag tone="success">RAG</Tag>
                    <span className="mono fact-key">{r.knowledgeBaseId}</span>
                    <span className="text-faint">{r.score.toFixed(4)}</span>
                    <span className="mono text-faint">#{r.sequence}</span>
                  </div>
                  <div className="fact-content">{r.content}</div>
                  <div className="fact-meta text-faint">
                    来源：{r.documentId} · chunk {r.chunkId}
                  </div>
                </div>
              ))}
            </div>
          )
        ) : null}
      </Card>

      {/* 扩展能力说明 */}
      <Card title="扩展能力（example-web 演示）" className="ext-card">
        <div className="ext-grid">
          <div className="ext-item">
            <div className="ext-head">
              <Boxes size={16} />
              <span>替换扩展点：RagChunker</span>
            </div>
            <p className="ext-desc">
              example-web 注册了自定义{' '}
              <code>ExampleRagChunker</code>（装饰默认{' '}
              <code>TextRagChunker</code>），每个分块元数据追加{' '}
              <code>extension=example-web-custom-chunker</code>
              。框架用 <code>@ConditionalOnMissingBean</code> 保证自定义 Bean 覆盖默认实现。
            </p>
          </div>
          <div className="ext-item">
            <div className="ext-head">
              <Boxes size={16} />
              <span>增强扩展点：RagReranker</span>
            </div>
            <p className="ext-desc">
              example-web 注册了可选 <code>ExampleRagReranker</code>
              ：向量检索后在业务侧按分数二次排序截取 topK 并输出日志，展示「可选重排」扩展能力。
            </p>
          </div>
        </div>
        <p className="ext-tip text-faint">
          提示：对话前在输入框上方添加知识库并选中，对话时后端会将命中内容作为
          “知识库参考”注入 system prompt；RAG 与记忆完全隔离（独立存储与配置）。
        </p>
      </Card>

      <ConfirmDialog
        open={deleting != null}
        title="删除文档"
        message={`确定删除文档「${deleting?.name || deleting?.documentId}」及其索引？此操作不可恢复。`}
        confirmText="删除"
        danger
        onConfirm={() => void doDelete()}
        onCancel={() => setDeleting(null)}
      />
    </div>
  );
}
