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
  /** 是否已归档：true 表示已滚出热窗、进入跨会话档案（会话详情返回，供展示「归档历史」分隔线） */
  archived?: boolean;
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
  /** 独立 RAG：本次对话注入的知识库列表（缺省取 settings 持久化的选择） */
  knowledgeBaseIds?: string[];
}

export interface StreamHandle {
  close(): void;
}

// ==================== 独立 RAG 知识库 ====================
// 后端来源：mwb-ai-claw-domain（rag/*）、mwb-ai-claw-adapter（RagController /rag/**）

export type RagDocumentStatus = 'PROCESSING' | 'READY' | 'FAILED';

/** RAG 文档及其索引状态 */
export interface RagDocument {
  documentId: string;
  knowledgeBaseId: string;
  name: string;
  contentType: string;
  checksum?: string;
  version: number;
  chunkCount: number;
  status: RagDocumentStatus;
  sourceContent?: string;
  lastError?: string;
  metadata?: Record<string, string>;
  createTime: number;
  updateTime: number;
}

/** 文档摄入命令 */
export interface RagIngestionCommand {
  knowledgeBaseId?: string;
  documentId?: string;
  name?: string;
  contentType?: string;
  content: string;
  metadata?: Record<string, string>;
}

/** 文档摄入结果 */
export interface RagIngestionResult {
  knowledgeBaseId: string;
  documentId: string;
  version: number;
  chunkCount: number;
  skipped: boolean;
  status: RagDocumentStatus;
}

/** 检索请求 */
export interface RagQuery {
  knowledgeBaseIds: string[];
  text: string;
  topK?: number;
  minScore?: number;
  filters?: Record<string, string>;
}

/** 检索命中及引用信息 */
export interface RagSearchResult {
  knowledgeBaseId: string;
  documentId: string;
  documentVersion: number;
  chunkId: string;
  sequence: number;
  content: string;
  score: number;
  metadata?: Record<string, string>;
}

// ==================== 可观测性（运行记录 + 全链路 trace） ====================
// 后端来源：mwb-ai-claw-domain（observability/*）、mwb-ai-claw-adapter（RunUsageController /runs、TraceController /trace）

/** 一次 Agent 运行的用量摘要（运行记录列表项；按当前登录身份 tenantId/userId 隔离返回） */
export interface RunUsage {
  ts?: string;
  tenantId?: string;
  userId?: string;
  traceId?: string;
  sessionId?: string;
  agentId?: string;
  orchestration?: string;
  model?: string;
  durationMs?: number;
  success?: boolean;
  steps?: number;
  errorCode?: string;
}

/** 步骤级 trace 单元 */
export interface TraceStep {
  index: number;
  type: string; // thought | action | observation | info | step
  content: string;
}

/** 一次 Agent 运行的全链路 trace（可还原逐步 Thought / Action / Observation） */
export interface TraceRun {
  traceId: string;
  tenantId?: string;
  userId?: string;
  sessionId?: string;
  agentId?: string;
  orchestration?: string;
  model?: string;
  startTime?: number;
  durationMs?: number;
  success?: boolean;
  errorCode?: string;
  steps: TraceStep[];
}
