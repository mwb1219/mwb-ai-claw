package com.mwb.ai.claw.shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Resource;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.FileNameCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.agent.ApprovalService;
import com.mwb.ai.claw.agent.executor.ChatCmdExe;
import com.mwb.ai.claw.agent.observability.RunUsageRecorder;
import com.mwb.ai.claw.api.AgentServiceI;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.ContentPart;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.tool.McpServerConfig;
import com.mwb.ai.claw.domain.tool.ToolApproval;
import com.mwb.ai.claw.dto.ApprovalCmd;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.PendingApprovalDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.memory.MemorySynthesisExecutor;
import com.mwb.ai.claw.infrastructure.memory.SynthesisCache;
import com.mwb.ai.claw.infrastructure.memory.strategy.LlmMemorySynthesizer;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.infrastructure.tool.ToolSecurity;
import com.mwb.ai.claw.infrastructure.tool.mcp.McpClientManager;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;
import com.mwb.ai.claw.shell.util.MultimodalInputParser;
import com.mwb.ai.claw.shell.util.TemplateEngine;

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
public class AgentShell implements CommandLineRunner, ToolApproval {

    private static final Logger log = LoggerFactory.getLogger(AgentShell.class);

    /** shell 模式统一使用的租户/用户维度（default/default，落库/序列化统一为 default） */
    private static final AgentScope SHELL_SCOPE = AgentScope.of("default", "default");

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

    @Resource
    private MemoryGateway memoryGateway;

    @Resource
    private LlmMemorySynthesizer llmMemorySynthesizer;

    @Resource
    private ToolSecurity toolSecurity;

    @Resource
    private McpClientManager mcpClientManager;

    @Resource
    private ApprovalService approvalService;

    @Resource
    private MetricsRecorder metricsRecorder;

    @Resource
    private RunUsageRecorder runUsageRecorder;

    private Terminal terminal;
    private LineReader reader;
    private volatile String sessionId;
    /** 后台对话执行器（daemon）：delegate 编排命中审批门禁等待期间，REPL 主循环仍可接收 /pending /approve /reject */
    private ExecutorService chatExecutor;
    /** 当前是否有对话在后台执行 */
    private volatile boolean chatInProgress = false;
    private boolean streamMode = true;    // 默认流式模式
    private boolean verbose = false;      // 默认观察结果缩写展示（/trace 切换完整显示）
    private String defaultAgentId;        // --agent 指定默认专家（每次对话显式路由到该 Agent）
    private boolean resultStarted = false; // 结果区是否已开始输出
    private boolean planMode = false;     // 计划模式：先出方案，确认后执行
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    private boolean finalReplyStreamed = false; // 最终回复是否已通过流式输出完成
    /** headless 模式：非交互执行（--prompt / 管道输入），无终端 UI */
    private boolean headless = false;
    /** 自定义斜杠命令（~/.claw/commands/*.md） */
    private final Map<String, CustomCommand> customCommands = new HashMap<>();
    /** 后台 agent 任务（--bg 启动，/agent 管理） */
    private final Map<String, BackgroundAgentTask> bgTasks = new ConcurrentHashMap<>();
    /** 上下文 token 估算缓存（会话 + 消息数变化时重算） */
    private String ctxCacheSessionId;
    private int ctxMsgCount = -1;
    private int ctxTokens = 0;
    /** 结构化输出模式（output=json 的自定义命令）：抑制常规展示，捕获最终回复统一格式化 */
    private boolean jsonOutputMode = false;
    /** 结构化输出模式下捕获的最终回复原文 */
    private String capturedReply = null;

    // ANSI 风格
    private static final AttributedStyle STYLE_PROMPT = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
    private static final AttributedStyle STYLE_INFO = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE);
    private static final AttributedStyle STYLE_THOUGHT = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
    private static final AttributedStyle STYLE_ACTION = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle STYLE_OBS = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE);
    private static final AttributedStyle STYLE_ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final AttributedStyle STYLE_WARN = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();
    private static final AttributedStyle STYLE_SESSION = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).faint();
    private static final AttributedStyle STYLE_STREAM = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    private static final AttributedStyle STYLE_APPROVAL = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();

    @Override
    public void run(String... args) throws Exception {
        ShellOptions opts = ShellOptions.parse(args);
        if (opts.verbose) {
            verbose = true;
        }
        if (opts.agentId != null && !opts.agentId.trim().isEmpty()) {
            defaultAgentId = opts.agentId.trim();
        }
        java.io.Console console = System.console();
        // JDK 21 兼容：System.console() 在 stdin 为管道/重定向时返回 null（JDK 22 的 Console.isTerminal() 不可用）
        if (opts.prompt != null || console == null) {
            // headless 模式：--prompt 给定或 stdin 为管道（echo xxx | mwb-ai-claw）
            runHeadless(opts);
            return;
        }
        if (opts.sessionId != null) {
            sessionId = opts.sessionId; // --resume 指定会话
        }
        if (opts.mode != null) {
            streamMode = "stream".equalsIgnoreCase(opts.mode);
        }
        initTerminal();
        loadCustomCommands();
        chatExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-shell-chat");
            t.setDaemon(true);
            return t;
        });
        restoreLastSession();
        if (opts.bgPrompt != null && !opts.bgPrompt.trim().isEmpty()) {
            startBackgroundAgent(opts.bgPrompt);
        }
        printBanner();
        repl();
    }

    // ==================== headless 模式（--prompt / 管道输入） ====================

    /**
     * 非交互单轮执行：读取 --prompt 参数或 stdin 全部内容，同步对话后输出纯文本回复并退出。
     * 适用于脚本化 / 管道场景，如 {@code echo "总结一下" | mwb-ai-claw} 或 {@code mwb-ai-claw --prompt "列出文件"}。
     */
    private void runHeadless(ShellOptions opts) throws IOException {
        headless = true;
        String message = opts.prompt;
        if (message == null || message.trim().isEmpty()) {
            // 从 stdin 读取全部内容（管道输入）
            StringBuilder sb = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            message = sb.toString().trim();
        }
        if (message == null || message.isEmpty()) {
            System.err.println("用法: mwb-ai-claw --prompt \"问题\"   或   echo \"问题\" | mwb-ai-claw");
            return;
        }

        if (opts.sessionId != null) {
            sessionId = opts.sessionId;
        }
        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(message);
        cmd.setSessionId(sessionId);
        if (defaultAgentId != null) {
            cmd.setAgentId(defaultAgentId);
        }
        SingleResponse<ChatResponseDTO> resp;
        try {
            resp = agentService.chat(cmd);
        } catch (Exception e) {
            System.err.println("对话失败: " + e.getMessage());
            return;
        }
        if (!resp.isSuccess()) {
            System.err.println("错误: " + resp.getErrMessage());
            return;
        }
        ChatResponseDTO data = resp.getData();
        sessionId = data.getSessionId();
        String reply = data.getReply() != null ? data.getReply() : "（空回复）";
        System.out.println(reply);
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
                .completer(new ShellCompleter())
                .build();
    }

    private void printBanner() {
        println(STYLE_PROMPT, "  ◈  mwb-ai-claw Agent Shell");
        println(STYLE_INFO, "  输入消息与 Agent 对话，/help 查看命令");
        println(STYLE_INFO, "  模式: " + (streamMode ? "流式" : "同步")
                + " | 观察: " + (verbose ? "完整" : "缩写")
                + " | Agent: " + (defaultAgentId == null ? "自动路由" : defaultAgentId)
                + " | 会话: " + (sessionId == null ? "（自动创建）" : sessionId));
        println(STYLE_INFO, "");
    }

    // ==================== REPL 主循环 ====================

    private void repl() {
        while (true) {
            try {
                String input = readInput();
                if (input == null || input.trim().isEmpty()) {
                    continue;
                }
                processInput(input.trim());
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

    /**
     * 读取用户输入（支持多行）：以 ``` 开头的代码块或引号未闭合时继续追加行，
     * 直到代码块闭合 / 引号闭合 / 空行结束。
     */
    private String readInput() throws IOException {
        String first = reader.readLine(buildPrompt());
        if (first == null) {
            return null;
        }
        String input = first;
        while (needsMoreLines(input)) {
            String more = reader.readLine("  ··· ");
            if (more == null) {
                break;
            }
            input += "\n" + more;
            if (more.trim().isEmpty()) {
                break; // 空行结束多行输入
            }
        }
        return input;
    }

    /** 判断是否需要继续多行输入：未闭合的 ``` / 单双引号 / 花括号 */
    private boolean needsMoreLines(String input) {
        String trimmed = input.trim();
        if (countOccurrences(trimmed, "```") % 2 == 1) {
            return true; // 代码块未闭合
        }
        // 引号未闭合（忽略转义）
        for (char q : new char[]{'\'', '"'}) {
            boolean open = false, escaped = false;
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == q) {
                    open = !open;
                }
            }
            if (open) {
                return true;
            }
        }
        // 花括号块未闭合（如 JSON 粘贴）
        int open = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '{') open++;
            else if (c == '}') open--;
        }
        return open > 0;
    }

    private int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private String buildPrompt() {
        if (chatInProgress) {
            // 对话后台执行中（含 delegate 审批门禁等待）：显示执行状态，仅接收管理/审批命令
            return new AttributedStringBuilder()
                    .style(STYLE_WARN)
                    .append("⏳ 执行中… ")
                    .style(STYLE_INFO)
                    .append("(/pending /approve /reject 审批，回车忽略) ")
                    .toAnsi();
        }
        String prefix = streamMode ? "⚡ " : "▶ ";
        String sid = sessionId == null ? "" : "[" + sessionId.substring(0, 8) + "] ";
        int ctx = estimateContextTokens();
        String ctxTag = ctx > 0 ? "≈" + ctx + "tk " : "";
        String modeTag = planMode ? "plan " : "";
        return new AttributedStringBuilder()
                .style(STYLE_PROMPT)
                .append(prefix)
                .style(STYLE_SESSION)
                .append(sid)
                .style(STYLE_INFO)
                .append(ctxTag)
                .append(modeTag)
                .append("> ")
                .toAnsi();
    }

    /** 当前会话上下文 token 估算（缓存：会话或消息数变化时重算，避免每次按键都遍历消息） */
    private int estimateContextTokens() {
        if (sessionId == null) {
            return 0;
        }
        try {
            Session session = memoryGateway.getSession(SHELL_SCOPE, sessionId);
            if (session == null) {
                return 0;
            }
            List<Message> msgs = session.getMessages();
            int count = msgs == null ? 0 : msgs.size();
            if (!sessionId.equals(ctxCacheSessionId) || count != ctxMsgCount) {
                ctxCacheSessionId = sessionId;
                ctxMsgCount = count;
                ctxTokens = msgs == null ? 0 : TokenEstimator.estimate(msgs);
            }
            return ctxTokens;
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 命令分发 ====================

    private void processInput(String input) {
        if (chatInProgress) {
            // 对话执行中（含 delegate 审批门禁等待）：仅放行管理/审批命令，普通消息提示等待
            if (!input.startsWith("/")) {
                println(STYLE_WARN, "对话执行中，请等待其完成后再输入。可用 /pending 查看、/approve 或 /reject 处理 delegate 审批门禁。");
                return;
            }
            handleCommand(input);
            return;
        }
        if (input.startsWith("/")) {
            handleCommand(input);
        } else if (input.startsWith("!")) {
            handleBang(input);
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
            case "/mcp":
                handleMcpCommand(arg1, arg2);
                break;
            case "/agent":
                handleAgentCommand(arg1, arg2);
                break;
            case "/fork":
                forkSession(arg1);
                break;
            case "/plan":
                planMode = !planMode;
                println(STYLE_INFO, "计划模式: " + (planMode ? "开启（先输出方案，确认后执行）" : "关闭"));
                break;
            case "/memory":
                handleMemoryCommand(arg1, arg2);
                break;
            case "/metrics":
                handleMetricsCommand();
                break;
            case "/runs":
                handleRunsCommand(arg1);
                break;
            case "/compact":
                compactSession();
                break;
            case "/cost":
                showCost(arg1);
                break;
            case "/json":
                // 结构化输出：消息以 JSON 对象格式输出（response_format=json_object）
                String jsonMsg = input.length() > 5 ? input.substring(5).trim() : "";
                if (jsonMsg.isEmpty()) {
                    println(STYLE_WARN, "用法: /json <消息> —— 以 JSON 结构化输出，回复经提取并格式化展示");
                    break;
                }
                handleChat(jsonMsg, true);
                break;
            case "/clear":
                // 清屏 + 重置上下文（新建会话；旧会话保留在 /session list）
                terminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen);
                terminal.flush();
                if (sessionId != null) {
                    println(STYLE_INFO, "重置上下文: 创建新会话（原会话 " + sessionId + " 已保留）");
                }
                createSession();
                printBanner();
                break;
            case "/pending":
                handleApprovalPending(arg1);
                break;
            case "/approve":
                handleApprovalDecide(true, arg1, arg2);
                break;
            case "/reject":
                handleApprovalDecide(false, arg1, arg2);
                break;
            case "/exit":
            case "/quit":
                throw new EndOfFileException();
            default:
                CustomCommand cc = customCommands.get(cmd);
                if (cc != null) {
                    // 自定义斜杠命令：模板替换占位符后作为消息发送
                    String argsText = input.length() > parts[0].length()
                            ? input.substring(parts[0].length()).trim() : "";
                    handleCustomCommand(cc, argsText);
                } else {
                    println(STYLE_WARN, "未知命令: " + cmd + "，输入 /help 查看帮助");
                }
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
            case "rename":
                // /session rename <sessionId> <title> —— arg 此时可能含标题，需要重新切分
                handleRename(arg);
                break;
            case "export":
                // /session export <id> [路径] —— arg 含 id 与可选路径
                exportSession(arg);
                break;
            default:
                println(STYLE_WARN, "未知子命令: " + sub + "，支持: new, list, switch, delete, rename, export");
        }
    }

    /** /session rename <id> <title>：arg 为原始参数串（可能含空格标题） */
    private void handleRename(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            println(STYLE_WARN, "用法: /session rename <sessionId> <标题>");
            return;
        }
        String[] parts = arg.split("\\s+", 2);
        String target = parts[0];
        String title = parts.length > 1 ? parts[1].trim() : null;
        if (title == null || title.isEmpty()) {
            println(STYLE_WARN, "用法: /session rename <sessionId> <标题>");
            return;
        }
        try {
            String resolvedId = resolveSessionId(target);
            if (resolvedId == null) {
                println(STYLE_WARN, "会话不存在: " + target);
                return;
            }
            Session session = memoryGateway.getSession(SHELL_SCOPE, resolvedId);
            session.setTitle(title);
            memoryGateway.saveSession(session);
            println(STYLE_INFO, "已重命名会话 " + resolvedId + " → " + title);
        } catch (Exception e) {
            println(STYLE_ERROR, "重命名会话失败: " + e.getMessage());
        }
    }

    /** 会话 ID 解析：支持精确 ID 或前缀模糊匹配 */
    private String resolveSessionId(String targetId) {
        Session direct = memoryGateway.getSession(SHELL_SCOPE, targetId);
        if (direct != null) {
            return targetId;
        }
        for (Session s : memoryGateway.listSessions(SHELL_SCOPE)) {
            if (s.getSessionId().startsWith(targetId)) {
                return s.getSessionId();
            }
        }
        return null;
    }

    // ==================== 自定义斜杠命令 ====================

    /** 启动时加载自定义命令（~/.claw/commands/*.md 等） */
    private void loadCustomCommands() {
        for (CustomCommand cc : new CustomCommandLoader().load()) {
            customCommands.put(cc.getName(), cc);
        }
        if (!customCommands.isEmpty()) {
            println(STYLE_INFO, "已加载自定义命令 " + customCommands.size() + " 个: /" + String.join(", /", customCommands.keySet()));
        }
    }

    /** 执行自定义命令：模板引擎渲染后作为消息发送；output=json 时以结构化输出（response_format=json_object）并格式化展示 */
    private void handleCustomCommand(CustomCommand cc, String argsText) {
        String template = cc.getTemplate();
        if (template == null || template.isEmpty()) {
            println(STYLE_WARN, "命令 /" + cc.getName() + " 无模板内容");
            return;
        }
        String message = new TemplateEngine(argsText).render(template);
        if (!cc.getDescription().isEmpty()) {
            println(STYLE_ACTION, "[/" + cc.getName() + "] " + cc.getDescription());
        }
        if ("json".equalsIgnoreCase(cc.getOutput())) {
            chatOnce(message, true, null);
        } else {
            chatOnce(message);
        }
    }

    /** 结构化产物展示：output=json 时提取 JSON 并缩进格式化；提取失败告警并保留原文（不中断会话） */
    private void displayStructuredJson(String reply) {
        String json = JsonUtils.extractJson(reply);
        if (json == null) {
            println(STYLE_WARN, "（output=json 但未能从回复中提取到合法 JSON，保留原文）");
            terminal.writer().print(markdownRenderer.render(reply));
            terminal.writer().flush();
            return;
        }
        try {
            String pretty = JsonUtils.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(JsonUtils.readTree(json));
            println(STYLE_INFO, "");
            println(STYLE_PROMPT, "◈ 结构化产物 ◈");
            terminal.writer().print(pretty);
            terminal.writer().flush();
        } catch (Exception e) {
            println(STYLE_WARN, "（JSON 格式化失败，保留原文: " + e.getMessage() + "）");
            terminal.writer().print(markdownRenderer.render(reply));
            terminal.writer().flush();
        }
    }

    // ==================== 多模态输入解析（D2） ====================

    /**
     * 解析用户输入中的图片附件（D2 多模态，见 {@link MultimodalInputParser}）：
     * <ul>
     *   <li>{@code ![描述](路径|URL)} — Markdown 图片语法，URL 转 image_url，本地路径转 base64；</li>
     *   <li>{@code @路径} — 本地图片附件标记（路径存在且为图片扩展名时生效）。</li>
     * </ul>
     */
    private MultimodalInputParser.Result parseMultimodalInput(String input) {
        return MultimodalInputParser.parse(input);
    }

    // ==================== MCP 管理 ====================

    /** /mcp：查看 / 连接 / 断开 MCP Server */
    private void handleMcpCommand(String sub, String arg) {
        if (sub == null || sub.equalsIgnoreCase("list")) {
            List<McpServerConfig> configs = mcpClientManager.getServerConfigs();
            if (configs == null || configs.isEmpty()) {
                println(STYLE_INFO, "未配置 MCP Server（mcp-server.json 为空）");
                return;
            }
            println(STYLE_PROMPT, "◈ MCP Servers ◈");
            for (McpServerConfig c : configs) {
                boolean connected = mcpClientManager.isConnected(c.getName());
                int toolCount = connected ? mcpClientManager.getServerToolNames(c.getName()).size() : 0;
                String state = connected ? "已连接" : "未连接";
                String mark = connected ? "*" : " ";
                println(STYLE_INFO, String.format("  %s %-20s %s | transport=%-6s | enabled=%-5s | 工具=%d%s",
                        mark, c.getName(), state, c.getTransport(), c.isEnabled(), toolCount,
                        connected ? "" : "（/mcp connect 可连接）"));
            }
            return;
        }
        if (sub.equalsIgnoreCase("connect") || sub.equalsIgnoreCase("disconnect")) {
            if (arg == null || arg.trim().isEmpty()) {
                println(STYLE_WARN, "用法: /mcp " + sub + " <serverName>");
                return;
            }
            String name = arg.trim();
            if (sub.equalsIgnoreCase("connect")) {
                boolean ok = mcpClientManager.reconnectServer(name);
                if (ok) {
                    println(STYLE_INFO, "MCP Server 已连接: " + name);
                } else {
                    println(STYLE_ERROR, "连接失败（未配置或初始化异常）: " + name);
                }
            } else {
                boolean ok = mcpClientManager.disconnectServer(name);
                if (ok) {
                    println(STYLE_INFO, "MCP Server 已断开: " + name);
                } else {
                    println(STYLE_WARN, "MCP Server 未连接: " + name);
                }
            }
            return;
        }
        println(STYLE_WARN, "未知子命令: " + sub + "，支持: list, connect <name>, disconnect <name>");
    }

    // ==================== 后台 Agent ====================

    /** 启动后台 agent：独立线程执行一次对话（新会话），结果可经 /agent attach 查看 */
    private void startBackgroundAgent(String prompt) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        BackgroundAgentTask task = new BackgroundAgentTask(id, prompt);
        bgTasks.put(id, task);
        println(STYLE_INFO, "后台 agent 已启动: " + id + "（/agent list 查看状态，/agent attach " + id + " 查看结果）");
        Thread t = new Thread(task::run, "bg-agent-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** /agent：查看后台 agent 任务 / 附加查看结果 */
    private void handleAgentCommand(String sub, String arg) {
        if (sub == null || sub.equalsIgnoreCase("list")) {
            if (bgTasks.isEmpty()) {
                println(STYLE_INFO, "暂无后台 agent 任务（--bg \"任务\" 可启动）");
                return;
            }
            println(STYLE_PROMPT, "◈ 后台 Agent 任务 ◈");
            for (BackgroundAgentTask t : bgTasks.values()) {
                long sec = (System.currentTimeMillis() - t.startTime) / 1000;
                String sid = t.sessionId == null ? "-" : t.sessionId.substring(0, Math.min(8, t.sessionId.length()));
                println(STYLE_INFO, String.format("  %s | %s | %d 秒 | 会话=%s | %s",
                        t.id, t.status, sec, sid, abbreviate(t.prompt, 40)));
            }
            return;
        }
        if (sub.equalsIgnoreCase("attach")) {
            if (arg == null || arg.trim().isEmpty()) {
                println(STYLE_WARN, "用法: /agent attach <id>");
                return;
            }
            BackgroundAgentTask t = bgTasks.get(arg.trim());
            if (t == null) {
                println(STYLE_WARN, "任务不存在: " + arg);
                return;
            }
            println(STYLE_INFO, "后台 agent " + t.id + " 状态: " + t.status);
            if (t.error != null) {
                println(STYLE_ERROR, "错误: " + t.error);
            }
            if (t.sessionId != null) {
                println(STYLE_INFO, "会话: " + t.sessionId);
            }
            if (t.lastReply != null && !t.lastReply.isEmpty()) {
                println(STYLE_PROMPT, "── 结果 ──");
                terminal.writer().print(markdownRenderer.render(t.lastReply));
                terminal.writer().flush();
            }
            return;
        }
        println(STYLE_WARN, "未知子命令: " + sub + "，支持: list, attach <id>");
    }

    /** 后台 Agent 任务：独立线程执行一次对话（新会话） */
    private final class BackgroundAgentTask {
        final String id;
        final String prompt;
        final long startTime = System.currentTimeMillis();
        volatile String status = "运行中"; // 运行中 / 已完成 / 失败
        volatile String error;
        volatile String sessionId;
        volatile String lastReply;

        BackgroundAgentTask(String id, String prompt) {
            this.id = id;
            this.prompt = prompt;
        }

        void run() {
            try {
                ChatCmd cmd = new ChatCmd();
                cmd.setMessage(prompt);
                cmd.setSessionId(null); // 后台任务使用独立新会话
                if (defaultAgentId != null) {
                    cmd.setAgentId(defaultAgentId);
                }
                SingleResponse<ChatResponseDTO> resp = agentService.chat(cmd);
                if (resp.isSuccess() && resp.getData() != null) {
                    sessionId = resp.getData().getSessionId();
                    lastReply = resp.getData().getReply();
                    status = "已完成";
                } else {
                    status = "失败";
                    error = resp.getErrMessage();
                }
            } catch (Exception e) {
                status = "失败";
                error = e.getMessage();
            }
        }
    }

    // ==================== 会话导出 / 分叉 ====================

    /** /session export <id> [路径]：导出会话为 JSON 文件（默认 ~/.claw/exports/<id>.json） */
    private void exportSession(String arg) {
        String[] p = (arg == null || arg.trim().isEmpty()) ? new String[0] : arg.split("\\s+", 2);
        String target = p.length > 0 ? p[0] : null;
        String path = p.length > 1 ? p[1] : null;
        if (target == null || target.isEmpty()) {
            if (sessionId == null) {
                println(STYLE_WARN, "用法: /session export <会话ID> [文件路径]");
                return;
            }
            target = sessionId;
        }
        try {
            String resolvedId = resolveSessionId(target);
            if (resolvedId == null) {
                println(STYLE_WARN, "会话不存在: " + target);
                return;
            }
            Session session = memoryGateway.getSession(SHELL_SCOPE, resolvedId);
            String json = JsonUtils.toJson(session);
            File out = path == null
                    ? new File(System.getProperty("user.home") + File.separator + ".claw/exports", resolvedId + ".json")
                    : new File(path);
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
            println(STYLE_INFO, "已导出会话 " + resolvedId + " → " + out.getAbsolutePath() + "（" + json.length() + " 字节）");
        } catch (Exception e) {
            println(STYLE_ERROR, "导出失败: " + e.getMessage());
        }
    }

    /** /fork [会话ID]：复制指定（默认当前）会话为独立新会话并切换过去 */
    private void forkSession(String targetId) {
        String srcId = targetId;
        if (srcId == null || srcId.trim().isEmpty()) {
            if (sessionId == null) {
                println(STYLE_WARN, "用法: /fork [会话ID]");
                return;
            }
            srcId = sessionId;
        }
        try {
            String resolvedId = resolveSessionId(srcId.trim());
            if (resolvedId == null) {
                println(STYLE_WARN, "会话不存在: " + srcId);
                return;
            }
            Session src = memoryGateway.getSession(SHELL_SCOPE, resolvedId);
            Session copy = new Session();
            copy.setSessionId(UUID.randomUUID().toString());
            copy.setAgentId(src.getAgentId());
            String baseTitle = src.getTitle() == null || src.getTitle().isEmpty() ? "分叉会话" : src.getTitle();
            copy.setTitle(baseTitle.length() > 24 ? baseTitle.substring(0, 24) + "…" : baseTitle + " (fork)");
            copy.setMessages(new ArrayList<>(src.getMessages()));
            memoryGateway.saveSession(copy);
            sessionId = copy.getSessionId();
            ctxCacheSessionId = null; // 清理上下文估算缓存
            println(STYLE_INFO, "已从会话 " + resolvedId + " 分叉出新会话: " + copy.getSessionId()
                    + "（含 " + copy.getMessages().size() + " 条消息）");
        } catch (Exception e) {
            println(STYLE_ERROR, "分叉失败: " + e.getMessage());
        }
    }

    /** 终端确认输入（用于计划模式批准） */
    private String readConfirm(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 上下文压缩 / 用量统计 ====================

    /** /compact：将当前会话的旧消息压缩为一条 system 摘要，保留最近 N 条消息 */
    private void compactSession() {
        if (sessionId == null) {
            println(STYLE_WARN, "当前无会话，请先发起对话");
            return;
        }
        Session session = memoryGateway.getSession(SHELL_SCOPE, sessionId);
        if (session == null) {
            println(STYLE_WARN, "会话不存在: " + sessionId);
            return;
        }
        List<Message> messages = session.getMessages();
        if (messages == null || messages.size() <= COMPACT_KEEP_MESSAGES) {
            println(STYLE_INFO, "消息量较少（" + (messages == null ? 0 : messages.size()) + " 条），暂无需压缩");
            return;
        }
        int keepFrom = messages.size() - COMPACT_KEEP_MESSAGES;
        List<Message> oldBlock = new ArrayList<>(messages.subList(0, keepFrom));
        println(STYLE_INFO, "正在压缩历史上下文（" + oldBlock.size() + " 条消息 → 摘要）…");
        String summary = llmMemorySynthesizer.summarizeBlock(SHELL_SCOPE, oldBlock);
        if (summary == null || summary.trim().isEmpty()) {
            println(STYLE_WARN, "摘要生成失败，未执行压缩");
            return;
        }
        List<Message> kept = new ArrayList<>(messages.subList(keepFrom, messages.size()));
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(Message.of("system", "以下是本会话早前对话的压缩摘要，之后的对话应在此基础上继续：\n" + summary));
        newMessages.addAll(kept);
        session.setMessages(newMessages);
        memoryGateway.saveSession(session);
        int before = TokenEstimator.estimate(messages);
        int after = TokenEstimator.estimate(newMessages);
        println(STYLE_INFO, "已压缩: " + messages.size() + " 条 → 摘要 + 最近 " + kept.size() + " 条，估算 tokens "
                + before + " → " + after + "（节省 " + (before - after) + "）");
    }

    /** 压缩时保留的最近消息条数 */
    private static final int COMPACT_KEEP_MESSAGES = 10;

    /** /cost：展示当前会话（或指定会话）的估算 token 用量 */
    private void showCost(String targetId) {
        String sid = targetId;
        if (sid == null || sid.isEmpty()) {
            if (sessionId == null) {
                println(STYLE_WARN, "当前无会话，请先发起对话");
                return;
            }
            sid = sessionId;
        }
        Session session = memoryGateway.getSession(SHELL_SCOPE, sid);
        if (session == null) {
            println(STYLE_WARN, "会话不存在: " + sid);
            return;
        }
        List<Message> messages = session.getMessages();
        int total = TokenEstimator.estimate(messages);
        int input = 0, output = 0, tool = 0;
        for (Message m : messages) {
            int t = TokenEstimator.estimate(m);
            String role = m.getRole();
            if ("assistant".equals(role)) {
                output += t;
            } else if ("tool".equals(role)) {
                tool += t;
            } else {
                input += t;
            }
        }
        println(STYLE_PROMPT, "◈ Token 用量估算（" + sid.substring(0, Math.min(8, sid.length())) + "…）◈");
        println(STYLE_INFO, String.format("  消息数: %d | 合计: %d tokens", messages.size(), total));
        println(STYLE_INFO, String.format("  用户输入: %d | 助手输出: %d | 工具结果: %d", input, output, tool));
        println(STYLE_INFO, "  （基于 TokenEstimator 近似估算：中文 1 字符≈1 token，英文 4 字符≈1 token）");
        println(STYLE_INFO, "  提示: 长会话可执行 /compact 压缩历史上下文降低用量");
    }

    // ==================== 可观测性查询（/metrics /runs） ====================

    /** /metrics：实时展示进程内 claw.* 指标（Counter / Timer 快照） */
    private void handleMetricsCommand() {
        List<Map<String, Object>> meters = metricsRecorder.snapshot();
        if (meters.isEmpty()) {
            println(STYLE_INFO, "暂无指标数据（对话 / 工具 / LLM 调用后产生）");
            return;
        }
        println(STYLE_PROMPT, "◈ 可观测性指标（claw.* 实时内存计数）◈");
        for (Map<String, Object> m : meters) {
            String name = String.valueOf(m.get("name"));
            String tags = String.valueOf(m.get("tags"));
            String line;
            if (m.containsKey("meanMs")) {
                // Timer：次数 + 均值 + 总量
                line = String.format("%s{%s}  %d 次 | 均值 %.0fms | 总计 %.0fms",
                        name, tags, ((Number) m.get("count")).longValue(),
                        ((Number) m.get("meanMs")).doubleValue(),
                        ((Number) m.get("totalMs")).doubleValue());
            } else if (m.containsKey("count")) {
                // Counter
                line = String.format("%s{%s}  %.0f 次", name, tags,
                        ((Number) m.get("count")).doubleValue());
            } else {
                line = String.format("%s{%s}  %s", name, tags, String.valueOf(m.get("value")));
            }
            println(STYLE_INFO, "  " + line);
        }
        println(STYLE_INFO, "  提示: 指标为进程内计数（重启清零）；引入 actuator 后经 /actuator/metrics 暴露");
    }

    /** /runs [日期]：查询每次运行用量记录（JSONL），空参默认今天 */
    private void handleRunsCommand(String date) {
        String day = (date == null || date.trim().isEmpty()) ? LocalDate.now().toString() : date.trim();
        List<Map<String, Object>> runs = runUsageRecorder.readRuns(day);
        if (runs.isEmpty()) {
            println(STYLE_INFO, day + " 暂无运行记录（每次对话完成后写入 {memory-dir}/runs/" + day + ".jsonl）");
            return;
        }
        int success = 0;
        long totalMs = 0;
        for (Map<String, Object> r : runs) {
            if (Boolean.TRUE.equals(r.get("success"))) {
                success++;
            }
            Object d = r.get("durationMs");
            if (d instanceof Number) {
                totalMs += ((Number) d).longValue();
            }
        }
        println(STYLE_PROMPT, "◈ 运行记录（" + day + "）◈");
        println(STYLE_INFO, String.format("  共 %d 次 | 成功 %d | 失败 %d | 平均耗时 %.0fms",
                runs.size(), success, runs.size() - success,
                runs.isEmpty() ? 0 : totalMs * 1.0 / runs.size()));
        // 明细：最新 10 条（最新在上）
        int from = Math.max(0, runs.size() - 10);
        for (int i = runs.size() - 1; i >= from; i--) {
            Map<String, Object> r = runs.get(i);
            String label = Boolean.TRUE.equals(r.get("success")) ? "成功" : "失败";
            String ts = r.get("ts") == null ? "" : abbreviate(String.valueOf(r.get("ts")), 19);
            String sid = r.get("sessionId") == null ? "" : abbreviate(String.valueOf(r.get("sessionId")), 14);
            println(STYLE_INFO, String.format("  %s %s | %s | %s | %sms | %s",
                    ts, label, sid, r.get("model"), r.get("durationMs"), r.get("orchestration")));
        }
        if (runs.size() > 10) {
            println(STYLE_INFO, "  … 仅显示最近 10 条（共 " + runs.size() + " 条）");
        }
    }

    // ==================== ! 快捷命令执行 ====================

    /**
     * ! 快捷执行：本地执行 shell 命令（复用 ShellTool 同款白名单/黑名单沙箱），
     * 输出实时展示，并将命令与输出作为上下文注入 Agent（交给模型分析）。
     */
    private void handleBang(String input) {
        String command = input.substring(1).trim();
        if (command.isEmpty()) {
            println(STYLE_WARN, "用法: !<shell命令>，如 !npm test");
            return;
        }
        println(STYLE_ACTION, "[!] 执行: " + command);
        try {
            // 复用 ShellTool 安全沙箱校验（白名单 + 黑名单 + 审批）
            toolSecurity.validateShellCommand(command);
            if (toolSecurity.isApprovalRequired(command)) {
                String mode = toolSecurity.getShellApprovalMode();
                if ("read-only".equals(mode)) {
                    println(STYLE_ERROR, "只读模式（shell-approval-mode=read-only）禁止执行该命令");
                    return;
                }
                if (!approve(command)) {
                    println(STYLE_WARN, "已取消执行");
                    return;
                }
            }

            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            long deadline = System.currentTimeMillis() + toolSecurity.getToolTimeoutSeconds() * 1000L;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (true) {
                    if (r.ready()) {
                        String line = r.readLine();
                        if (line == null) {
                            break;
                        }
                        out.append(line).append('\n');
                        println(STYLE_STREAM, "[输出] " + abbreviate(line, 200));
                    } else if (!p.isAlive()) {
                        // 进程已结束：读尽剩余输出
                        String rest;
                        while ((rest = r.readLine()) != null) {
                            out.append(rest).append('\n');
                            println(STYLE_STREAM, "[输出] " + abbreviate(rest, 200));
                        }
                        break;
                    } else if (System.currentTimeMillis() > deadline) {
                        p.destroyForcibly();
                        println(STYLE_WARN, "命令执行超过 " + toolSecurity.getToolTimeoutSeconds() + " 秒，已终止");
                        break;
                    } else {
                        Thread.sleep(50);
                    }
                }
            }
            int exitCode = p.isAlive() ? -1 : p.exitValue();

            // 将命令与输出注入上下文，交给 Agent 分析
            String masked = toolSecurity.maskSecrets(out.toString().trim());
            String message = "用户执行了命令 `" + command + "`（exit=" + exitCode + "），输出如下：\n" + masked;
            if (masked.isEmpty()) {
                message = "用户执行了命令 `" + command + "`（exit=" + exitCode + "，无输出）";
            }
            chatOnce(message);
        } catch (SecurityException e) {
            println(STYLE_ERROR, "安全拦截: " + e.getMessage());
        } catch (Exception e) {
            println(STYLE_ERROR, "命令执行失败: " + e.getMessage());
            log.error("! 命令执行异常", e);
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
                showMemoryPages(pageStore.listAllSummaries(SHELL_SCOPE), "SUMMARY");
                break;
            case "archive":
                showMemoryPages(pageStore.listAllArchive(SHELL_SCOPE), "ARCHIVE");
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
        List<MemoryPage> facts = pageStore.loadFacts(SHELL_SCOPE);
        List<MemoryPage> summaries = pageStore.listAllSummaries(SHELL_SCOPE);
        List<MemoryPage> archives = pageStore.listAllArchive(SHELL_SCOPE);
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
        List<MemoryPage> facts = pageStore.loadFacts(SHELL_SCOPE);
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
        handleChat(message, false);
    }

    private void handleChat(String message, boolean jsonOutput) {
        if (planMode) {
            // 计划模式：先让 Agent 输出方案（不执行），用户确认后再执行
            chatOnce(message + "\n\n（计划模式）请先输出详细的实施方案与具体步骤，不要调用任何工具执行操作；等待用户确认后再开始执行。");
            String answer = readConfirm("  ⚠ 是否批准该方案并开始执行? (y/N) ");
            if (answer == null || !answer.trim().toLowerCase().startsWith("y")) {
                println(STYLE_INFO, "已取消执行，方案仅供参考。可继续调整问题。");
                return;
            }
            chatOnce("用户已批准上述方案，请按方案开始执行。");
            return;
        }
        if (chatInProgress) {
            println(STYLE_WARN, "当前有对话执行中（或等待审批决策）。可用 /pending 查看、/approve 或 /reject 处理 delegate 审批门禁。");
            return;
        }
        // 后台执行对话：delegate 编排命中审批门禁等待期间，REPL 主循环仍可接收 /pending /approve /reject
        chatInProgress = true;
        chatExecutor.submit(() -> {
            try {
                chatOnce(message, jsonOutput, null);
            } finally {
                chatInProgress = false;
                println(STYLE_INFO, "");
                println(STYLE_INFO, "（对话结束，按回车返回输入）");
            }
        });
    }

    /** 发送一次对话（同步/流式）并自动生成会话标题 */
    private void chatOnce(String message) {
        chatOnce(message, false, null);
    }

    /**
     * 发送一次对话（同步/流式）。
     * <p>
     * 兼容 PhaseD 能力：
     * <ul>
     *   <li>多模态：{@code parts} 为空时自动解析消息中的图片标记（{@code ![描述](路径|URL)} 与 {@code @路径}）；</li>
     *   <li>结构化输出：{@code jsonOutput=true} 时设置 {@code response_format=json_object}，回复经 extractJson 提取并格式化展示。</li>
     * </ul>
     */
    private void chatOnce(String message, boolean jsonOutput, List<ContentPart> parts) {
        // 多模态解析：未显式提供 parts 时识别消息中的图片标记
        MultimodalInputParser.Result parsed = parts == null ? parseMultimodalInput(message) : null;
        String text = parsed != null ? parsed.text() : message;
        List<ContentPart> imageParts = parsed != null ? parsed.parts() : parts;

        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(text);
        cmd.setSessionId(sessionId);
        if (jsonOutput) {
            cmd.setResponseFormat("json_object");
        }
        if (imageParts != null && !imageParts.isEmpty()) {
            cmd.setParts(imageParts);
        }
        if (jsonOutput) {
            jsonOutputMode = true;
            capturedReply = null;
        }
        try {
            if (streamMode) {
                doStreamChat(cmd);
            } else {
                doSyncChat(cmd);
            }
            // 结构化输出：统一提取 JSON 并格式化展示
            if (jsonOutput && capturedReply != null) {
                displayStructuredJson(capturedReply);
            }
            // 首次对话后自动生成会话标题（首条用户消息截断）
            ensureSessionTitle();
        } catch (Exception e) {
            println(STYLE_ERROR, "对话失败: " + e.getMessage());
            log.error("对话异常", e);
        } finally {
            if (jsonOutput) {
                jsonOutputMode = false;
            }
        }
    }

    /** 会话无标题时，用第一条用户消息自动生成标题（截断 20 字） */
    private void ensureSessionTitle() {
        if (sessionId == null) {
            return;
        }
        try {
            Session session = memoryGateway.getSession(SHELL_SCOPE, sessionId);
            if (session == null) {
                return;
            }
            String title = session.getTitle();
            if (title != null && !title.trim().isEmpty()) {
                return; // 已有标题（手动重命名过）
            }
            for (Message m : session.getMessages()) {
                if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().trim().isEmpty()) {
                    String content = m.getContent().trim().replace('\n', ' ');
                    session.setTitle(content.length() > 20 ? content.substring(0, 20) + "…" : content);
                    memoryGateway.saveSession(session);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("自动生成会话标题失败: {}", e.getMessage());
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
        if (jsonOutputMode) {
            // 结构化输出：捕获回复，由 handleCustomCommand 统一提取格式化展示
            capturedReply = reply;
        } else {
            terminal.writer().print(markdownRenderer.render(reply));
            terminal.writer().flush();
        }
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
                if (jsonOutputMode) {
                    return; // 结构化输出：仅累积不实时渲染，最终统一格式化展示
                }
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
                        if (jsonOutputMode) {
                            capturedReply = lineBuf.toString();
                        } else {
                            terminal.writer().print(markdownRenderer.renderLine(lineBuf.toString()));
                            terminal.writer().flush();
                        }
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
            if (jsonOutputMode) {
                capturedReply = reply;
            } else {
                terminal.writer().print(markdownRenderer.render(reply));
                terminal.writer().flush();
            }
        }

        sessionId = resp.getData().getSessionId();
    }

    /** 按顺序展示每一步轨迹：思考 / 工具调用（含入参）/ 观察结果 / 命令实时输出 */
    private void printTraceStep(String step) {
        if (step == null || step.isEmpty()) {
            return;
        }
        if (step.startsWith("[Stream]")) {
            // shell 命令实时输出（逐行回显）
            String content = step.substring("[Stream]".length()).trim();
            if (!content.isEmpty()) {
                println(STYLE_STREAM, "[输出] " + abbreviate(content, 200));
            }
        } else if (step.startsWith("[Thought]")) {
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
        } else if (step.startsWith("[Stage:") || step.startsWith("[Round:")
                || step.startsWith("[Converge:") || step.startsWith("[Orchestration]")) {
            // 编排中间过程（流水线阶段 / 对话轮次 / 收敛）：原样展示，让多 Agent 协作进度可见
            println(STYLE_INFO, step);
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
        println(STYLE_INFO, "  !<命令>        → 本地执行 shell 命令并将输出交给 Agent 分析（如 !npm test）");
        println(STYLE_INFO, "  ![描述](图片路径或URL) → 发送图片给 Agent（多模态；也可用 @图片路径 附件标记）");
        println(STYLE_INFO, "");
        println(STYLE_INFO, "  命令:");
        println(STYLE_INFO, "  /help                  显示此帮助");
        println(STYLE_INFO, "  /mode                  切换 同步/流式 模式");
        println(STYLE_INFO, "  /trace                 切换 观察结果 完整/缩写");
        println(STYLE_INFO, "  /compact               压缩当前会话历史上下文（保留最近 10 条 + 摘要）");
        println(STYLE_INFO, "  /cost [id]             当前会话（或指定会话）Token 用量估算");
        println(STYLE_INFO, "  /plan                  切换计划模式（先出方案，确认后执行）");
        println(STYLE_INFO, "  /json <消息>           以 JSON 结构化输出（response_format=json_object），回复经提取格式化展示");
        println(STYLE_INFO, "  /mcp                   查看 MCP Server 列表");
        println(STYLE_INFO, "  /mcp connect <name>    连接（重连）MCP Server");
        println(STYLE_INFO, "  /mcp disconnect <name> 断开 MCP Server");
        println(STYLE_INFO, "  /agent                 查看后台 agent 任务");
        println(STYLE_INFO, "  /agent attach <id>     查看后台 agent 结果");
        println(STYLE_INFO, "  /fork [id]             分叉当前（或指定）会话为新会话");
        println(STYLE_INFO, "  /session               查看当前会话");
        println(STYLE_INFO, "  /session new           创建新会话");
        println(STYLE_INFO, "  /session list          列出所有会话");
        println(STYLE_INFO, "  /session switch <id>   切换会话");
        println(STYLE_INFO, "  /session rename <id> <标题>  重命名会话");
        println(STYLE_INFO, "  /session export <id> [path]  导出会话为 JSON");
        println(STYLE_INFO, "  /session delete <id>   删除会话");
        println(STYLE_INFO, "  /memory                分层记忆总览（配置/统计/缓存）");
        println(STYLE_INFO, "  /memory facts          查看长期记忆事实");
        println(STYLE_INFO, "  /memory summaries      查看中期摘要页");
        println(STYLE_INFO, "  /memory archive        查看跨会话档案块");
        println(STYLE_INFO, "  /memory search <q>     检索记忆召回调试");
        println(STYLE_INFO, "  /metrics               可观测性指标总览（claw.* 实时计数）");
        println(STYLE_INFO, "  /runs [yyyy-MM-dd]     运行用量记录（空参=今天）");
        println(STYLE_INFO, "  /clear                 清屏并重置上下文（新建会话）");
        println(STYLE_INFO, "  /pending [sessionId]   列出待审批节点（delegate 编排审批门禁）");
        println(STYLE_INFO, "  /approve <layerKey> [sessionId]  批准该层计划，继续委派执行");
        println(STYLE_INFO, "  /reject <layerKey> [sessionId]   拒绝该层计划（降级直执行）");
        println(STYLE_INFO, "  /exit, /quit           退出");
        if (!customCommands.isEmpty()) {
            println(STYLE_INFO, "");
            println(STYLE_INFO, "  自定义命令（~/.claw/commands/*.md）:");
            for (CustomCommand cc : customCommands.values()) {
                println(STYLE_INFO, "  /" + cc.getName() + (cc.getDescription().isEmpty() ? "" : "  " + cc.getDescription()));
            }
        }
        println(STYLE_INFO, "");
        println(STYLE_INFO, "  多行输入: 以 ``` 或 { 开头（或引号未闭合）时，空行结束");
        println(STYLE_INFO, "  Tab 补全: 斜杠命令 / 会话 ID / 文件路径");
        println(STYLE_INFO, "  启动参数: --prompt \"问题\" | --resume <id> | --mode stream|sync | --bg \"后台任务\" | --agent <专家id> | --verbose");
        println(STYLE_INFO, "  模式: " + (streamMode ? "流式（逐 token 输出）" : "同步（等待完整回复）")
                + (planMode ? " | 计划模式" : ""));
        println(STYLE_INFO, "  观察: " + (verbose ? "完整" : "缩写"));
        println(STYLE_INFO, "  审批: " + agentProperties.getSecurity().getShellApprovalMode()
                + "（auto=自动执行 / ask=询问确认 / read-only=只读）");
        println(STYLE_INFO, "  会话: " + (sessionId == null ? "自动创建" : sessionId));
        println(STYLE_INFO, "");
    }

    // ==================== 审批门禁（delegate 编排人工审批） ====================

    /**
     * 列出待审批节点（delegate 编排人工审批门禁）。
     * 不传 sessionId 时列出全部（推荐：编排首次创建会话时 shell 的 sessionId 可能尚未同步）；
     * 传入时按会话过滤。
     */
    private void handleApprovalPending(String sessionId) {
        String sid = (sessionId == null || sessionId.trim().isEmpty()) ? null : sessionId.trim();
        SingleResponse<List<PendingApprovalDTO>> resp = approvalService.pendingTasks(sid);
        if (!resp.isSuccess()) {
            println(STYLE_ERROR, "查询待审批节点失败: " + resp.getErrMessage());
            return;
        }
        List<PendingApprovalDTO> list = resp.getData();
        if (list == null || list.isEmpty()) {
            println(STYLE_INFO, "无待审批节点");
            return;
        }
        println(STYLE_INFO, "待审批节点（delegate 编排门禁）:");
        for (PendingApprovalDTO dto : list) {
            String shortSid = dto.getSessionId() == null ? "?" : dto.getSessionId();
            if (shortSid.length() > 8) {
                shortSid = shortSid.substring(0, 8);
            }
            println(STYLE_APPROVAL, String.format("  [%s] 会话=%s 层=%s todo数=%d 注册于 %tF %<tT",
                    shortSid, dto.getSessionId(), dto.getLayerKey(), dto.getTodoCount(), dto.getCreatedAt()));
            if (dto.getTask() != null && !dto.getTask().trim().isEmpty()) {
                println(STYLE_INFO, "    任务: " + abbreviate(dto.getTask(), 80));
            }
            if (dto.getTodoTitles() != null && !dto.getTodoTitles().isEmpty()) {
                println(STYLE_INFO, "    计划: " + String.join(" | ", dto.getTodoTitles()));
            }
            println(STYLE_INFO, "    决策: /approve " + dto.getLayerKey() + " " + dto.getSessionId()
                    + " 或 /reject " + dto.getLayerKey() + " " + dto.getSessionId());
        }
    }

    /**
     * 审批决策（approve / reject）：
     * layerKey 必填；sessionId 可选（默认当前会话——编排首次创建会话时 shell 可能未同步，
     * 请使用 /pending 输出的真实 sessionId）。
     */
    private void handleApprovalDecide(boolean approved, String layerKey, String sessionId) {
        if (layerKey == null || layerKey.trim().isEmpty()) {
            println(STYLE_WARN, "用法: /" + (approved ? "approve" : "reject") + " <layerKey> [sessionId]");
            return;
        }
        String sid = (sessionId == null || sessionId.trim().isEmpty()) ? this.sessionId : sessionId.trim();
        ApprovalCmd cmd = new ApprovalCmd();
        cmd.setSessionId(sid);
        cmd.setLayerKey(layerKey.trim());
        SingleResponse<Void> resp = approved ? approvalService.approve(cmd) : approvalService.reject(cmd);
        if (resp.isSuccess()) {
            println(STYLE_INFO, "已" + (approved ? "批准" : "拒绝") + "该层计划: " + layerKey.trim());
        } else {
            println(STYLE_ERROR, (approved ? "批准" : "拒绝") + "失败: " + resp.getErrMessage()
                    + "（可用 /pending 查看当前待审批节点）");
        }
    }

    // ==================== 命令审批（ToolApproval） ====================

    /**
     * 高风险 Shell 命令审批：在终端弹 Y/N 确认。
     * <p>
     * 由 ShellTool 在 ask 审批模式下调用（同一线程），阻塞等待用户输入；
     * 用户输入 y/yes 放行，其余拒绝。
     */
    @Override
    public boolean approve(String command) {
        if (reader == null) {
            // headless / 非交互场景无法弹确认：安全默认拒绝
            System.err.println("[mwb-ai-claw] 非交互模式无法确认命令，已拒绝执行: " + command);
            return false;
        }
        println(STYLE_APPROVAL, "⚠ 需要批准执行 Shell 命令:");
        println(STYLE_ACTION, "  " + command);
        try {
            String answer = reader.readLine("  批准? (y/N) ");
            if (answer != null) {
                String a = answer.trim().toLowerCase();
                return a.startsWith("y");
            }
        } catch (Exception e) {
            log.warn("审批输入异常: {}", e.getMessage());
        }
        return false;
    }

    // ==================== 终端输出工具 ====================

    /** 标记结果区已开始输出（首次调用时置位），供兜底渲染判断是否已有内容输出 */
    private void beginResultSection() {
        resultStarted = true;
    }

    /** 打印工具调用（执行区）：工具名 + 缩写入参（敏感信息脱敏展示） */
    private void printToolInvocation(String toolName, String arguments) {
        println(STYLE_ACTION, "[工具] " + toolName);
        String args = abbreviate(toolSecurity.maskSecrets(arguments), 120);
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

    // ==================== 启动参数解析 ====================

    /** 启动参数：--prompt <text>（headless 单轮）/ -p <text> / --resume <sessionId> / --mode stream|sync
     *  / --bg "后台任务" / --agent <专家id> / --verbose */
    private static final class ShellOptions {
        String prompt;
        String sessionId;
        String mode;
        String bgPrompt;
        String agentId;
        boolean verbose;

        static ShellOptions parse(String[] args) {
            ShellOptions opts = new ShellOptions();
            if (args == null) {
                return opts;
            }
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                String key = a;
                String value = null;
                int eq = a.indexOf('=');
                if (eq > 0) {
                    key = a.substring(0, eq);
                    value = a.substring(eq + 1);
                }
                switch (key) {
                    case "--prompt":
                    case "-p":
                        if (value == null && i + 1 < args.length) {
                            value = args[++i];
                        }
                        opts.prompt = value;
                        break;
                    case "--resume":
                        if (value == null && i + 1 < args.length) {
                            value = args[++i];
                        }
                        opts.sessionId = value;
                        break;
                    case "--mode":
                        if (value == null && i + 1 < args.length) {
                            value = args[++i];
                        }
                        opts.mode = value;
                        break;
                    case "--bg":
                        if (value == null && i + 1 < args.length) {
                            value = args[++i];
                        }
                        opts.bgPrompt = value;
                        break;
                    case "--agent":
                        if (value == null && i + 1 < args.length) {
                            value = args[++i];
                        }
                        opts.agentId = value;
                        break;
                    case "--verbose":
                        opts.verbose = true;
                        break;
                    default:
                        // 忽略 Spring Boot 等其他参数
                        break;
                }
            }
            return opts;
        }
    }

    // ==================== Tab 补全 ====================

    /** 可补全的斜杠命令 */
    private static final List<String> SLASH_COMMANDS = Arrays.asList(
            "/help", "/mode", "/trace", "/session", "/session new", "/session list",
            "/session switch ", "/session delete ", "/session rename ", "/session export ",
            "/fork ", "/plan", "/mcp", "/mcp list", "/mcp connect ", "/mcp disconnect ",
            "/agent", "/agent list", "/agent attach ",
            "/memory", "/memory stats", "/memory facts", "/memory summaries",
            "/memory archive", "/memory search ", "/compact", "/cost", "/json ", "/clear",
            "/metrics", "/runs ", "/pending", "/approve ", "/reject ", "/exit", "/quit");

    /** 终端补全器：斜杠命令 / 会话 ID（switch/delete/rename 场景）/ 文件路径 */
    private final class ShellCompleter implements Completer {
        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            String word = line.word();
            String buffer = line.line().trim();

            // 会话 ID 补全：/session switch|delete|rename <前缀>
            if (buffer.startsWith("/session") && (buffer.contains("switch") || buffer.contains("delete") || buffer.contains("rename"))) {
                try {
                    for (Session s : memoryGateway.listSessions(SHELL_SCOPE)) {
                        if (s.getSessionId().startsWith(word)) {
                            candidates.add(new Candidate(s.getSessionId()));
                        }
                    }
                } catch (Exception ignore) {
                    // 补全失败不影响输入
                }
                return;
            }

            // 斜杠命令补全（内置 + 自定义）
            if (word.startsWith("/")) {
                for (String c : SLASH_COMMANDS) {
                    if (c.startsWith(word)) {
                        candidates.add(new Candidate(c));
                    }
                }
                for (String name : customCommands.keySet()) {
                    String slash = "/" + name;
                    if (slash.startsWith(word)) {
                        candidates.add(new Candidate(slash + " "));
                    }
                }
                return;
            }

            // 普通输入：文件路径补全（复用 JLine FileNameCompleter）
            new FileNameCompleter().complete(reader, line, candidates);
        }
    }
}
