/**
 * API 类型定义：与后端 DTO 一一对应
 * 后端来源：mwb-ai-claw-client（dto/*）、mwb-ai-claw-domain（memory/MemoryPage）
 * 参考 docs/feature-web-console-frontend技术方案(SUBMIT).md §2.3
 */

/** 统一响应包裹 */
export interface SingleResponse<T> {
  success: boolean;
  errCode?: string;
  errMessage?: string;
  data?: T;
}

// ==================== 对话 ====================

export interface ChatResponseDTO {
  sessionId: string;
  agentId: string;
  orchestrationId: string;
  reply: string;
  traceSteps: string[];
}

// ==================== 会话 ====================

export interface CreateSessionCmd {
  agentId?: string;
  title?: string;
}

export interface SessionDTO {
  sessionId: string;
  agentId: string;
  title: string;
  status: string; // ACTIVE / CLOSED
  createTime: number;
  updateTime: number;
  messages: MessageDTO[];
  /** 推理轨迹步骤（[Thought]/[Action]/[Observation] 文本，按轮累加） */
  traceSteps?: string[];
}

export interface MessageDTO {
  role: string; // user / assistant / system / tool
  content: string;
  timestamp: number;
  /** assistant 消息携带的工具调用（用于刷新后恢复轨迹展示） */
  toolCalls?: ToolCallDTO[];
}

export interface ToolCallDTO {
  id: string;
  name: string;
  arguments: string;
}

// ==================== 用户 ====================

export interface UserDTO {
  username: string;
  name: string;
  apiKey: string;
  tools: string[];
  createdAt: number;
}

export interface AuthRequest {
  username: string;
  password: string;
  name?: string;
}

export interface UserInfoDTO {
  username: string;
  name: string;
  tenantId: string;
  authEnabled: boolean;
}

// ==================== 审批 ====================

export interface ApprovalCmd {
  sessionId: string;
  layerKey: string;
  action?: string; // approve | reject
}

export interface PendingApprovalDTO {
  sessionId: string;
  layerKey: string;
  task: string;
  todoTitles: string[];
  todoCount: number;
  createdAt: number;
}

// ==================== 记忆 ====================

export type MemoryPageType = 'HOT' | 'SUMMARY' | 'FACT' | 'RETRIEVED' | 'ARCHIVE';

export interface MemoryPage {
  pageId: string;
  type: MemoryPageType;
  content: string;
  key?: string;
  importance?: number;
  tokenCount?: number;
  sessionId?: string;
  blockStart?: number;
  blockEnd?: number;
  createTime: number;
  version?: number;
}

export interface MemoryOverview {
  enabled: boolean;
  config: Record<string, unknown>;
  stats: {
    facts: [number, number]; // [count, tokens]
    summaries: [number, number];
    archives: [number, number];
    archiveBySession: Record<string, number>;
  };
  synthesis: {
    cache: Record<string, unknown>;
    pendingTasks: number;
  };
}

// ==================== SSE 流式（GET /agent/chat/stream） ====================

/** SSE 事件回调（与后端事件名一一对应） */
export interface StreamCallbacks {
  onSession?(sessionId: string): void;
  onStep?(step: string): void;
  onToken?(token: string): void;
  onToolName?(toolName: string): void;
  onToolArgs?(delta: string): void;
  onReply?(reply: string): void;
  onDone?(): void;
  onError?(message: string): void;
}

export interface StreamRequest {
  message: string;
  sessionId?: string;
  agentId?: string;
}

export interface StreamHandle {
  close(): void;
}
