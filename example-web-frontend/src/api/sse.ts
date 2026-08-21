import { useSettings } from '../store/settings';
import { resolveBaseUrl } from './client';
import type { StreamCallbacks, StreamHandle, StreamRequest } from './types';

/**
 * SSE 客户端（双模式）：GET /agent/chat/stream?message=&sessionId=&agentId=
 *
 * - EventSource 模式：鉴权走 ?apiKey= 查询参数（AuthInterceptor 支持），浏览器自动处理多行 data: 拼接与断线；
 * - fetch 模式：需要自定义头（X-API-Key / Authorization: Bearer）或主动中断（AbortController）时使用，
 *   手写 SSE 帧解析（event: / data: 分帧，多行 data 用 \n 拼接）。
 */

/** 构造 SSE URL：baseUrl + path + 查询参数（含 apiKey 兜底） */
function buildUrl(cmd: StreamRequest): string {
  const { apiKey } = useSettings.getState();
  const params = new URLSearchParams({ message: cmd.message });
  if (cmd.sessionId) params.set('sessionId', cmd.sessionId);
  if (cmd.agentId) params.set('agentId', cmd.agentId);
  if (apiKey) params.set('apiKey', apiKey);
  return `${resolveBaseUrl()}/agent/chat/stream?${params.toString()}`;
}

/** 按名称分发事件到回调 */
function dispatch(cb: StreamCallbacks, name: string, data: string) {
  switch (name) {
    case 'session':
      cb.onSession?.(data);
      break;
    case 'step':
      cb.onStep?.(data);
      break;
    case 'token':
      cb.onToken?.(data);
      break;
    case 'tool_name':
      cb.onToolName?.(data);
      break;
    case 'tool_args':
      cb.onToolArgs?.(data);
      break;
    case 'reply':
      cb.onReply?.(data);
      break;
    case 'done':
      cb.onDone?.();
      break;
    case 'error':
      cb.onError?.(data);
      break;
    default:
      break;
  }
}

/** EventSource 模式 */
function withEventSource(cmd: StreamRequest, cb: StreamCallbacks): StreamHandle {
  const es = new EventSource(buildUrl(cmd));
  let finished = false;

  const finish = () => {
    if (!finished) {
      finished = true;
      es.close();
    }
  };

  es.addEventListener('session', (e) => cb.onSession?.((e as MessageEvent).data));
  es.addEventListener('step', (e) => cb.onStep?.((e as MessageEvent).data));
  es.addEventListener('token', (e) => cb.onToken?.((e as MessageEvent).data));
  es.addEventListener('tool_name', (e) => cb.onToolName?.((e as MessageEvent).data));
  es.addEventListener('tool_args', (e) => cb.onToolArgs?.((e as MessageEvent).data));
  es.addEventListener('reply', (e) => cb.onReply?.((e as MessageEvent).data));
  es.addEventListener('done', () => {
    finish();
    cb.onDone?.();
  });
  es.addEventListener('error', () => {
    // done 已触发后的 error 事件为正常关闭，忽略
    if (!finished) {
      finish();
      cb.onError?.('流式连接异常，请检查后端地址与网络');
    }
  });

  return { close: finish };
}

/** fetch + ReadableStream 模式：支持自定义鉴权头与主动中断 */
async function withFetch(cmd: StreamRequest, cb: StreamCallbacks): Promise<StreamHandle> {
  const { apiKey } = useSettings.getState();
  const controller = new AbortController();

  const headers: Record<string, string> = { Accept: 'text/event-stream' };
  if (apiKey) headers['X-API-Key'] = apiKey;

  const resp = await fetch(buildUrl(cmd), {
    headers,
    signal: controller.signal,
    cache: 'no-store',
  });

  if (!resp.ok || !resp.body) {
    cb.onError?.(`流式请求失败（HTTP ${resp.status}）`);
    return { close: () => controller.abort() };
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];
  let doneFired = false;

  const dispatchFrame = () => {
    const data = dataLines.join('\n');
    dataLines = [];
    if (data === '' && eventName === 'message') {
      return; // 心跳 / 空帧
    }
    dispatch(cb, eventName, data);
    if (eventName === 'done') {
      doneFired = true;
    }
  };

  const pump = async (): Promise<void> => {
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // 按空行分帧（SSE 规范：\n\n）
        let idx: number;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) {
              eventName = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
              dataLines.push(line.slice(5).trimStart());
            } else if (line === '') {
              // 帧内空行忽略
            }
            // 注释行（: xxx）与未知行忽略
          }
          dispatchFrame();
        }
      }
      // 处理尾部残余帧（无空行结尾）
      if (buffer.trim().length > 0) {
        for (const line of buffer.split('\n')) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim();
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
        }
        dispatchFrame();
      }
      if (!doneFired) {
        cb.onDone?.();
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        cb.onError?.((err as Error).message || '流式读取异常');
      }
    }
  };

  void pump();
  return { close: () => controller.abort() };
}

/**
 * 发起流式对话。
 * mode=auto：配置了 apiKey（需自定义头）时走 fetch，否则 EventSource。
 */
export function chatStream(
  cmd: StreamRequest,
  cb: StreamCallbacks,
  mode: 'auto' | 'event-source' | 'fetch' = 'auto',
): StreamHandle {
  const { apiKey } = useSettings.getState();
  const effectiveMode = mode === 'auto' ? (apiKey ? 'fetch' : 'event-source') : mode;
  if (effectiveMode === 'fetch') {
    let handle: StreamHandle | null = null;
    void withFetch(cmd, cb).then((h) => {
      handle = h;
    });
    return {
      close: () => handle?.close(),
    };
  }
  return withEventSource(cmd, cb);
}
