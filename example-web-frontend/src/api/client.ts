import { useSettings } from '../store/settings';
import type {
  ApprovalCmd,
  AuthRequest,
  CreateSessionCmd,
  MemoryOverview,
  MemoryPage,
  PendingApprovalDTO,
  RagDocument,
  RagIngestionCommand,
  RagIngestionResult,
  RagQuery,
  RagSearchResult,
  SessionDTO,
  SingleResponse,
  UserDTO,
  UserInfoDTO,
} from './types';

/** API 调用错误（带后端 errCode / errMessage） */
export class ApiError extends Error {
  errCode?: string;

  constructor(message: string, errCode?: string) {
    super(message);
    this.name = 'ApiError';
    this.errCode = errCode;
  }
}

/** 解析后端地址：settings.baseUrl（去尾斜杠）→ 相对路径 */
export function resolveBaseUrl(): string {
  const { baseUrl } = useSettings.getState();
  return baseUrl.replace(/\/+$/, '');
}

/** 鉴权头：配置了 apiKey 时附加 X-API-Key */
function authHeaders(extra?: HeadersInit): HeadersInit {
  const { apiKey } = useSettings.getState();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(extra as Record<string, string>),
  };
  if (apiKey) {
    headers['X-API-Key'] = apiKey;
  }
  return headers;
}

/** 统一 REST 请求：解包 SingleResponse，success=false 抛 ApiError */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(resolveBaseUrl() + path, {
    ...init,
    headers: authHeaders(init?.headers),
  });
  let body: SingleResponse<T>;
  try {
    body = (await resp.json()) as SingleResponse<T>;
  } catch {
    throw new ApiError(`服务返回非 JSON 响应（HTTP ${resp.status}）`);
  }
  if (!resp.ok || !body.success) {
    const errCode =
      body.errCode ||
      (resp.status === 401 || resp.status === 403 ? 'UNAUTHORIZED' : undefined);
    throw new ApiError(body.errMessage || `请求失败（HTTP ${resp.status}）`, errCode);
  }
  return body.data as T;
}

// ==================== 会话管理 ====================

export const sessionApi = {
  list(): Promise<SessionDTO[]> {
    return request<SessionDTO[]>('/agent/sessions');
  },
  get(sessionId: string): Promise<SessionDTO> {
    return request<SessionDTO>(`/agent/session/${encodeURIComponent(sessionId)}`);
  },
  create(cmd: CreateSessionCmd = {}): Promise<SessionDTO> {
    return request<SessionDTO>('/agent/session', {
      method: 'POST',
      body: JSON.stringify(cmd),
    });
  },
  remove(sessionId: string): Promise<void> {
    return request<void>(`/agent/session/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
    });
  },
  rename(sessionId: string, title: string): Promise<SessionDTO> {
    return request<SessionDTO>(`/agent/session/${encodeURIComponent(sessionId)}`, {
      method: 'PUT',
      body: JSON.stringify({ title }),
    });
  },
  duplicate(sessionId: string): Promise<SessionDTO> {
    return request<SessionDTO>(`/agent/session/${encodeURIComponent(sessionId)}/duplicate`, {
      method: 'POST',
    });
  },
};

// ==================== 人工审批 ====================

export const approvalApi = {
  pendingTasks(sessionId?: string): Promise<PendingApprovalDTO[]> {
    const qs = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : '';
    return request<PendingApprovalDTO[]>(`/agent/pending-tasks${qs}`);
  },
  approve(cmd: ApprovalCmd): Promise<void> {
    return request<void>('/agent/approve', {
      method: 'POST',
      body: JSON.stringify({ ...cmd, action: 'approve' }),
    });
  },
  reject(cmd: ApprovalCmd): Promise<void> {
    return request<void>('/agent/reject', {
      method: 'POST',
      body: JSON.stringify({ ...cmd, action: 'reject' }),
    });
  },
};

// ==================== 用户注册 / 登录 ====================

export const authApi = {
  register(req: AuthRequest): Promise<UserDTO> {
    return request<UserDTO>('/auth/register', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  },
  login(req: AuthRequest): Promise<UserDTO> {
    return request<UserDTO>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  },
};

// ==================== 用户 ====================

export const userApi = {
  current(): Promise<UserInfoDTO> {
    return request<UserInfoDTO>('/user/current');
  },
};

// ==================== 记忆可视化 ====================

export const memoryApi = {
  overview(): Promise<MemoryOverview> {
    return request<MemoryOverview>('/memory');
  },
  facts(): Promise<MemoryPage[]> {
    return request<MemoryPage[]>('/memory/facts');
  },
  summaries(sessionId?: string): Promise<MemoryPage[]> {
    const qs = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : '';
    return request<MemoryPage[]>(`/memory/summaries${qs}`);
  },
  archive(sessionId?: string): Promise<MemoryPage[]> {
    const qs = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : '';
    return request<MemoryPage[]>(`/memory/archive${qs}`);
  },
  search(q: string, topK = 5): Promise<MemoryPage[]> {
    return request<MemoryPage[]>(
      `/memory/search?q=${encodeURIComponent(q)}&topK=${topK}`,
    );
  },
};

// ==================== 独立 RAG 知识库 ====================

export const ragApi = {
  /** 列出指定知识库下的全部文档 */
  list(knowledgeBaseId: string): Promise<RagDocument[]> {
    return request<RagDocument[]>(
      `/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`,
    );
  },
  /** 摄入文档：解析 → 切分 → 向量化 → 写入索引 */
  ingest(knowledgeBaseId: string, command: RagIngestionCommand): Promise<RagIngestionResult> {
    return request<RagIngestionResult>(
      `/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`,
      { method: 'POST', body: JSON.stringify(command) },
    );
  },
  /** 重建指定文档索引 */
  reindex(knowledgeBaseId: string, documentId: string): Promise<RagIngestionResult> {
    return request<RagIngestionResult>(
      `/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents/${encodeURIComponent(documentId)}/reindex`,
      { method: 'POST' },
    );
  },
  /** 删除文档及其索引 */
  remove(knowledgeBaseId: string, documentId: string): Promise<void> {
    return request<void>(
      `/rag/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents/${encodeURIComponent(documentId)}`,
      { method: 'DELETE' },
    );
  },
  /** 独立 RAG 检索 */
  search(query: RagQuery): Promise<RagSearchResult[]> {
    return request<RagSearchResult[]>('/rag/search', {
      method: 'POST',
      body: JSON.stringify(query),
    });
  },
};
