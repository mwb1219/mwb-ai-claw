/* mwb-ai-claw Agent 测试控制台 - 交互逻辑 */
'use strict';

const $ = (id) => document.getElementById(id);

// ============ 状态 ============
const state = {
  sessions: new Map(),      // sessionId -> { sessionId, title, messages: [] }
  currentSessionId: null,
  mode: 'sync',             // sync | stream
  busy: false,
  streaming: false,         // 当前是否在流式接收
  currentAssistantBubble: null,  // 流式模式下当前助手消息气泡引用
  currentAssistantContent: '',  // 流式模式下已接收的内容累积
};

// ============ 工具函数 ============
function getBaseUrl() {
  return ($('baseUrl').value || '').replace(/\/$/, '');
}

/** 从 HTTP base URL 推导 WebSocket URL */
function getWsUrl() {
  const http = getBaseUrl();
  return http.replace(/^http/, 'ws') + '/ws/agent';
}

function setStatus(msg, type = '') {
  const bar = $('statusBar');
  bar.textContent = msg;
  bar.className = 'statusbar ' + type;
}

function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function setBusy(busy) {
  state.busy = busy;
  $('sendBtn').disabled = busy;
}

function scrollMessages() {
  const m = $('messages');
  m.scrollTop = m.scrollHeight;
}

// ============ Markdown 渲染 ============
/**
 * 将文本渲染为 HTML，支持代码块、行内代码、加粗等
 * 先 escape HTML，再替换 Markdown 标记
 */
function renderMarkdown(text) {
  if (!text) return '';
  let result = escapeHtml(text);

  // 1. 代码块：```language\ncode\n``` → <pre><code class="language-xxx">
  //    使用占位符避免后续正则误处理
  const codeBlocks = [];
  result = result.replace(/```(\w*)\n([\s\S]*?)```/g, (_m, lang, code) => {
    const idx = codeBlocks.length;
    codeBlocks.push({ lang: lang.trim(), code: code });
    return `\u0000CODE_BLOCK_${idx}\u0000`;
  });

  // 2. 行内代码：`code` → <code>code</code>
  result = result.replace(/`([^`\n]+)`/g, '<code class="inline">$1</code>');

  // 3. 加粗：**text** → <strong>text</strong>
  result = result.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');

  // 4. 行内代码：`code` → <code>code</code>（处理剩余的反引号）
  result = result.replace(/`([^`\n]+)`/g, '<code class="inline">$1</code>');

  // 5. 换行符转 <br>
  result = result.replace(/\n/g, '<br>');

  // 6. 还原代码块占位符
  result = result.replace(/\u0000CODE_BLOCK_(\d+)\u0000/g, (_m, idx) => {
    const block = codeBlocks[parseInt(idx)];
    const lang = block.lang || '';
    const escapedCode = escapeHtml(block.code).replace(/\n/g, '<br>');
    const copyId = 'cb_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    return `<div class="code-block">
      <div class="code-block-header">
        <span class="code-block-lang">${escapeHtml(lang)}</span>
        <button class="code-copy-btn" data-target="${copyId}" onclick="window.__copyCode('${copyId}')">复制</button>
      </div>
      <pre id="${copyId}"><code>${escapedCode}</code></pre>
    </div>`;
  });

  return result;
}

// 注册到全局供 onclick 使用
window.__copyCode = function(id) {
  const el = document.getElementById(id);
  if (!el) return;
  const text = el.textContent;
  navigator.clipboard.writeText(text).then(() => {
    const btn = el.closest('.code-block').querySelector('.code-copy-btn');
    const orig = btn.textContent;
    btn.textContent = '已复制';
    btn.style.color = 'var(--green)';
    setTimeout(() => { btn.textContent = orig; btn.style.color = ''; }, 1500);
  });
};

// ============ 渲染：消息 ============
function clearMessages() {
  $('messages').innerHTML = '';
}

function addMessage(role, content) {
  const wrap = document.createElement('div');
  wrap.className = 'msg ' + role;

  const avatar = document.createElement('div');
  avatar.className = 'avatar';
  const labels = { user: 'YOU', assistant: 'AI', tool: 'TOOL', system: 'SYS' };
  avatar.textContent = labels[role] || role.toUpperCase();

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  if (role === 'assistant' || role === 'tool') {
    bubble.innerHTML = renderMarkdown(content || '');
  } else {
    bubble.textContent = content || '';
  }

  wrap.appendChild(avatar);
  wrap.appendChild(bubble);
  $('messages').appendChild(wrap);
  scrollMessages();
  return bubble;
}

/**
 * 流式模式：创建或追加到当前助手消息
 * 流式阶段使用 textContent（纯文本，快速且不会有不完整 Markdown 的问题）
 * 最终通过 finalizeAssistantMessage 切换为 Markdown 渲染
 */
function appendAssistantContent(chunk) {
  if (!state.currentAssistantBubble) {
    // 首次创建气泡（使用 textContent，不走 renderMarkdown）
    const wrap = document.createElement('div');
    wrap.className = 'msg assistant';
    const avatar = document.createElement('div');
    avatar.className = 'avatar';
    avatar.textContent = 'AI';
    const bubble = document.createElement('div');
    bubble.className = 'bubble streaming';
    bubble.textContent = chunk;
    wrap.appendChild(avatar);
    wrap.appendChild(bubble);
    $('messages').appendChild(wrap);
    state.currentAssistantContent = chunk;
    state.currentAssistantBubble = bubble;
    scrollMessages();
  } else {
    // 追加内容：用 textContent 累积，避免不完整 Markdown 导致渲染异常
    state.currentAssistantContent += chunk;
    state.currentAssistantBubble.textContent = state.currentAssistantContent;
    scrollMessages();
  }
}

function finalizeAssistantMessage(content) {
  state.currentAssistantContent = content;
  if (state.currentAssistantBubble) {
    state.currentAssistantBubble.innerHTML = renderMarkdown(content);
    state.currentAssistantBubble.classList.remove('streaming');
  } else {
    state.currentAssistantBubble = addMessage('assistant', content);
  }
  scrollMessages();
  // 重置流式状态
  state.currentAssistantBubble = null;
  state.currentAssistantContent = '';
}

function addThinking() {
  const wrap = document.createElement('div');
  wrap.className = 'msg assistant';
  wrap.id = 'thinkingMsg';
  wrap.innerHTML = `
    <div class="avatar">AI</div>
    <div class="bubble thinking">
      <div class="dots"><span></span><span></span><span></span></div>
      <span>Agent 推理中…</span>
    </div>`;
  $('messages').appendChild(wrap);
  scrollMessages();
}

function removeThinking() {
  const el = $('thinkingMsg');
  if (el) el.remove();
}

// ============ 渲染：轨迹 ============
function clearTrace() {
  $('traceTimeline').innerHTML = '';
}

function addTrace(text) {
  const timeline = $('traceTimeline');
  const hint = timeline.querySelector('.empty-hint');
  if (hint) hint.remove();

  let type = 'thought';
  let label = 'THOUGHT';
  let body = text;
  const m = text.match(/^\[([A-Za-z]+)\]\s*(.*)$/s);
  if (m) {
    const tag = m[1].toLowerCase();
    body = m[2];
    if (tag.startsWith('action')) { type = 'action'; label = 'ACTION'; }
    else if (tag.startsWith('obs')) { type = 'observation'; label = 'OBSERVATION'; }
    else if (tag.startsWith('thought')) { type = 'thought'; label = 'THOUGHT'; }
  }
  if (/error|失败|异常/i.test(text)) { type = 'error'; label = 'ERROR'; }

  const step = document.createElement('div');
  step.className = 'trace-step ' + type;
  step.innerHTML = `<span class="tag">${label}</span><div class="content">${escapeHtml(body)}</div>`;
  timeline.appendChild(step);
  timeline.scrollTop = timeline.scrollHeight;
}

// ============ 渲染：会话列表 ============
function renderSessionList() {
  const list = $('sessionList');
  if (state.sessions.size === 0) {
    list.innerHTML = '<div class="empty-hint">暂无会话，点击 + 新建</div>';
    return;
  }
  list.innerHTML = '';
  state.sessions.forEach((s) => {
    const card = document.createElement('div');
    card.className = 'session-card' + (s.sessionId === state.currentSessionId ? ' active' : '');
    card.innerHTML = `
      <span class="sid">${escapeHtml(s.sessionId.slice(0, 12))}…</span>
      <span class="stitle">${escapeHtml(s.title || '未命名会话')}</span>`;
    card.addEventListener('click', () => selectSession(s.sessionId));
    list.appendChild(card);
  });
}

function ensureSession(sessionId, title) {
  if (!state.sessions.has(sessionId)) {
    state.sessions.set(sessionId, { sessionId, title, messages: [] });
  }
  state.currentSessionId = sessionId;
  $('currentSessionId').value = sessionId;
  renderSessionList();
}

// ============ API 调用 ============
async function apiChat(cmd) {
  const resp = await fetch(getBaseUrl() + '/agent/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cmd),
  });
  return resp.json();
}

async function apiCreateSession(cmd) {
  const resp = await fetch(getBaseUrl() + '/agent/session', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cmd),
  });
  return resp.json();
}

async function apiGetSession(sessionId) {
  const resp = await fetch(getBaseUrl() + '/agent/session/' + sessionId);
  return resp.json();
}

function streamChat(cmd, callbacks) {
  const params = new URLSearchParams({ message: cmd.message });
  if (cmd.sessionId) params.set('sessionId', cmd.sessionId);
  if (cmd.agentId) params.set('agentId', cmd.agentId);
  const url = getBaseUrl() + '/agent/chat/stream?' + params.toString();

  const es = new EventSource(url);
  let finished = false;
  let receivedDone = false;

  const finish = () => {
    if (!finished) {
      finished = true;
      es.close();
    }
  };

  // Named event handlers
  es.addEventListener('session', (e) => {
    callbacks.onSession && callbacks.onSession(e.data);
  });

  es.addEventListener('step', (e) => {
    callbacks.onStep && callbacks.onStep(e.data);
  });

  // LLM token 级增量推送（核心流式体验）
  es.addEventListener('token', (e) => {
    callbacks.onToken && callbacks.onToken(e.data);
  });

  es.addEventListener('tool_name', (e) => {
    callbacks.onToolName && callbacks.onToolName(e.data);
  });

  es.addEventListener('tool_args', (e) => {
    callbacks.onToolArgs && callbacks.onToolArgs(e.data);
  });

  es.addEventListener('reply', (e) => {
    callbacks.onReply && callbacks.onReply(e.data);
  });

  es.addEventListener('done', () => {
    receivedDone = true;
    finish();
    callbacks.onDone && callbacks.onDone();
  });

  es.addEventListener('error', () => {
    if (receivedDone || finished) {
      return;
    }
    finish();
    callbacks.onError && callbacks.onError('流式连接异常，请重试');
  });

  return es;
}

// ============ 业务流程 ============
function buildCmd() {
  const message = $('input').value.trim();
  const cmd = { message };
  if (state.currentSessionId) cmd.sessionId = state.currentSessionId;
  const agentId = $('agentId').value.trim();
  if (agentId) cmd.agentId = agentId;
  return cmd;
}

async function send() {
  const cmd = buildCmd();
  if (!cmd.message) return;
  if (state.busy) return;

  // 清空欢迎页
  const welcome = $('messages').querySelector('.welcome');
  if (welcome) welcome.remove();

  // 渲染用户消息
  addMessage('user', cmd.message);
  $('input').value = '';
  clearTrace();
  addThinking();
  setBusy(true);
  setStatus('正在与 Agent 通信…', 'busy');

  // 重置流式状态
  state.currentAssistantBubble = null;
  state.currentAssistantContent = '';

  try {
    if (state.mode === 'sync') {
      await sendSync(cmd);
    } else if (state.mode === 'ws') {
      await sendWebSocket(cmd);
    } else {
      await sendStream(cmd);
    }
  } catch (err) {
    removeThinking();
    const msg = err && err.message ? err.message : String(err);
    addMessage('system', '请求失败：' + msg);
    setStatus('请求失败：' + msg, 'err');
    // 重置流式状态
    state.currentAssistantBubble = null;
  } finally {
    setBusy(false);
  }
}

async function sendSync(cmd) {
  const resp = await apiChat(cmd);
  removeThinking();

  if (!resp.success) {
    addMessage('system', '错误 [' + (resp.errCode || '') + ']：' + (resp.errMessage || '未知错误'));
    setStatus('错误：' + (resp.errMessage || resp.errCode), 'err');
    return;
  }

  const data = resp.data;
  ensureSession(data.sessionId, 'session');
  addMessage('assistant', data.reply || '(空回复)');

  (data.traceSteps || []).forEach(addTrace);
  setStatus(`完成 · ${(data.traceSteps || []).length} 步推理`, 'ok');
}

async function sendStream(cmd) {
  await new Promise((resolve, reject) => {
    let replyData = null;
    let replyReceived = false;

    streamChat(cmd, {
      onSession: (sid) => {
        ensureSession(sid, 'session');
      },
      onStep: (step) => {
        addTrace(step);
        setStatus('流式接收中…', 'busy');
      },
      // LLM token 级流式：逐 token 追加为纯文本
      onToken: (token) => {
        removeThinking();
        appendAssistantContent(token);
        setStatus('流式接收中…', 'busy');
      },
      onToolName: (name) => {
        setStatus('调用工具: ' + name, 'busy');
      },
      onToolArgs: (_args) => {
        // 工具参数增量（可在轨迹区展示）
      },
      // onReply 仅记录数据，不 finalize（避免与 onDone 冲突）
      onReply: (data) => {
        replyData = data;
        replyReceived = true;
      },
      // onDone 统一负责 finalize：移除流式气泡，用 addMessage 重建确保格式一致
      onDone: () => {
        removeThinking();
        // 保存累积内容，然后移除流式气泡
        let finalContent = '';
        if (state.currentAssistantBubble) {
          finalContent = state.currentAssistantContent;
          const bubbleWrap = state.currentAssistantBubble.parentElement;
          if (bubbleWrap) bubbleWrap.remove();
          state.currentAssistantBubble = null;
          state.currentAssistantContent = '';
        }
        if (finalContent) {
          // 用 addMessage 渲染（与切换 session 完全相同的路径）
          addMessage('assistant', finalContent);
        } else if (replyReceived && replyData) {
          let text = replyData;
          try {
            const obj = JSON.parse(replyData);
            text = obj.content || obj.reply || obj.delta || replyData;
          } catch (_) { /* 纯文本 */ }
          addMessage('assistant', text);
        } else {
          addMessage('assistant', '(无回复内容)');
        }
        setStatus('流式完成', 'ok');
        resolve();
      },
      onError: (msg) => {
        removeThinking();
        if (state.currentAssistantContent) {
          finalizeAssistantMessage(state.currentAssistantContent + '\n\n⚠️ 连接中断');
        } else {
          addMessage('system', '流式错误：' + msg);
        }
        setStatus('流式错误：' + msg, 'err');
        reject(new Error(msg));
      },
    });
  });
}

async function sendWebSocket(cmd) {
  await new Promise((resolve, reject) => {
    const wsUrl = getWsUrl();
    setStatus('WebSocket 连接中…', 'busy');

    let ws;
    try {
      ws = new WebSocket(wsUrl);
    } catch (err) {
      removeThinking();
      addMessage('system', 'WebSocket 创建失败：' + err.message);
      setStatus('WebSocket 创建失败', 'err');
      reject(err);
      return;
    }

    let replyReceived = false;
    let replyData = null;

    ws.onopen = () => {
      setStatus('WebSocket 已连接，发送消息…', 'busy');
      // 发送聊天请求
      const request = { type: 'chat', message: cmd.message };
      if (cmd.sessionId) request.sessionId = cmd.sessionId;
      if (cmd.agentId) request.agentId = cmd.agentId;
      ws.send(JSON.stringify(request));
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        switch (msg.type) {
          case 'session':
            ensureSession(msg.data, 'session');
            break;
          case 'step':
            addTrace(msg.data);
            setStatus('WebSocket 流式接收中…', 'busy');
            break;
          case 'token':
            removeThinking();
            appendAssistantContent(msg.data);
            setStatus('WebSocket 流式接收中…', 'busy');
            break;
          case 'tool_name':
            setStatus('调用工具: ' + msg.data, 'busy');
            break;
          case 'tool_args':
            // 工具参数增量
            break;
          case 'reply':
            replyData = msg.data;
            replyReceived = true;
            break;
          case 'done':
            ws.close();
            removeThinking();
            let finalContent = '';
            if (state.currentAssistantBubble) {
              finalContent = state.currentAssistantContent;
              const bubbleWrap = state.currentAssistantBubble.parentElement;
              if (bubbleWrap) bubbleWrap.remove();
              state.currentAssistantBubble = null;
              state.currentAssistantContent = '';
            }
            if (finalContent) {
              addMessage('assistant', finalContent);
            } else if (replyReceived && replyData) {
              let text = replyData;
              try {
                const obj = JSON.parse(replyData);
                text = obj.content || obj.reply || obj.delta || replyData;
              } catch (_) { /* 纯文本 */ }
              addMessage('assistant', text);
            } else {
              addMessage('assistant', '(无回复内容)');
            }
            setStatus('WebSocket 流式完成', 'ok');
            resolve();
            break;
          case 'error':
            setStatus('WebSocket 错误: ' + msg.data, 'err');
            break;
        }
      } catch (err) {
        setStatus('消息解析异常: ' + err.message, 'err');
      }
    };

    ws.onerror = () => {
      removeThinking();
      if (state.currentAssistantContent) {
        finalizeAssistantMessage(state.currentAssistantContent);
      }
      setStatus('WebSocket 连接异常', 'err');
      if (ws.readyState !== WebSocket.OPEN && ws.readyState !== WebSocket.CONNECTING) {
        reject(new Error('WebSocket 连接异常'));
      }
    };

    ws.onclose = () => {
      // 如果 done 事件未触发，说明异常关闭
      if (!replyReceived && !state.currentAssistantContent) {
        removeThinking();
        reject(new Error('WebSocket 连接已关闭'));
      }
    };
  });
}

// ============ 会话操作 ============
async function createSession() {
  if (state.busy) return;
  setBusy(true);
  setStatus('创建会话…', 'busy');
  try {
    const cmd = {};
    const agentId = $('agentId').value.trim();
    if (agentId) cmd.agentId = agentId;
    const resp = await apiCreateSession(cmd);
    if (!resp.success) {
      setStatus('创建失败：' + (resp.errMessage || resp.errCode), 'err');
      return;
    }
    const s = resp.data;
    ensureSession(s.sessionId, s.title);
    clearMessages();
    clearTrace();
    $('currentSessionId').value = s.sessionId;
    setStatus('已创建会话 ' + s.sessionId.slice(0, 12) + '…', 'ok');
  } catch (err) {
    setStatus('创建失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function selectSession(sessionId) {
  if (state.busy) return;
  setBusy(true);
  setStatus('加载会话…', 'busy');
  try {
    const resp = await apiGetSession(sessionId);
    if (!resp.success) {
      setStatus('加载失败：' + (resp.errMessage || resp.errCode), 'err');
      return;
    }
    const s = resp.data;
    state.currentSessionId = s.sessionId;
    $('currentSessionId').value = s.sessionId;
    renderSessionList();

    clearMessages();
    clearTrace();
    (s.messages || []).forEach((m) => addMessage(m.role, m.content));
    setStatus(`已加载会话 · ${(s.messages || []).length} 条消息`, 'ok');
  } catch (err) {
    setStatus('加载失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

// ============ 事件绑定 ============
function bindEvents() {
  $('sendBtn').addEventListener('click', send);
  $('input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  });

  document.querySelectorAll('.mode-switch button').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.mode-switch button').forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      state.mode = btn.dataset.mode;
      const labels = { sync: '同步', stream: '流式', ws: 'WebSocket' };
      setStatus('已切换到' + (labels[state.mode] || state.mode) + '模式', 'ok');
    });
  });

  $('newSessionBtn').addEventListener('click', createSession);

  document.querySelectorAll('.tabs button').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tabs button').forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      document.querySelectorAll('.tab-content').forEach((c) => c.classList.remove('active'));
      $(btn.dataset.tab === 'trace' ? 'tracePanel' : 'configPanel').classList.add('active');
    });
  });
}

// ============ 启动 ============
document.addEventListener('DOMContentLoaded', () => {
  bindEvents();
  setStatus('就绪 · 后端 ' + getBaseUrl(), 'ok');
});
