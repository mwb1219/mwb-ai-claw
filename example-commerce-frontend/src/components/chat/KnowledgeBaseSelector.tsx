import { useState } from 'react';
import { BookOpen, Plus, X } from 'lucide-react';

import { useSettings } from '../../store/settings';

/** 对话知识库选择条：选中知识库经 SSE 透传后端，注入 system prompt 的知识库参考 */
export function KnowledgeBaseSelector() {
  const knowledgeBaseIds = useSettings((s) => s.knowledgeBaseIds);
  const addKnowledgeBase = useSettings((s) => s.addKnowledgeBase);
  const removeKnowledgeBase = useSettings((s) => s.removeKnowledgeBase);
  const [input, setInput] = useState('');

  const add = () => {
    const id = input.trim();
    if (!id) return;
    addKnowledgeBase(id);
    setInput('');
  };

  return (
    <div className="kb-selector">
      <span className="kb-selector-label" title="对话时注入知识库参考">
        <BookOpen size={14} /> 知识库
      </span>
      {knowledgeBaseIds.length === 0 ? (
        <span className="text-faint">未选择，对话不会注入知识库</span>
      ) : (
        <div className="kb-selector-tags">
          {knowledgeBaseIds.map((id) => (
            <span key={id} className="kb-tag">
              {id}
              <button
                className="kb-tag-x"
                title="移除"
                aria-label={`移除知识库 ${id}`}
                onClick={() => removeKnowledgeBase(id)}
              >
                <X size={12} />
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="kb-selector-add">
        <input
          value={input}
          placeholder="知识库 ID"
          spellCheck={false}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') add();
          }}
        />
        <button
          className="btn-icon"
          title="添加知识库"
          aria-label="添加知识库"
          onClick={add}
          disabled={!input.trim()}
        >
          <Plus size={14} />
        </button>
      </div>
    </div>
  );
}
