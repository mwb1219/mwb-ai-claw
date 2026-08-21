import { create } from 'zustand';

/** 推理轨迹步骤（按 [Thought]/[Action]/[Observation] 分类着色） */
export interface TraceStep {
  id: string;
  raw: string;
  type: 'thought' | 'action' | 'observation' | 'error';
  label: string;
  body: string;
}

/** 工具调用片段（供轨迹区展示） */
export interface ToolCallItem {
  id: string;
  name: string;
  args: string;
}

interface ChatState {
  /** 是否正在流式对话 */
  busy: boolean;
  /** 流式期间当前助手气泡的累计纯文本 */
  streamingContent: string;
  /** 是否有进行中的助手气泡 */
  streaming: boolean;
  traceSteps: TraceStep[];
  toolCalls: ToolCallItem[];
  /** 全局状态条消息 */
  status: { text: string; type: 'ok' | 'err' | 'busy' | '' };
  setBusy(busy: boolean): void;
  setStreamingContent(content: string): void;
  appendStreamingContent(delta: string): void;
  setStreaming(streaming: boolean): void;
  addTraceStep(raw: string): void;
  clearTrace(): void;
  startToolCall(name: string): void;
  appendToolArgs(delta: string): void;
  clearToolCalls(): void;
  setStatus(text: string, type?: 'ok' | 'err' | 'busy' | ''): void;
}

let traceSeq = 0;
let toolSeq = 0;

/** 解析 step 文本：[Thought]/[Action]/[Observation] 分类 */
function classifyStep(raw: string): Pick<TraceStep, 'type' | 'label' | 'body'> {
  const m = raw.match(/^\[([A-Za-z]+)\]\s*(.*)$/s);
  if (m) {
    const tag = m[1].toLowerCase();
    const body = m[2];
    if (tag.startsWith('action')) return { type: 'action', label: 'ACTION', body };
    if (tag.startsWith('obs')) return { type: 'observation', label: 'OBSERVATION', body };
    if (tag.startsWith('thought')) return { type: 'thought', label: 'THOUGHT', body };
  }
  if (/error|失败|异常/i.test(raw)) return { type: 'error', label: 'ERROR', body: raw };
  return { type: 'thought', label: 'THOUGHT', body: raw };
}

export const useChatStore = create<ChatState>((set) => ({
  busy: false,
  streamingContent: '',
  streaming: false,
  traceSteps: [],
  toolCalls: [],
  status: { text: '就绪', type: '' },

  setBusy: (busy) => set({ busy }),
  setStreamingContent: (content) => set({ streamingContent: content }),
  appendStreamingContent: (delta) =>
    set((state) => ({ streamingContent: state.streamingContent + delta })),
  setStreaming: (streaming) => set({ streaming }),

  addTraceStep: (raw) =>
    set((state) => {
      const cls = classifyStep(raw);
      return {
        traceSteps: [...state.traceSteps, { id: `tr-${++traceSeq}`, raw, ...cls }],
      };
    }),
  clearTrace: () => set({ traceSteps: [], toolCalls: [] }),

  startToolCall: (name) =>
    set((state) => ({ toolCalls: [...state.toolCalls, { id: `tl-${++toolSeq}`, name, args: '' }] })),
  appendToolArgs: (delta) =>
    set((state) => {
      const toolCalls = [...state.toolCalls];
      if (toolCalls.length === 0) {
        toolCalls.push({ id: `tl-${++toolSeq}`, name: 'tool', args: '' });
      }
      toolCalls[toolCalls.length - 1] = {
        ...toolCalls[toolCalls.length - 1],
        args: toolCalls[toolCalls.length - 1].args + delta,
      };
      return { toolCalls };
    }),
  clearToolCalls: () => set({ toolCalls: [] }),

  setStatus: (text, type = '') => set({ status: { text, type } }),
}));
