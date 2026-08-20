package com.mwb.ai.claw.shell;

import lombok.Data;

/**
 * 自定义斜杠命令：由命令目录下的 *.md 定义（frontmatter name/description/output + 正文模板）。
 * <p>
 * 模板支持占位符（命中时替换后作为消息发送给 Agent）：
 * <ul>
 *   <li>{@code {args}} / {@code {{args}}} — 用户输入的全部参数（不含命令名）</li>
 *   <li>{@code {1} {2} …} / {@code {{1}} {{2}} …} — 按空白切分的第 N 个参数</li>
 *   <li>{@code {{date}}} / {@code {{time}}} / {@code {{env:NAME}}} — 日期 / 时间 / 环境变量</li>
 *   <li>{@code {{#if 变量}}…{{else}}…{{/if}} — 条件块（变量非空即真，支持嵌套）</li>
 * </ul>
 * 示例：{@code /review} 命中 review.md，模板含 {@code {args}} 时输入 {@code /review 变更内容} 会替换为完整指令。
 */
@Data
public class CustomCommand {

    /** 命令名（不含 /，与文件名一致，小写） */
    private String name;

    /** 描述（/help 展示） */
    private String description;

    /** 正文模板（含占位符） */
    private String template;

    /** 产物类型：text（默认）/ json（回复经 JsonUtils.extractJson 提取并格式化展示） */
    private String output = "text";
}
