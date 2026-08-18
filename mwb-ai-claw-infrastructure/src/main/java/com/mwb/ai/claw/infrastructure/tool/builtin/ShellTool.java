package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.tool.ToolApproval;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.tool.ToolSecurity;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.ShellParams;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Shell 工具：在受限环境中执行 Shell 命令。
 * <p>
 * 受安全沙箱保护：命令必须通过白名单（按命令段逐段校验）与黑名单校验，执行有超时限制，输出有长度截断。
 * 能力：
 * <ul>
 *   <li><b>完整 shell 语义</b>：经 {@code bash -lc}（Windows 为 {@code cmd /c}）执行，支持管道 / 重定向 / 通配符 / 环境变量 / 逻辑连接符；</li>
 *   <li><b>审批模式</b>：ask 模式下命中审批规则时向用户弹确认，read-only 模式下命中审批规则直接拒绝；</li>
 *   <li><b>后台任务</b>：background=true 或前台超时不强杀，返回 taskId，由 shell_status 工具查询 / 终止；</li>
 *   <li><b>流式回显</b>：通过 ProgressCallback 逐行推送 {@code "[Stream] ..."} 实时输出。</li>
 * </ul>
 */
@Component
public class ShellTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);

    @Resource
    private ToolSecurity toolSecurity;

    @Resource
    private ShellProcessManager processManager;

    /**
     * 审批处理器提供者：由适配层（如 Shell REPL）实现并注册；
     * 用 ObjectProvider 延迟解析，避免 ShellTool → ToolApproval → AgentShell 的 Bean 循环依赖。
     * 未注册任何处理器时 ask 模式安全默认拒绝。
     */
    @Autowired
    private ObjectProvider<ToolApproval> toolApprovalProvider;

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public ToolSpec getSpec() {
        String params = "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"command\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"要执行的 Shell 命令（支持管道、重定向、通配符、&& 等完整 shell 语法）\"\n"
                + "    },\n"
                + "    \"workingDir\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"可选的工作目录\"\n"
                + "    },\n"
                + "    \"background\": {\n"
                + "      \"type\": \"boolean\",\n"
                + "      \"description\": \"可选。true 时立即返回 taskId 并在后台运行，不等待执行完成；可用 shell_status 查询状态/输出或终止\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"required\": [\"command\"]\n"
                + "}";
        return new ToolSpec("shell", "在受限环境中执行 Shell 命令（受白名单、审批和超时保护）", params);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        return execute(argumentsJson, null);
    }

    @Override
    public ToolResult execute(String argumentsJson, ProgressCallback callback) {
        try {
            ShellParams params = JsonUtils.fromJson(argumentsJson, ShellParams.class);
            String command = params.getCommand();
            String workingDir = params.getWorkingDir();
            boolean background = Boolean.TRUE.equals(params.getBackground());

            if (command == null || command.trim().isEmpty()) {
                return ToolResult.error("命令不能为空");
            }

            // 安全校验：黑名单 + 白名单（按命令段逐段校验）
            toolSecurity.validateShellCommand(command);

            // 审批：ask 模式命中审批规则 → 请求用户确认；read-only 模式命中 → 直接拒绝
            if (toolSecurity.isApprovalRequired(command)) {
                String mode = toolSecurity.getShellApprovalMode();
                if ("read-only".equals(mode)) {
                    return ToolResult.error("只读模式（shell-approval-mode=read-only）禁止执行该命令: " + command);
                }
                ToolApproval approval = toolApprovalProvider.getIfAvailable();
                if (approval == null) {
                    return ToolResult.error("命令需要用户批准，但当前环境无审批处理器（shell-approval-mode=ask），已拒绝执行: " + command);
                }
                if (!approval.approve(command)) {
                    return ToolResult.error("用户拒绝执行命令: " + command);
                }
            }

            Process process = buildProcess(command, workingDir).start();

            // 注册后台任务：守护线程持续读取输出；callback 非空时逐行实时回显（先脱敏再推送）
            ShellProcessManager.ShellTask task = processManager.register(process,
                    callback == null ? null : line -> callback.onProgress("[Stream] " + toolSecurity.maskSecrets(line)));

            if (background) {
                return ToolResult.success("后台任务已启动: taskId=" + task.getId()
                        + "（可用 shell_status 查询状态/输出，action=kill 终止）");
            }

            // 前台执行：等待完成；超时不强杀，转为后台任务继续运行
            int timeoutSeconds = toolSecurity.getToolTimeoutSeconds();
            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task.kill();
                return ToolResult.error("命令执行被中断");
            }
            if (!finished) {
                return ToolResult.success("命令执行超过 " + timeoutSeconds + " 秒，已转为后台任务继续运行: taskId=" + task.getId()
                        + "（可用 shell_status 查询结果，action=kill 终止）\n"
                        + "部分输出:\n" + maskAndTruncate(task.getOutput()));
            }

            task.joinReader(2000);
            int exitCode = task.getExitCode();
            String truncated = maskAndTruncate(task.getOutput());
            if (exitCode == 0) {
                return ToolResult.success(truncated);
            } else {
                return ToolResult.error("命令执行失败 (exit=" + exitCode + "):\n" + truncated);
            }
        } catch (SecurityException e) {
            log.warn("Shell 安全校验失败: {}", e.getMessage());
            return ToolResult.error("安全拦截: " + e.getMessage());
        } catch (Exception e) {
            log.error("Shell 执行失败", e);
            return ToolResult.error("Shell 执行失败: " + e.getMessage());
        }
    }

    /** 输出脱敏 + 截断（先截断减少脱敏计算，再打码避免密钥明文进入上下文） */
    private String maskAndTruncate(String output) {
        return toolSecurity.maskSecrets(toolSecurity.truncateOutput(output));
    }

    /**
     * 构建进程：经系统 shell 执行以支持完整 shell 语义（管道/重定向/通配符/变量/&& 等）。
     * macOS/Linux 使用 bash -lc（继承用户登录 shell 环境），Windows 使用 cmd /c。
     */
    private ProcessBuilder buildProcess(String command, String workingDir) throws IOException {
        ProcessBuilder pb;
        if (isWindows()) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("bash", "-lc", command);
        }
        pb.redirectErrorStream(true);

        // 设置工作目录（需在 workspace 范围内）
        if (workingDir != null && !workingDir.isEmpty()) {
            java.nio.file.Path dir = toolSecurity.resolveAndValidatePath(workingDir);
            pb.directory(dir.toFile());
        }
        return pb;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
