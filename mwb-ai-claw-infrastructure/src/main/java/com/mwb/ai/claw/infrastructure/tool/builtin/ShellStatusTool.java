package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.tool.ToolSecurity;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.ShellStatusParams;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * shell_status 工具：查询 / 获取输出 / 终止后台任务。
 * <p>
 * 配合 shell 工具的 background 参数或超时转后台机制使用：
 * shell 返回 taskId 后，LLM 可调用本工具查询任务状态、取回输出或终止任务。
 */
@Component
public class ShellStatusTool implements ToolExecutor {

    @Resource
    private ShellProcessManager processManager;

    @Resource
    private ToolSecurity toolSecurity;

    @Override
    public String getName() {
        return "shell_status";
    }

    @Override
    public ToolSpec getSpec() {
        String params = "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"taskId\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"后台任务 ID（shell 工具返回的 taskId）\"\n"
                + "    },\n"
                + "    \"action\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"enum\": [\"status\", \"output\", \"kill\"],\n"
                + "      \"description\": \"操作：status（查询状态，默认）| output（获取完整输出）| kill（终止任务）\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"required\": [\"taskId\"]\n"
                + "}";
        return new ToolSpec("shell_status",
                "查询 / 获取输出 / 终止 shell 后台任务（shell 工具超时转后台或 background=true 时返回 taskId）",
                params);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            ShellStatusParams params = JsonUtils.fromJson(argumentsJson, ShellStatusParams.class);
            String taskId = params.getTaskId();
            if (taskId == null || taskId.trim().isEmpty()) {
                return ToolResult.error("taskId 不能为空");
            }
            ShellProcessManager.ShellTask task = processManager.get(taskId.trim());
            if (task == null) {
                return ToolResult.error("后台任务不存在: " + taskId);
            }

            String action = params.getAction() == null ? "status" : params.getAction().trim().toLowerCase();
            switch (action) {
                case "kill":
                    task.kill();
                    return ToolResult.success("已终止后台任务 " + taskId);
                case "output":
                    return ToolResult.success(toolSecurity.truncateOutput(toolSecurity.maskSecrets(task.getOutput())));
                case "status":
                default:
                    String state = task.isDone() ? "已完成" : "运行中";
                    long elapsedSec = (System.currentTimeMillis() - task.getStartTime()) / 1000;
                    return ToolResult.success(String.format(
                            "任务 %s: %s | 退出码=%d | 已运行 %d 秒 | 当前输出 %d 字符\n"
                                    + "（action=output 获取完整输出，action=kill 终止任务）",
                            taskId, state, task.getExitCode(), elapsedSec, task.getOutput().length()));
            }
        } catch (Exception e) {
            return ToolResult.error("shell_status 执行失败: " + e.getMessage());
        }
    }
}
