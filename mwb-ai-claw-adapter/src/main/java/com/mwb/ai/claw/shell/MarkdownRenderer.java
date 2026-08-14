package com.mwb.ai.claw.shell;

/**
 * 轻量 Markdown 渲染器：将 Markdown 文本渲染为带 ANSI 颜色的终端输出。
 * <p>
 * 支持标题、代码块围栏、行内代码、加粗、引用、列表等常见 Markdown 元素。
 * <p>
 * 可用于流式逐行渲染（{@link #renderLine}，保留跨行代码块状态）或一次性渲染（{@link #render}）。
 */
public class MarkdownRenderer {

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String CYAN = "\u001b[36m";
    private static final String BRIGHT_CYAN = "\u001b[96m";
    private static final String YELLOW = "\u001b[33m";
    private static final String GRAY = "\u001b[90m";

    /** 是否处于代码块内部（跨行状态） */
    private boolean inCodeBlock = false;

    /** 重置渲染状态（开始渲染一段新的 Markdown 前调用） */
    public void reset() {
        inCodeBlock = false;
    }

    /** 一次性渲染完整 Markdown 文本 */
    public String render(String markdown) {
        if (markdown == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        for (String line : lines) {
            sb.append(renderLine(line)).append('\n');
        }
        return sb.toString();
    }

    /** 渲染单行 Markdown（保留跨行代码块状态），返回 ANSI 字符串 */
    public String renderLine(String line) {
        if (line == null) {
            return "";
        }
        String t = line.trim();

        // 代码块围栏 ``` 切换状态
        if (t.startsWith("```")) {
            inCodeBlock = !inCodeBlock;
            return BRIGHT_CYAN + line + RESET;
        }
        if (inCodeBlock) {
            return BRIGHT_CYAN + line + RESET;
        }

        // 标题
        if (t.matches("^#{1,6}\\s+.*")) {
            return BOLD + CYAN + line + RESET;
        }
        // 引用
        if (t.startsWith(">")) {
            return DIM + GRAY + line + RESET;
        }

        return renderInline(line);
    }

    /** 处理行内样式：行内代码、加粗 */
    private String renderInline(String text) {
        text = text.replaceAll("`([^`]+)`", YELLOW + "$1" + RESET);
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", BOLD + "$1" + RESET);
        return text;
    }
}
