package com.mwb.ai.claw.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.alibaba.cola.dto.SingleResponse;
import com.mwb.ai.claw.agent.executor.ChatCmdExe;
import com.mwb.ai.claw.api.AgentServiceI;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.memory.MemorySynthesisExecutor;
import com.mwb.ai.claw.infrastructure.memory.SynthesisCache;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;

/**
 * Agent Shell：终端 REPL 交互模式。
 * <p>
 * 通过 {@code --spring.profiles.active=shell} 激活。
 * 支持同步/流式对话、会话管理、命令历史等功能。
 *
 * <h3>使用方式</h3>
 * <pre>
 * java -jar start.jar --spring.profiles.active=shell
 * </pre>
 */
@Component
@Profile("shell")
public class AgentShell implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentShell.class);

    @Resource
    private AgentServiceI agentService;

    @Resource
    private ChatCmdExe chatCmdExe;

    @Resource
    private MemoryPageStore pageStore;

    @Resource
    private LayeredMemoryGateway layeredMemoryGateway;

    @Resource
    private AgentProperties agentProperties;

    @Resource
    private SynthesisCache synthesisCache;

    @Resource
    private MemorySynthesisExecutor synthesisExecutor;

    private Terminal terminal;
    private LineReader reader;
    private String sessionId;
    private boolean streamMode = true;    // 默认流式模式
    private boolean verbose = false;      // 默认观察结果缩写展示（/trace 切换完整显示）
    private boolean resultStarted = false; // 结果区是否已开始输出
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    private boolean finalReplyStreamed = false; // 最终回复是否已通过流式输出完成

    // ANSI 风格
    private static final AttributedStyle STYLE_PROMPT = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
    private static final AttributedStyle STYLE_INFO = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE);
    private static final AttributedStyle STYLE_THOUGHT = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
    private static final AttributedStyle STYLE_ACTION = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle STYLE_OBS = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE);
    private static final AttributedStyle STYLE_ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final AttributedStyle STYLE_WARN = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();
    private static final AttributedStyle STYLE_SESSION = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).faint();

    @Override
    public void run(String... args) throws Exception {
        initTerminal();
        restoreLastSession();
        printBanner();
        repl();
    }

    // ==================== 初始化 ====================

    private void initTerminal() throws IOException {
        terminal = TerminalBuilder.builder()
                .system(true)
                .jansi(false)
                .build();

        Path historyFile = Paths.get(System.getProperty("user.home"), ".mwb-ai-claw-history");
        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();
    }

    private void printBanner() {
        println(STYLE_PROMPT, "  ◈  mwb-ai-claw Agent Shell");
        println(STYLE_INFO, "  输入消息与 Agent 对话，/help 查看命令");
        println(STYLE_INFO, "  模式: " + (streamMode ? "流式" : "同步") + " | 观察: " + (verbose ? "完整" : "缩写") + " | 会话: " + (sessionId == null ? "（自动创建）" : sessionId));
        println(STYLE_INFO, "");
    }

    // ==================== REPL 主循环 ====================

    private void repl() {
        while (true) {
            try {
                String prompt = buildPrompt();
                String line = reader.readLine(prompt);
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                processInput(line.trim());
            } catch (EndOfFileException | UserInterruptException e) {
                println(STYLE_INFO, "\n再见！");
                break;
            } catch (Exception e) {
                println(STYLE_ERROR, "错误: " + e.getMessage());
                log.error("REPL 异常", e);
            }
        }
        try {
            terminal.close();
        } catch (IOException ignored) {}
    }

    private String buildPrompt() {
        String prefix = streamMode ? "⚡ " : "▶ ";
        String sid = sessionId == null ? "" : "[" + sessionId.substring(0, 8) + "] ";
        return new AttributedStringBuilder()
                .style(STYLE_PROMPT)
                .append(prefix)
                .style(STYLE_SESSION)
                .append(sid)
                .style(STYLE_INFO)
                .append("> ")
                .toAnsi();
    }

    // ==================== 命令分发 ====================

    private void processInput(String input) {
        if (input.startsWith("/")) {
            handleCommand(input);
        } else {
            handleChat(input);
        }
    }

    private void handleCommand(String input) {
        String[] parts = input.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();
        String arg1 = parts.length > 1 ? parts[1] : null;
        String arg2 = parts.length > 2 ? parts[2] : null;

        switch (cmd) {
            case "/help":
                printHelp();
                break;
            case "/mode":
                streamMode = !streamMode;
                println(STYLE_INFO, "模式已切换为: " + (streamMode ? "流式" : "同步"));
                break;
            case "/trace":
                verbose = !verbose;
                println(STYLE_INFO, "观察结果完整显示: " + (verbose ? "开启" : "关闭（缩写）"));
                break;
            case "/session":
                handleSessionCommand(arg1, arg2);
                break;
            case "/memory":
                handleMemoryCommand(arg1, arg2);
                break;
            case "/clear":
                terminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen);
                terminal.flush();
                printBanner();
                break;
            case "/exit":
            case "/quit":
                throw new EndOfFileException();
            default:
                println(STYLE_WARN, "未知命令: " + cmd + "，输入 /help 查看帮助");
        }
    }

    private void handleSessionCommand(String sub, String arg) {
        if (sub == null) {
            // /session → 显示当前会话
            if (sessionId == null) {
                println(STYLE_INFO, "当前无会话，新消息会自动创建会话");
            } else {
                println(STYLE_INFO, "当前会话: " + sessionId);
            }
            return;
        }
        switch (sub.toLowerCase()) {
            case "new":
                createSession();
                break;
            case "list":
                listSessions();
                break;
            case "switch":
                if (arg == null) {
                    println(STYLE_WARN, "用法: /session switch <sessionId>");
                } else {
                    switchSession(arg);
                }
                break;
            case "delete":
                if (arg == null) {
                    println(STYLE_WARN, "用法: /session delete <sessionId>");
                } else {
                    deleteSession(arg);
                }
                break;
            default:
                println(STYLE_WARN, "未知子命令: " + sub + "，支持: new, list, switch, delete");
        }
    }

    // ==================== 记忆可视化查询 ====================

    private void handleMemoryCommand(String sub, String arg) {
        if (!agentProperties.getMemory().isEnabled()) {
            println(STYLE_WARN, "分层记忆未启用（agent.memory.layered.enabled=false）");
            return;
        }
        if (sub == null || sub.equalsIgnoreCase("stats") || sub.equalsIgnoreCase("overview")) {
            showMemoryStats();
            return;
        }
        switch (sub.toLowerCase()) {
            case "facts":
                showMemoryFacts();
                break;
            case "summaries":
                showMemoryPages(pageStore.listAllSummaries(), "SUMMARY");
                break;
            case "archive":
                showMemoryPages(pageStore.listAllArchive(), "ARCHIVE");
                break;
            case "search":
                if (arg == null) {
                    println(STYLE_WARN, "用法: /memory search <关键词> [topK]");
                } else {
                    showMemorySearch(arg);
                }
                break;
            default:
                println(STYLE_WARN, "未知子命令: " + sub + "，支持: stats, facts, summaries, archive, search");
        }
    }

    /** 分层记忆总览：配置 + 各层统计 + 提炼缓存/队列状态 */
    private void showMemoryStats() {
        LayeredMemoryConfig cfg = agentProperties.getMemory();
        List<MemoryPage> facts = pageStore.loadFacts();
        List<MemoryPage> summaries = pageStore.listAllSummaries();
        List<MemoryPage> archives = pageStore.listAllArchive();
        println(STYLE_PROMPT, "◈ 分层记忆总览 ◈");
        println(STYLE_INFO, String.format("  启用: %s | 检索器: %s | 向量: %s | 档案: %s | 共享: %s | topK: %d",
                cfg.isEnabled(), cfg.getRetriever(), cfg.isVectorEnabled(),
                cfg.isArchiveEnabled(), cfg.isSharedRetrieve(), cfg.getTopK()));
        println(STYLE_INFO, String.format("  FACT    : %d 条 / %d tokens", facts.size(), sumTokens(facts)));
        println(STYLE_INFO, String.format("  SUMMARY : %d 页 / %d tokens", summaries.size(), sumTokens(summaries)));
        println(STYLE_INFO, String.format("  ARCHIVE : %d 块 / %d tokens", archives.size(), sumTokens(archives)));
        Map<String, Object> cache = synthesisCache.stats();
        println(STYLE_INFO, String.format("  提炼缓存: 容量=%s 已用=%s 命中=%s 未中=%s 命中率=%s%% | 提炼队列待办=%d",
                cache.get("capacity"), cache.get("size"), cache.get("hits"),
                cache.get("misses"), cache.get("hitRate"), synthesisExecutor.pendingCount()));
    }

    /** 长期记忆事实列表（重要度降序，含版本/时间戳） */
    private void showMemoryFacts() {
        List<MemoryPage> facts = pageStore.loadFacts();
        facts.sort(Comparator.comparingDouble(MemoryPage::getImportance).reversed());
        if (facts.isEmpty()) {
            println(STYLE_INFO, "暂无长期记忆事实");
            return;
        }
        println(STYLE_PROMPT, "◈ 长期记忆事实（重要度降序，共 " + facts.size() + " 条）◈");
        for (MemoryPage f : facts) {
            String key = f.getKey() != null ? f.getKey() : "";
            String meta = String.format("v%d · %s", f.getVersion(), formatTime(f.getCreateTime()));
            println(STYLE_INFO, String.format("  [%.2f] %s: %s %s",
                    f.getImportance(), key, abbreviate(f.getContent(), 140), meta));
        }
    }

    /** 摘要页 / 档案块列表 */
    private void showMemoryPages(List<MemoryPage> pages, String label) {
        if (pages == null || pages.isEmpty()) {
            println(STYLE_INFO, "暂无 " + label + " 内容");
            return;
        }
        println(STYLE_PROMPT, "◈ " + label + "（共 " + pages.size() + " 页）◈");
        for (MemoryPage p : pages) {
            String sid = p.getSessionId() != null
                    ? p.getSessionId().substring(0, Math.min(8, p.getSessionId().length())) : "?";
            String range = p.getBlockStart() >= 0 ? " [" + p.getBlockStart() + "-" + p.getBlockEnd() + "]" : "";
            println(STYLE_INFO, String.format("  %s %s%s %s tokens: %s",
                    label, sid, range, p.getTokenCount(), abbreviate(p.getContent(), 180)));
        }
    }

    /** 检索召回调试：按当前检索器（keyword/vector/hybrid）执行检索 */
    private void showMemorySearch(String query) {
        List<MemoryPage> hits = layeredMemoryGateway.search(query, 5);
        if (hits == null || hits.isEmpty()) {
            println(STYLE_INFO, "未检索到相关记忆: " + query);
            return;
        }
        println(STYLE_PROMPT, "◈ 检索召回（" + agentProperties.getMemory().getRetriever() + "，top " + hits.size() + "）：" + query + " ◈");
        for (MemoryPage p : hits) {
            String sid = p.getSessionId() != null
                    ? p.getSessionId().substring(0, Math.min(8, p.getSessionId().length())) : "?";
            println(STYLE_INFO, String.format("  [%s] %s: %s",
                    p.getType(), sid, abbreviate(p.getContent(), 160)));
        }
    }

    private int sumTokens(List<MemoryPage> pages) {
        return pages.stream().mapToInt(p -> p.getTokenCount() > 0 ? p.getTokenCount() : TokenEstimator.estimate(p)).sum();
    }

    /** 毫秒时间戳 → "MM-dd HH:mm" 终端展示 */
    private String formatTime(long millis) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(millis));
    }

    // ==================== 对话处理 ====================

    private void handleChat(String message) {
        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(message);
        cmd.setSessionId(sessionId);

        try {
            if (streamMode) {
                doStreamChat(cmd);
            } else {
                doSyncChat(cmd);
            }
        } catch (Exception e) {
            println(STYLE_ERROR, "对话失败: " + e.getMessage());
            log.error("对话异常", e);
        }
    }

    private void doSyncChat(ChatCmd cmd) {
        resultStarted = false;
        println(STYLE_INFO, "（同步等待中…）");
        SingleResponse<ChatResponseDTO> resp = agentService.chat(cmd);
        if (!resp.isSuccess()) {
            println(STYLE_ERROR, "错误: " + resp.getErrMessage());
            return;
        }
        ChatResponseDTO data = resp.getData();
        sessionId = data.getSessionId();

        // 执行区：按顺序展示每一步轨迹（思考 / 工具调用+入参 / 观察结果）
        if (data.getTraceSteps() != null) {
            for (String step : data.getTraceSteps()) {
                printTraceStep(step);
            }
        }
        // 结果区
        beginResultSection();
        markdownRenderer.reset();
        String reply = data.getReply() != null ? data.getReply() : "（空回复）";
        terminal.writer().print(markdownRenderer.render(reply));
        terminal.writer().flush();
    }

    private void doStreamChat(ChatCmd cmd) {
        resultStarted = false;
        finalReplyStreamed = false;
        markdownRenderer.reset();

        // 执行区：按顺序展示每一步轨迹（思考 / 工具调用+入参 / 观察结果）
        ProgressCallback progressCb = this::printTraceStep;

        // 流式回调：最终回复轮实时输出（按行增量渲染 Markdown），工具调用轮内容抑制
        LlmStreamCallback streamCb = new LlmStreamCallback() {
            private final StringBuilder lineBuf = new StringBuilder();
            private boolean toolRound = false;

            @Override
            public void onToken(String token) {
                if (toolRound) {
                    return; // 工具轮：LLM 前导文本不展示，由执行区轨迹展示工具调用
                }
                beginResultSection();
                lineBuf.append(token);
                // 行缓冲按行渲染并输出，保证 Markdown（代码块等）跨行状态正确
                int nl;
                while ((nl = lineBuf.indexOf("\n")) >= 0) {
                    String line = lineBuf.substring(0, nl);
                    lineBuf.delete(0, nl + 1);
                    terminal.writer().print(markdownRenderer.renderLine(line) + "\n");
                    terminal.writer().flush();
                }
            }

            @Override
            public void onToolName(String toolName) {
                // 检测到工具调用开始：本轮内容不再实时输出，丢弃已缓冲的未完成行
                toolRound = true;
                lineBuf.setLength(0);
            }

            @Override
            public void onComplete(LlmResponse response) {
                boolean isToolRound = response != null && response.getToolCalls() != null
                        && !response.getToolCalls().isEmpty();
                if (isToolRound) {
                    // 工具调用轮：丢弃缓冲的中间思考文本，由执行区轨迹展示
                    lineBuf.setLength(0);
                } else {
                    // 最终回复轮：冲刷剩余半行
                    if (lineBuf.length() > 0) {
                        terminal.writer().print(markdownRenderer.renderLine(lineBuf.toString()));
                        terminal.writer().flush();
                        lineBuf.setLength(0);
                    }
                    finalReplyStreamed = true;
                }
                toolRound = false; // 复位，供下一轮（最终回复轮）实时输出
            }
        };

        println(STYLE_INFO, ""); // 换行
        SingleResponse<ChatResponseDTO> resp = chatCmdExe.execute(cmd, progressCb, streamCb);
        println(STYLE_INFO, ""); // 换行

        if (!resp.isSuccess()) {
            println(STYLE_ERROR, "错误: " + resp.getErrMessage());
            return;
        }

        // 兜底：流式回调未完成（异常/流中断等）且尚未输出任何内容时，一次性渲染最终回复，避免遗漏
        if (!finalReplyStreamed && !resultStarted) {
            beginResultSection();
            ChatResponseDTO data = resp.getData();
            String reply = (data != null && data.getReply() != null) ? data.getReply() : "（空回复）";
            terminal.writer().print(markdownRenderer.render(reply));
            terminal.writer().flush();
        }

        sessionId = resp.getData().getSessionId();
    }

    /** 按顺序展示每一步轨迹：思考 / 工具调用（含入参）/ 观察结果 */
    private void printTraceStep(String step) {
        if (step == null || step.isEmpty()) {
            return;
        }
        if (step.startsWith("[Thought]")) {
            String content = step.substring("[Thought]".length()).trim();
            // 仅展示中间思考；最终回复会以 [Thought] 携带正文，这里跳过，交给结果区完整渲染
            if (content.startsWith("需要调用工具处理")) {
                println(STYLE_THOUGHT, "[思考] " + content);
            }
        } else if (step.startsWith("[Action]")) {
            printActionStep(step);
        } else if (step.startsWith("[Observation]")) {
            String content = step.substring("[Observation]".length()).trim();
            String display = verbose ? content : abbreviate(content, 200);
            println(STYLE_OBS, "[观察] " + display);
        }
    }

    // ==================== 会话管理 ====================

    /** 启动时恢复上次使用的会话：会话列表已按最后使用时间倒序，首个即最近会话 */
    private void restoreLastSession() {
        try {
            SingleResponse<List<SessionDTO>> resp = agentService.listSessions();
            if (!resp.isSuccess() || resp.getData() == null || resp.getData().isEmpty()) {
                return;
            }
            sessionId = resp.getData().get(0).getSessionId();
            println(STYLE_INFO, "已恢复上次会话: " + sessionId);
        } catch (Exception e) {
            log.warn("恢复上次会话失败: {}", e.getMessage());
        }
    }

    private void createSession() {
        try {
            SingleResponse<SessionDTO> resp = agentService.createSession(new CreateSessionCmd());
            if (!resp.isSuccess()) {
                println(STYLE_ERROR, "创建会话失败: " + resp.getErrMessage());
                return;
            }
            sessionId = resp.getData().getSessionId();
            println(STYLE_INFO, "已创建会话: " + sessionId);
        } catch (Exception e) {
            println(STYLE_ERROR, "创建会话失败: " + e.getMessage());
        }
    }

    private void listSessions() {
        try {
            SingleResponse<List<SessionDTO>> resp = agentService.listSessions();
            if (!resp.isSuccess()) {
                println(STYLE_ERROR, "获取会话列表失败: " + resp.getErrMessage());
                return;
            }
            List<SessionDTO> sessions = resp.getData();
            if (sessions == null || sessions.isEmpty()) {
                println(STYLE_INFO, "暂无会话");
                return;
            }
            println(STYLE_INFO, String.format("%-20s  %-30s  %s", "SessionID", "标题", "消息数"));
            println(STYLE_INFO, "──────────────────────────────────────────────────────────────────────");
            for (SessionDTO s : sessions) {
                boolean active = s.getSessionId().equals(sessionId);
                String marker = active ? " *" : "  ";
                String id = s.getSessionId().substring(0, Math.min(16, s.getSessionId().length()));
                String title = s.getTitle() != null ? s.getTitle() : "（未命名）";
                if (title.length() > 28) title = title.substring(0, 28) + "…";
                int msgCount = s.getMessages() != null ? s.getMessages().size() : 0;
                String line = String.format("%-20s  %-30s  %d%s",
                        id, title, msgCount, marker);
                terminal.writer().println(active ? ansi(STYLE_PROMPT, line) : line);
            }
            terminal.writer().flush();
        } catch (Exception e) {
            println(STYLE_ERROR, "获取会话列表失败: " + e.getMessage());
        }
    }

    private void switchSession(String targetId) {
        try {
            // 尝试加载会话以验证存在
            SingleResponse<SessionDTO> resp = agentService.getSession(targetId);
            if (!resp.isSuccess()) {
                // 尝试模糊匹配（前缀）
                boolean found = false;
                SingleResponse<List<SessionDTO>> listResp = agentService.listSessions();
                if (listResp.isSuccess() && listResp.getData() != null) {
                    for (SessionDTO s : listResp.getData()) {
                        if (s.getSessionId().startsWith(targetId)) {
                            sessionId = s.getSessionId();
                            println(STYLE_INFO, "已切换到会话: " + sessionId);
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    println(STYLE_WARN, "会话不存在: " + targetId);
                }
                return;
            }
            sessionId = targetId;
            println(STYLE_INFO, "已切换到会话: " + sessionId);
        } catch (Exception e) {
            println(STYLE_ERROR, "切换会话失败: " + e.getMessage());
        }
    }

    private void deleteSession(String targetId) {
        try {
            // 模糊匹配
            String resolvedId = targetId;
            SingleResponse<SessionDTO> resp = agentService.getSession(targetId);
            if (!resp.isSuccess()) {
                SingleResponse<List<SessionDTO>> listResp = agentService.listSessions();
                if (listResp.isSuccess() && listResp.getData() != null) {
                    for (SessionDTO s : listResp.getData()) {
                        if (s.getSessionId().startsWith(targetId)) {
                            resolvedId = s.getSessionId();
                            break;
                        }
                    }
                }
                if (resolvedId.equals(targetId)) {
                    println(STYLE_WARN, "会话不存在: " + targetId);
                    return;
                }
            }
            SingleResponse<Void> delResp = agentService.deleteSession(resolvedId);
            if (!delResp.isSuccess()) {
                println(STYLE_ERROR, "删除失败: " + delResp.getErrMessage());
                return;
            }
            if (resolvedId.equals(sessionId)) {
                sessionId = null;
            }
            println(STYLE_INFO, "已删除会话: " + resolvedId);
        } catch (Exception e) {
            println(STYLE_ERROR, "删除会话失败: " + e.getMessage());
        }
    }

    // ==================== 帮助信息 ====================

    private void printHelp() {
        println(STYLE_PROMPT, "╔══════════════════════════════════════════════╗");
        println(STYLE_PROMPT, "║  mwb-ai-claw Agent Shell  帮助              ║");
        println(STYLE_PROMPT, "╚══════════════════════════════════════════════╝");
        println(STYLE_INFO, "");
        println(STYLE_INFO, "  直接输入文本 → 发送给 Agent");
        println(STYLE_INFO, "");
        println(STYLE_INFO, "  命令:");
        println(STYLE_INFO, "  /help                  显示此帮助");
        println(STYLE_INFO, "  /mode                  切换 同步/流式 模式");
        println(STYLE_INFO, "  /trace                 切换 观察结果 完整/缩写");
        println(STYLE_INFO, "  /session               查看当前会话");
        println(STYLE_INFO, "  /session new           创建新会话");
        println(STYLE_INFO, "  /session list          列出所有会话");
        println(STYLE_INFO, "  /session switch <id>   切换会话");
        println(STYLE_INFO, "  /session delete <id>   删除会话");
        println(STYLE_INFO, "  /memory                分层记忆总览（配置/统计/缓存）");
        println(STYLE_INFO, "  /memory facts          查看长期记忆事实");
        println(STYLE_INFO, "  /memory summaries      查看中期摘要页");
        println(STYLE_INFO, "  /memory archive        查看跨会话档案块");
        println(STYLE_INFO, "  /memory search <q>     检索记忆召回调试");
        println(STYLE_INFO, "  /clear                 清屏");
        println(STYLE_INFO, "  /exit, /quit           退出");
        println(STYLE_INFO, "");
        println(STYLE_INFO, "  模式: " + (streamMode ? "流式（逐 token 输出）" : "同步（等待完整回复）"));
        println(STYLE_INFO, "  观察: " + (verbose ? "完整" : "缩写"));
        println(STYLE_INFO, "  会话: " + (sessionId == null ? "自动创建" : sessionId));
        println(STYLE_INFO, "");
    }

    // ==================== 终端输出工具 ====================

    /** 标记结果区已开始输出（首次调用时置位），供兜底渲染判断是否已有内容输出 */
    private void beginResultSection() {
        resultStarted = true;
    }

    /** 打印工具调用（执行区）：工具名 + 缩写入参 */
    private void printToolInvocation(String toolName, String arguments) {
        println(STYLE_ACTION, "[工具] " + toolName);
        String args = abbreviate(arguments, 120);
        if (!args.isEmpty()) {
            println(STYLE_ACTION, "     入参: " + args);
        }
    }

    /** 缩写长文本（去换行），避免终端显示过长 */
    private String abbreviate(String text, int maxLen) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String single = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (single.length() <= maxLen) {
            return single;
        }
        return single.substring(0, maxLen) + "…（共 " + single.length() + " 字符）";
    }

    /** 从 [Action] step 中解析工具名与入参并展示（同步模式） */
    private void printActionStep(String step) {
        String body = step.substring("[Action]".length()).trim();
        if (body.startsWith("调用工具: ")) {
            body = body.substring("调用工具: ".length());
        }
        int sep = body.indexOf(" 参数: ");
        String toolName = sep >= 0 ? body.substring(0, sep) : body;
        String args = sep >= 0 ? body.substring(sep + " 参数: ".length()) : "";
        printToolInvocation(toolName, args);
    }

    private void println(AttributedStyle style, String text) {
        terminal.writer().println(ansi(style, text));
        terminal.writer().flush();
    }

    private String ansi(AttributedStyle style, String text) {
        return new AttributedStringBuilder()
                .style(style)
                .append(text)
                .toAnsi();
    }
}
