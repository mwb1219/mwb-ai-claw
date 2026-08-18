package com.mwb.ai.claw.domain.tool;

/**
 * 工具审批门：执行高风险 Shell 命令前征求用户批准（交互终端可弹 Y/N 确认）。
 * <p>
 * 由适配层按接入场景注入实现（如 Shell REPL 使用 JLine 终端弹确认）；
 * 未注入实现时（如 REST 场景未配置审批处理器），ShellTool 在 ask 模式下安全默认拒绝。
 */
@FunctionalInterface
public interface ToolApproval {

    /**
     * 请求用户批准执行命令。
     *
     * @param command 待批准的 Shell 命令全文
     * @return true = 放行执行；false = 拒绝执行
     */
    boolean approve(String command);
}
