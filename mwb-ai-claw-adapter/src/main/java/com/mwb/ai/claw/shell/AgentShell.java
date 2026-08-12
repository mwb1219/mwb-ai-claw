package com.mwb.ai.claw.shell;

import com.alibaba.cola.dto.SingleResponse;
import com.mwb.ai.claw.agent.executor.ChatCmdExe;
import com.mwb.ai.claw.api.AgentServiceI;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;
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

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

    private Terminal terminal;
    private LineReader reader;
    private String sessionId;
    private boolean streamMode = true;    // 默认流式模式

    // ANSI 风格
    private static final AttributedStyle STYLE_PROMPT = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
    private static final AttributedStyle STYLE_INFO = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE);
    private static final AttributedStyle STYLE_AI = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    private static final AttributedStyle STYLE_THOUGHT = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
    private static final AttributedStyle STYLE_ACTION = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle STYLE_OBS = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE);
    private static final AttributedStyle STYLE_ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final AttributedStyle STYLE_WARN = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();
    private static final AttributedStyle STYLE_SESSION = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).faint();

    @Override
    public void run(String... args) throws Exception {
        initTerminal();
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
        println(STYLE_INFO, "  模式: " + (streamMode ? "流式" : "同步") + " | 会话: " + (sessionId == null ? "（自动创建）" : sessionId));
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
            case "/session":
                handleSessionCommand(arg1, arg2);
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
        println(STYLE_INFO, "（同步等待中…）");
        SingleResponse<ChatResponseDTO> resp = agentService.chat(cmd);
        if (!resp.isSuccess()) {
            println(STYLE_ERROR, "错误: " + resp.getErrMessage());
            return;
        }
        ChatResponseDTO data = resp.getData();
        sessionId = data.getSessionId();

        // 打印轨迹
        if (data.getTraceSteps() != null) {
            for (String step : data.getTraceSteps()) {
                printTrace(step);
            }
        }
        // 打印回复
        println(STYLE_AI, data.getReply() != null ? data.getReply() : "（空回复）");
    }

    private void doStreamChat(ChatCmd cmd) {
        ProgressCallback progressCb = step -> terminal.writer().println(styleLine(step));
        LlmStreamCallback streamCb = new LlmStreamCallback() {
            @Override
            public void onToken(String token) {
                terminal.writer().print(ansi(STYLE_AI, token));
                terminal.writer().flush();
            }

            @Override
            public void onToolName(String toolName) {
                terminal.writer().print(ansi(STYLE_ACTION, "[调用工具: " + toolName + "] "));
                terminal.writer().flush();
            }
        };

        println(STYLE_INFO, ""); // 换行
        SingleResponse<ChatResponseDTO> resp = chatCmdExe.execute(cmd, progressCb, streamCb);
        println(STYLE_INFO, ""); // 换行

        if (!resp.isSuccess()) {
            println(STYLE_ERROR, "错误: " + resp.getErrMessage());
            return;
        }
        sessionId = resp.getData().getSessionId();
    }

    /** 根据内容自动识别轨迹类型并着色 */
    private void printTrace(String step) {
        terminal.writer().println(styleLine(step));
        terminal.writer().flush();
    }

    private String styleLine(String step) {
        if (step == null || step.isEmpty()) return "";
        if (step.startsWith("[Thought")) {
            return ansi(STYLE_THOUGHT, step);
        } else if (step.startsWith("[Action")) {
            return ansi(STYLE_ACTION, step);
        } else if (step.startsWith("[Observation")) {
            return ansi(STYLE_OBS, step);
        }
        return step;
    }

    // ==================== 会话管理 ====================

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
        println(STYLE_INFO, "  /session               查看当前会话");
        println(STYLE_INFO, "  /session new           创建新会话");
        println(STYLE_INFO, "  /session list          列出所有会话");
        println(STYLE_INFO, "  /session switch <id>   切换会话");
        println(STYLE_INFO, "  /session delete <id>   删除会话");
        println(STYLE_INFO, "  /clear                 清屏");
        println(STYLE_INFO, "  /exit, /quit           退出");
        println(STYLE_INFO, "");
        println(STYLE_INFO, "  模式: " + (streamMode ? "流式（逐 token 输出）" : "同步（等待完整回复）"));
        println(STYLE_INFO, "  会话: " + (sessionId == null ? "自动创建" : sessionId));
        println(STYLE_INFO, "");
    }

    // ==================== 终端输出工具 ====================

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
