package com.mwb.ai.claw.shell.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义命令模板引擎（D3）。
 * <p>
 * 在旧 {@code {args}} / {@code {1}}-{@code {9}} 占位符基础上新增 {@code {{...}}} 语法（两者并存）：
 * <ul>
 *   <li>变量：{@code {{args}}} 全量参数、{@code {{1}}}-{@code {{9}}} 第 N 个参数、
 *       {@code {{date}}}（yyyy-MM-dd）、{@code {{time}}}（HH:mm:ss）、{@code {{env:NAME}}}（环境变量）</li>
 *   <li>条件：{@code {{#if 变量}}…{{else}}…{{/if}} —— 变量非空即真，支持 else 与嵌套</li>
 * </ul>
 * 模板不含 {@code {{}} 时行为与旧实现完全一致（向后兼容）。
 */
public class TemplateEngine {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^{}]*?)\\}\\}");
    private static final Pattern OLD_VARIABLE = Pattern.compile("\\{(args|[1-9])\\}");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String argsText;
    private final List<String> args;

    public TemplateEngine(String argsText) {
        this.argsText = argsText == null ? "" : argsText.trim();
        this.args = splitArgs(this.argsText);
    }

    /** 渲染模板：条件块 → {{变量}} → 旧占位符 */
    public String render(String template) {
        if (template == null) {
            return "";
        }
        String out = renderConditions(template);
        out = substituteVariables(out);
        return applyLegacyPlaceholders(out);
    }

    /** 按空白切分参数；空串返回空列表 */
    private static List<String> splitArgs(String text) {
        List<String> list = new ArrayList<>();
        if (!text.isEmpty()) {
            for (String s : text.split("\\s+")) {
                if (!s.isEmpty()) {
                    list.add(s);
                }
            }
        }
        return list;
    }

    /** 展开全部 {{#if 变量}}…{{else}}…{{/if}} 条件块（支持嵌套），直至无残留 */
    private String renderConditions(String template) {
        String result = template;
        while (true) {
            int start = result.indexOf("{{#if ");
            if (start < 0) {
                return result;
            }
            int varStart = start + "{{#if ".length();
            int varEnd = result.indexOf("}}", varStart);
            if (varEnd < 0) {
                return result; // 语法不完整，保持原样
            }
            String condVar = result.substring(varStart, varEnd).trim();
            if (condVar.isEmpty()) {
                return result;
            }
            // 扫描匹配的 {{/if}} 与同层 {{else}}（支持嵌套）
            int scan = varEnd + 2;
            int depth = 1;
            int ifEnd = -1;
            int elsePos = -1;
            while (scan < result.length()) {
                int nextIf = result.indexOf("{{#if ", scan);
                int nextElse = result.indexOf("{{else}}", scan);
                int nextEnd = result.indexOf("{{/if}}", scan);
                if (nextEnd < 0) {
                    return result; // 无闭合，保持原样
                }
                int earliest = earliestOf(nextIf, nextElse, nextEnd);
                if (earliest == nextEnd) {
                    depth--;
                    if (depth == 0) {
                        ifEnd = nextEnd;
                        break;
                    }
                    scan = nextEnd + "{{/if}}".length();
                } else if (earliest == nextIf) {
                    depth++;
                    scan = nextIf + "{{#if ".length();
                } else {
                    // {{else}}：仅记录当前块最外层（嵌套块的 else 由内层递归处理）
                    if (depth == 1) {
                        elsePos = nextElse;
                    }
                    scan = nextElse + "{{else}}".length();
                }
            }
            if (ifEnd < 0) {
                return result;
            }
            int innerStart = varEnd + 2;
            String inner = result.substring(innerStart, ifEnd);
            String trueBranch = inner;
            String falseBranch = "";
            if (elsePos >= 0) {
                int relElse = elsePos - innerStart;
                trueBranch = inner.substring(0, relElse);
                falseBranch = inner.substring(relElse + "{{else}}".length());
            }
            String chosen = isTruthy(condVar) ? trueBranch : falseBranch;
            result = result.substring(0, start) + chosen + result.substring(ifEnd + "{{/if}}".length());
        }
    }

    /** 取三个标记中最靠前的位置；均不存在返回 -1 */
    private static int earliestOf(int a, int b, int c) {
        int m = -1;
        if (a >= 0 && (m < 0 || a < m)) {
            m = a;
        }
        if (b >= 0 && (m < 0 || b < m)) {
            m = b;
        }
        if (c >= 0 && (m < 0 || c < m)) {
            m = c;
        }
        return m;
    }

    /** 替换 {{变量}}；未知变量与条件语法残留保留原样 */
    private String substituteVariables(String template) {
        Matcher m = VARIABLE.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String value = valueOf(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 变量取值；未知变量返回 null（调用方保留原样） */
    private String valueOf(String var) {
        if ("args".equals(var)) {
            return argsText;
        }
        if (var.length() == 1 && var.charAt(0) >= '1' && var.charAt(0) <= '9') {
            int idx = var.charAt(0) - '0';
            return idx <= args.size() ? args.get(idx - 1) : "";
        }
        if ("date".equals(var)) {
            return LocalDate.now().format(DATE_FORMAT);
        }
        if ("time".equals(var)) {
            return LocalTime.now().format(TIME_FORMAT);
        }
        if (var.startsWith("env:")) {
            return System.getenv(var.substring(4));
        }
        return null;
    }

    /** 条件判断：变量非空即真 */
    private boolean isTruthy(String var) {
        String v = valueOf(var);
        return v != null && !v.isEmpty();
    }

    /** 兼容旧占位符：{args} 与 {1}-{9} */
    private String applyLegacyPlaceholders(String template) {
        Matcher m = OLD_VARIABLE.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value;
            if ("args".equals(key)) {
                value = argsText;
            } else {
                int idx = key.charAt(0) - '0';
                value = idx <= args.size() ? args.get(idx - 1) : "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
