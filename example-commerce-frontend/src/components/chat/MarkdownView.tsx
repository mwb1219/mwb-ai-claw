import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Check, Copy } from 'lucide-react';
import type { Components } from 'react-markdown';

/** 代码块：语法高亮简化（主题色）+ 一键复制 */
function CodeBlock({ className, children }: { className?: string; children?: React.ReactNode }) {
  const [copied, setCopied] = useState(false);
  const lang = (className || '').replace(/^language-/, '');
  const code = String(children ?? '').replace(/\n$/, '');

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard 不可用时忽略 */
    }
  };

  return (
    <div className="code-block">
      <div className="code-block-head">
        <span className="code-block-lang">{lang || 'code'}</span>
        <button type="button" className="code-copy-btn" onClick={copy}>
          {copied ? <Check size={13} /> : <Copy size={13} />}
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre className="code-block-pre">
        <code className={className}>{code}</code>
      </pre>
    </div>
  );
}

/** Markdown 渲染：表格/列表（GFM）+ 代码块复制 */
export function MarkdownView({ content }: { content: string }) {
  const components: Components = {
    pre: ({ children }) => <>{children}</>,
    code: ({ className, children }) => {
      // 块级代码（带语言标记）→ CodeBlock；行内代码 → 原生样式
      if (className) {
        return <CodeBlock className={className}>{children}</CodeBlock>;
      }
      return <code>{children}</code>;
    },
    a: ({ href, children }) => (
      <a href={href} target="_blank" rel="noreferrer">
        {children}
      </a>
    ),
  };

  return (
    <div className="md-body">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {content}
      </ReactMarkdown>
    </div>
  );
}
