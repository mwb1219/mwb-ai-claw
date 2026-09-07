import { useEffect, useRef, useState } from 'react';
import { ChevronDown, Search } from 'lucide-react';

/** 下拉选择项：value 为实际值（如路径），label 为展示文案 */
export interface SelectOption {
  value: string;
  label: string;
}

interface FilterableSelectProps {
  options: SelectOption[];
  /** 当前值（受控） */
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** 无可选项时的提示 */
  emptyText?: string;
  /** 是否允许手动输入自定义值（默认允许，作为自由文本路径） */
  allowCustom?: boolean;
  disabled?: boolean;
}

/**
 * 下拉 + 关键字筛选的组合输入框（combobox）：
 * - 输入框显示当前值，聚焦展开下拉；
 * - 下拉顶部有关键字筛选框，实时过滤选项；
 * - 点选选项直接回填；allowCustom 时输入框也可自由输入。
 */
export function FilterableSelect({
  options,
  value,
  onChange,
  placeholder,
  emptyText = '暂无匹配项，可手动输入',
  allowCustom = true,
  disabled = false,
}: FilterableSelectProps) {
  const [open, setOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [highlight, setHighlight] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);

  const k = keyword.trim().toLowerCase();
  const filtered = k
    ? options.filter((o) => o.label.toLowerCase().includes(k) || o.value.toLowerCase().includes(k))
    : options;

  // 点击组件外部时关闭下拉
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const pick = (v: string) => {
    onChange(v);
    setOpen(false);
    setKeyword('');
    setHighlight(-1);
  };

  const openList = () => {
    setOpen(true);
    setKeyword(value);
    setHighlight(-1);
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!open) return openList();
      setHighlight((h) => (h + 1) % Math.max(filtered.length, 1));
      return;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) return openList();
      setHighlight((h) => (h - 1 + Math.max(filtered.length, 1)) % Math.max(filtered.length, 1));
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (highlight >= 0 && filtered[highlight]) {
        pick(filtered[highlight].value);
      } else if (allowCustom && value.trim()) {
        setOpen(false);
      }
      return;
    }
    if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div className="fselect" ref={rootRef}>
      <div className="fselect-input-wrap">
        <input
          className="fselect-input"
          value={value}
          disabled={disabled}
          placeholder={placeholder}
          spellCheck={false}
          onFocus={openList}
          onChange={(e) => {
            const v = e.target.value;
            if (allowCustom) onChange(v);
            setKeyword(v);
            setOpen(true);
            setHighlight(-1);
          }}
          onKeyDown={onKey}
        />
        <button
          type="button"
          className="fselect-toggle"
          tabIndex={-1}
          aria-label="展开列表"
          onClick={openList}
        >
          <ChevronDown size={16} />
        </button>
      </div>

      {open ? (
        <div className="fselect-panel">
          <div className="fselect-filter">
            <Search size={13} />
            <input
              autoFocus
              value={keyword}
              placeholder="输入关键字筛选…"
              onChange={(e) => {
                setKeyword(e.target.value);
                setHighlight(-1);
              }}
            />
          </div>
          <div className="fselect-list">
            {filtered.length === 0 ? (
              <div className="fselect-empty">{emptyText}</div>
            ) : (
              filtered.map((o, i) => (
                <div
                  key={o.value}
                  className={`fselect-option${o.value === value ? ' selected' : ''}${i === highlight ? ' active' : ''}`}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pick(o.value);
                  }}
                  onMouseEnter={() => setHighlight(i)}
                >
                  <span className="fselect-option-label">{o.label}</span>
                </div>
              ))
            )}
          </div>
          {value && allowCustom ? (
            <button
              type="button"
              className="fselect-clear"
              onClick={() => onChange('')}
            >
              清除
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
