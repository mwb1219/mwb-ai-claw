import { useEffect, useState } from 'react';
import {
  FlaskConical,
  GitCompare,
  Play,
  RefreshCw,
  Wand2,
  FileText,
  CheckCircle2,
  XCircle,
} from 'lucide-react';

import { evalApi } from '../api/client';
import type {
  DatasetInfo,
  EvalConfig,
  EvalDiff,
  EvalJudgeType,
  EvalReport,
  EvalRunResult,
  GenerateDatasetResult,
} from '../api/types';
import { Button } from '../components/common/Button';
import { Card } from '../components/common/Card';
import { Empty } from '../components/common/Empty';
import { Loading } from '../components/common/Loading';
import { Tag } from '../components/common/Tag';
import { formatDateTime } from '../utils/format';

function formatDuration(ms?: number): string {
  if (ms == null) return '-';
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

function pct(v?: number): string {
  if (v == null) return '-';
  return `${(v * 100).toFixed(1)}%`;
}

function formatTokens(n?: number): string {
  if (n == null) return '-';
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

const JUDGE_OPTIONS = [
  { value: 'both', label: 'both（rule 先过 + LLM 兜底）' },
  { value: 'rule', label: 'rule（零 LLM，低成本确定性回归）' },
  { value: 'llm', label: 'llm（仅语义裁判）' },
];

/**
 * Agent 评测面板（/eval/**）：自动生成数据集 → 选择数据集运行 → 查看结果报告 + 回归对比。
 * - 生成：LLM 按主题产出符合数据集规范的 JSON（task + cases[prompt/expected/rule]）并落盘。
 * - 运行：POST /eval/run，返回完整报告（summary + 逐用例明细）。
 * - 报告/对比：读取 /eval/report 与 /eval/diff。
 */
export function EvalPage() {
  // ============ 数据集列表 ============
  const [datasets, setDatasets] = useState<DatasetInfo[] | null>(null);
  const [datasetsLoading, setDatasetsLoading] = useState(false);

  // ============ 生成数据集 ============
  const [genTopic, setGenTopic] = useState('');
  const [genType, setGenType] = useState('qa');
  const [genCount, setGenCount] = useState(5);
  const [genAgentId, setGenAgentId] = useState('');
  const [genModel, setGenModel] = useState('');
  const [genTaskId, setGenTaskId] = useState('');
  const [genName, setGenName] = useState('');
  const [generating, setGenerating] = useState(false);
  const [generated, setGenerated] = useState<GenerateDatasetResult | null>(null);

  // ============ 运行评测 ============
  const [runDatasetPath, setRunDatasetPath] = useState('');
  const [runAgentId, setRunAgentId] = useState('');
  const [runJudge, setRunJudge] = useState<EvalJudgeType>('both');
  const [runJudgeModel, setRunJudgeModel] = useState('');
  const [running, setRunning] = useState(false);
  const [runResult, setRunResult] = useState<EvalRunResult | null>(null);

  // ============ 报告查看 ============
  const [reportPath, setReportPath] = useState('');
  const [report, setReport] = useState<EvalReport | null>(null);
  const [reportLoading, setReportLoading] = useState(false);

  // ============ 回归对比 ============
  const [diffBaseline, setDiffBaseline] = useState('');
  const [diffCurrent, setDiffCurrent] = useState('');
  const [diff, setDiff] = useState<EvalDiff | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);

  const [error, setError] = useState('');

  const loadDatasets = async () => {
    setDatasetsLoading(true);
    setError('');
    try {
      setDatasets(await evalApi.ls());
    } catch (err) {
      setError((err as Error).message);
      setDatasets([]);
    } finally {
      setDatasetsLoading(false);
    }
  };

  useEffect(() => {
    void loadDatasets();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const generate = async () => {
    if (!genTopic.trim()) {
      setError('请填写数据集主题（topic）');
      return;
    }
    setGenerating(true);
    setError('');
    setGenerated(null);
    try {
      const res = await evalApi.generateDataset({
        topic: genTopic.trim(),
        type: genType.trim() || undefined,
        count: genCount,
        agentId: genAgentId.trim() || undefined,
        model: genModel.trim() || undefined,
        taskId: genTaskId.trim() || undefined,
        name: genName.trim() || undefined,
      });
      setGenerated(res);
      setRunDatasetPath(res.datasetPath);
      void loadDatasets();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setGenerating(false);
    }
  };

  const runEval = async (datasetPath?: string) => {
    const path = datasetPath ?? runDatasetPath;
    if (!path.trim()) {
      setError('请先选择或生成数据集');
      return;
    }
    setRunning(true);
    setError('');
    setRunResult(null);
    const config: EvalConfig = {
      datasetPath: path.trim(),
      agentId: runAgentId.trim() || 'default',
      judge: runJudge,
      judgeModel: runJudgeModel.trim() || undefined,
    };
    try {
      setRunResult(await evalApi.run(config));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setRunning(false);
    }
  };

  const viewReport = async () => {
    if (!reportPath.trim()) {
      setError('请输入报告 JSON 路径（path）');
      return;
    }
    setReportLoading(true);
    setError('');
    setReport(null);
    try {
      setReport(await evalApi.report(reportPath.trim()));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setReportLoading(false);
    }
  };

  const compareDiff = async () => {
    if (!diffBaseline.trim() || !diffCurrent.trim()) {
      setError('请填写 baseline 与 current 报告路径');
      return;
    }
    setDiffLoading(true);
    setError('');
    setDiff(null);
    try {
      setDiff(await evalApi.diff(diffBaseline.trim(), diffCurrent.trim()));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setDiffLoading(false);
    }
  };

  const selectDataset = (info: DatasetInfo) => {
    setRunDatasetPath(info.file);
    if (info.name) setGenName(info.name);
  };

  const genFormDisabled = generating;

  return (
    <div className="page eval-page">
      <div className="page-head">
        <h2>Agent 评测</h2>
        <div className="page-head-actions">
          <span className="text-secondary">
            LLM 自动生成数据集 · 选择数据集运行 · 查看结果与回归对比
          </span>
          <Button size="sm" icon={RefreshCw} disabled={datasetsLoading} onClick={() => void loadDatasets()}>
            刷新
          </Button>
        </div>
      </div>

      {error ? <div className="alert alert-error">{error}</div> : null}

      {/* 自动生成数据集 */}
      <Card
        title="自动生成数据集（LLM）"
        actions={
          <span className="text-faint">
            按主题生成 task + cases[prompt/expected/rule]，落盘为 JSON 可直接运行
          </span>
        }
      >
        <div className="eval-gen-grid">
          <div className="form-field">
            <label>主题 / 领域（必填）</label>
            <input
              value={genTopic}
              placeholder="如：高可用架构 / Spring Boot 事务"
              onChange={(e) => setGenTopic(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label>用例类型</label>
            <input
              value={genType}
              placeholder="qa | math | reasoning | code"
              onChange={(e) => setGenType(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label>用例数量（1-20）</label>
            <input
              type="number"
              min={1}
              max={20}
              value={genCount}
              onChange={(e) => setGenCount(Number(e.target.value))}
            />
          </div>
          <div className="form-field">
            <label>主 Agent id（可选）</label>
            <input
              value={genAgentId}
              placeholder="default"
              onChange={(e) => setGenAgentId(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label>生成模型（可选，覆盖 Agent 配置）</label>
            <input
              value={genModel}
              placeholder="缺省取主 Agent 绑定模型"
              onChange={(e) => setGenModel(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label>数据集 id（可选）</label>
            <input
              value={genTaskId}
              placeholder="缺省按主题生成 slug"
              onChange={(e) => setGenTaskId(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label>数据集显示名（可选）</label>
            <input
              value={genName}
              placeholder="缺省取主题"
              onChange={(e) => setGenName(e.target.value)}
            />
          </div>
        </div>
        <div className="eval-actions">
          <Button
            variant="primary"
            icon={Wand2}
            disabled={!genTopic.trim() || genFormDisabled}
            onClick={() => void generate()}
          >
            {generating ? '生成中…' : '生成数据集'}
          </Button>
          {generated ? (
            <Button
              variant="primary"
              icon={Play}
              disabled={running}
              onClick={() => void runEval(generated.datasetPath)}
            >
              用该数据集直接运行
            </Button>
          ) : null}
        </div>

        {generating ? (
          <Loading text="调用 LLM 生成数据集…" />
        ) : generated ? (
          <div className="gen-result">
            <div className="gen-meta">
              <Tag tone="primary">{generated.taskId}</Tag>
              <span className="mono">{generated.datasetPath}</span>
              <span className="text-faint">
                {generated.caseCount} 用例 · 生成模型 {generated.model || '-'}
              </span>
            </div>
            <div className="gen-cases">
              {generated.dataset.cases.map((c) => (
                <div key={c.id} className="gen-case">
                  <div className="gen-case-head">
                    <span className="gen-case-name">{c.name || c.id}</span>
                    <Tag tone={c.rule?.type === 'contains' ? 'info' : 'default'}>
                      rule: {c.rule?.type} {c.rule?.value}
                    </Tag>
                  </div>
                  <div className="gen-case-prompt">{c.prompt}</div>
                  <div className="gen-case-expected text-faint">期望：{c.expected}</div>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <Empty text="填写主题并点击「生成数据集」，由 LLM 产出可运行的评测数据集" />
        )}
      </Card>

      {/* 数据集列表（选择） */}
      <Card title="可用数据集" actions={<span className="text-faint">点击列表项选用于运行</span>}>
        {datasetsLoading ? (
          <Loading text="加载数据集列表…" />
        ) : datasets && datasets.length === 0 ? (
          <Empty text="暂无数据集，先用上方「生成数据集」或配置数据集目录" />
        ) : (
          <div className="run-list">
            {(datasets || []).map((info) => (
              <div
                key={info.file}
                className={`run-row dataset-row${runDatasetPath === info.file ? ' selected' : ''}`}
                onClick={() => selectDataset(info)}
              >
                <div className="run-head">
                  <Tag tone={info.loaded ? 'primary' : 'danger'}>
                    {info.loaded ? `${info.caseCount} 用例` : '加载失败'}
                  </Tag>
                  <span className="run-orchestration">{info.name || info.taskId}</span>
                  <span className="mono run-trace-id" title={info.taskId}>
                    {info.taskId}
                  </span>
                </div>
                <div className="run-meta text-faint">file: {info.file}</div>
                {!info.loaded && info.error ? <div className="doc-error">{info.error}</div> : null}
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* 运行评测 */}
      <Card title="运行评测" actions={<span className="text-faint">POST /eval/run</span>}>
        <div className="eval-gen-grid">
          <div className="form-field form-field-wide">
            <label>数据集文件路径（datasetPath）</label>
            <input
              value={runDatasetPath}
              placeholder="选择数据集或生成后会自动填入"
              spellCheck={false}
              onChange={(e) => setRunDatasetPath(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label>主 Agent id</label>
            <input
              value={runAgentId}
              placeholder="default"
              onChange={(e) => setRunAgentId(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label>判定策略</label>
            <select value={runJudge} onChange={(e) => setRunJudge(e.target.value as EvalJudgeType)}>
              {JUDGE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          <div className="form-field">
            <label>LLM 裁判模型（可选）</label>
            <input
              value={runJudgeModel}
              placeholder="缺省继承全局 judge-model"
              onChange={(e) => setRunJudgeModel(e.target.value)}
            />
          </div>
        </div>
        <div className="eval-actions">
          <Button variant="primary" icon={Play} disabled={!runDatasetPath.trim() || running} onClick={() => void runEval()}>
            {running ? '运行中…' : '运行评测'}
          </Button>
        </div>

        {running ? (
          <Loading text="执行 Agent 评测（可能耗时，取决于用例数与 LLM 判定）…" />
        ) : runResult ? (
          <div className="run-result">
            <div className="stat-grid">
              <div className="stat-cell">
                <span className="stat-label">CASE 用例</span>
                <span className="stat-value">{runResult.report.summary?.total ?? '-'}</span>
              </div>
              <div className="stat-cell">
                <span className="stat-label">PASSED 通过</span>
                <span className="stat-value stat-ok">{runResult.report.summary?.passed ?? '-'}</span>
              </div>
              <div className="stat-cell">
                <span className="stat-label">FAILED 失败</span>
                <span className="stat-value stat-err">{runResult.report.summary?.failed ?? '-'}</span>
              </div>
              <div className="stat-cell">
                <span className="stat-label">PASS RATE 通过率</span>
                <span className="stat-value">{pct(runResult.report.summary?.passRate)}</span>
              </div>
              <div className="stat-cell">
                <span className="stat-label">AVG 平均耗时</span>
                <span className="stat-value">{formatDuration(runResult.report.summary?.avgDurationMs)}</span>
              </div>
              <div className="stat-cell">
                <span className="stat-label">TOKEN 消耗</span>
                <span className="stat-value">{formatTokens(runResult.report.summary?.totalTokens)}</span>
              </div>
            </div>

            <div className="run-result-meta text-faint">
              {runResult.report.taskName || runResult.report.taskId} · {formatDateTime(runResult.report.runAt)} · agent{' '}
              {runResult.report.meta?.agentId || '-'} · 判定 {runResult.report.meta?.judge || '-'}
              {runResult.jsonPath ? (
                <Button size="sm" variant="ghost" icon={FileText} onClick={() => setReportPath(runResult.jsonPath!)}>
                  查看 JSON 报告
                </Button>
              ) : null}
            </div>

            <div className="run-list">
              {(runResult.report.cases || []).map((c) => (
                <div key={c.caseId} className="run-row">
                  <div className="run-head">
                    {c.passed ? (
                      <Tag tone="success">
                        <CheckCircle2 size={12} /> PASSED
                      </Tag>
                    ) : (
                      <Tag tone="danger">
                        <XCircle size={12} /> FAILED
                      </Tag>
                    )}
                    <span className="run-orchestration">{c.name || c.caseId}</span>
                    <span className="text-faint">
                      judge {c.judge || '-'} · {formatDuration(c.durationMs)} · {formatTokens(c.tokens)} tok
                      {c.score != null ? ` · score ${c.score}` : ''}
                    </span>
                  </div>
                  {c.verdict ? <div className="case-verdict">{c.verdict}</div> : null}
                  {c.reply ? <div className="case-reply text-faint">回复：{c.reply}</div> : null}
                  {c.error ? <div className="doc-error">{c.error}</div> : null}
                </div>
              ))}
            </div>
          </div>
        ) : (
          <Empty text="选择或生成数据集后点击「运行评测」，此处展示结果报告" />
        )}
      </Card>

      {/* 报告查看 + 回归对比 */}
      <Card title="报告与回归对比" actions={<span className="text-faint">GET /eval/report · /eval/diff</span>}>
        <div className="eval-gen-grid">
          <div className="form-field form-field-wide">
            <label>报告 JSON 路径（path，运行后自动回填）</label>
            <input
              value={reportPath}
              placeholder="运行报告生成的 JSON 路径"
              spellCheck={false}
              onChange={(e) => setReportPath(e.target.value)}
            />
          </div>
        </div>
        <div className="eval-actions">
          <Button size="sm" icon={FileText} disabled={!reportPath.trim() || reportLoading} onClick={() => void viewReport()}>
            读取报告
          </Button>
        </div>

        {reportLoading ? (
          <Loading text="读取报告…" />
        ) : report ? (
          <div className="report-detail">
            <div className="run-result-meta text-faint">
              {report.taskName || report.taskId} · {formatDateTime(report.runAt)} · 判定{' '}
              {report.meta?.judge || '-'} · {formatTokens(report.summary?.totalTokens)} tok · 通过率{' '}
              {pct(report.summary?.passRate)}
            </div>
            <div className="run-list">
              {(report.cases || []).map((c) => (
                <div key={c.caseId} className="run-row">
                  <div className="run-head">
                    {c.passed ? <Tag tone="success">PASSED</Tag> : <Tag tone="danger">FAILED</Tag>}
                    <span className="run-orchestration">{c.name || c.caseId}</span>
                    <span className="text-faint">
                      judge {c.judge || '-'} · {formatDuration(c.durationMs)} · {formatTokens(c.tokens)} tok
                    </span>
                  </div>
                  {c.verdict ? <div className="case-verdict">{c.verdict}</div> : null}
                </div>
              ))}
            </div>
          </div>
        ) : (
          <Empty text="输入报告路径读取历史报告" />
        )}

        <div className="eval-diff-section">
          <div className="eval-gen-grid">
            <div className="form-field">
              <label>baseline 报告路径</label>
              <input
                value={diffBaseline}
                placeholder="基线报告 JSON 路径"
                spellCheck={false}
                onChange={(e) => setDiffBaseline(e.target.value)}
              />
            </div>
            <div className="form-field">
              <label>current 报告路径</label>
              <input
                value={diffCurrent}
                placeholder="当前报告 JSON 路径"
                spellCheck={false}
                onChange={(e) => setDiffCurrent(e.target.value)}
              />
            </div>
          </div>
          <div className="eval-actions">
            <Button
              size="sm"
              icon={GitCompare}
              disabled={!diffBaseline.trim() || !diffCurrent.trim() || diffLoading}
              onClick={() => void compareDiff()}
            >
              对比
            </Button>
          </div>

          {diffLoading ? (
            <Loading text="对比报告…" />
          ) : diff ? (
            <div className="diff-detail">
              <div className="stat-grid">
                <div className="stat-cell">
                  <span className="stat-label">BASELINE 通过率</span>
                  <span className="stat-value">{pct(diff.baselinePassRate)}</span>
                </div>
                <div className="stat-cell">
                  <span className="stat-label">CURRENT 通过率</span>
                  <span className="stat-value">{pct(diff.currentPassRate)}</span>
                </div>
                <div className="stat-cell">
                  <span className="stat-label">DELTA 变化</span>
                  <span className={`stat-value ${diff.passRateDelta < 0 ? 'stat-err' : 'stat-ok'}`}>
                    {diff.passRateDelta >= 0 ? '+' : ''}
                    {pct(diff.passRateDelta)}
                  </span>
                </div>
              </div>
              <div className="run-result-meta text-faint">
                {diff.regression ? '判定为回归' : '未判定回归'} · 回归 {diff.regressedCount} · 修复{' '}
                {diff.improvedCount}
              </div>
              <div className="run-list">
                {(diff.items || []).map((it) => (
                  <div key={it.caseId} className="run-row">
                    <div className="run-head">
                      <Tag
                        tone={
                          it.status === 'regressed'
                            ? 'danger'
                            : it.status === 'improved'
                              ? 'success'
                              : it.status === 'new'
                                ? 'warning'
                                : 'default'
                        }
                      >
                        {it.status}
                      </Tag>
                      <span className="run-orchestration">{it.name || it.caseId}</span>
                      <span className="text-faint">
                        baseline {it.baselinePassed ? 'PASS' : 'FAIL'}
                        {it.baselineScore != null ? `(${it.baselineScore})` : ''} → current{' '}
                        {it.currentPassed ? 'PASS' : 'FAIL'}
                        {it.currentScore != null ? `(${it.currentScore})` : ''}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <Empty text="输入两份报告路径进行回归对比" />
          )}
        </div>
      </Card>

      {/* 能力说明 */}
      <Card title="评测能力说明（example-web 演示）" className="ext-card">
        <div className="ext-grid">
          <div className="ext-item">
            <div className="ext-head">
              <FlaskConical size={16} />
              <span>LLM 自动生成数据集</span>
            </div>
            <p className="ext-desc">
              <code>POST /eval/dataset/generate</code> 按主题调用 Agent 模型，产出符合数据集规范（task +
              cases[prompt/expected/rule]）的 JSON，校验后落盘到{' '}
              <code>example.eval.dataset-dir</code>（默认 <code>./generated-datasets</code>），
              返回路径可直接用于 <code>/eval/run</code>。
            </p>
          </div>
          <div className="ext-item">
            <div className="ext-head">
              <FlaskConical size={16} />
              <span>运行 + 报告 + 回归对比</span>
            </div>
            <p className="ext-desc">
              <code>POST /eval/run</code> 执行评测并落盘 JSON/Markdown 报告；<code>GET /eval/report</code>{' '}
              回读报告；<code>GET /eval/diff</code> 对比 baseline/current 通过率，识别回归与修复用例。
            </p>
          </div>
        </div>
      </Card>
    </div>
  );
}
