package com.mwb.ai.claw.shell.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TemplateEngine 单测（D3）：变量 / 条件 / 嵌套 / 兼容旧占位符。
 */
public class TemplateEngineTest {

    // ---------- 旧占位符兼容 ----------

    @Test
    public void testLegacyPlaceholders() {
        assertEquals("say hello world extra", new TemplateEngine("hello world extra").render("say {args}"));
        assertEquals("first=hello second=world", new TemplateEngine("hello world").render("first={1} second={2}"));
        // 无参数时 {1}/{2} 替换为空
        assertEquals("a= b=", new TemplateEngine("").render("a={1} b={2}"));
    }

    // ---------- {{变量}} ----------

    @Test
    public void testVariables() {
        TemplateEngine e = new TemplateEngine("foo bar");
        assertEquals("foo bar", e.render("{{args}}"));
        assertEquals("foo", e.render("{{1}}"));
        assertEquals("bar", e.render("{{2}}"));
        assertEquals("", e.render("{{3}}"));
    }

    @Test
    public void testDateAndTime() {
        TemplateEngine e = new TemplateEngine("");
        String[] parts = e.render("{{date}} {{time}}").split(" ");
        assertTrue("日期格式 yyyy-MM-dd，实际=" + parts[0], parts[0].matches("\\d{4}-\\d{2}-\\d{2}"));
        assertTrue("时间格式 HH:mm:ss，实际=" + parts[1], parts[1].matches("\\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void testEnvVariable() {
        // HOME 在 macOS/Linux 环境必然存在
        String out = new TemplateEngine("").render("home={{env:HOME}}");
        assertTrue("HOME 应替换为环境变量值，实际=" + out, out.startsWith("home=") && !out.endsWith("{{env:HOME}}"));
    }

    @Test
    public void testUnknownVariableKeptAsIs() {
        TemplateEngine e = new TemplateEngine("foo");
        assertEquals("{{unknown}} foo", e.render("{{unknown}} {{args}}"));
    }

    // ---------- {{#if}} 条件 ----------

    @Test
    public void testIfTrue() {
        assertEquals("有参数", new TemplateEngine("hello").render("{{#if args}}有参数{{else}}无参数{{/if}}"));
    }

    @Test
    public void testIfFalse() {
        assertEquals("无参数", new TemplateEngine("").render("{{#if args}}有参数{{else}}无参数{{/if}}"));
    }

    @Test
    public void testIfOnNthArg() {
        TemplateEngine e = new TemplateEngine("a b");
        assertEquals("A", e.render("{{#if 1}}A{{else}}X{{/if}}"));
        assertEquals("A", e.render("{{#if 2}}A{{else}}X{{/if}}"));
        assertEquals("X", e.render("{{#if 3}}A{{else}}X{{/if}}"));
    }

    @Test
    public void testIfWithoutElse() {
        assertEquals("内容", new TemplateEngine("hello").render("{{#if args}}内容{{/if}}"));
        assertEquals("", new TemplateEngine("").render("{{#if args}}内容{{/if}}"));
    }

    @Test
    public void testNestedIf() {
        TemplateEngine e = new TemplateEngine("a b");
        // 外层真、内层真
        assertEquals("A-B", e.render("{{#if 1}}A{{#if 2}}-B{{/if}}{{/if}}"));
        // 外层真、内层假 → 内层取 else
        assertEquals("A-C", e.render("{{#if 1}}A{{#if 3}}-B{{else}}-C{{/if}}{{/if}}"));
        // 外层假 → 内层整体丢弃，走外层 else
        assertEquals("D", new TemplateEngine("").render("{{#if 1}}A{{#if 2}}B{{/if}}{{else}}D{{/if}}"));
    }

    // ---------- 新旧语法混用 ----------

    @Test
    public void testMixedNewAndLegacy() {
        TemplateEngine e = new TemplateEngine("foo bar");
        assertEquals("foo|foo|bar|bar", e.render("{{1}}|{1}|{{2}}|{2}"));
        assertEquals("foo bar|foo bar", e.render("{{args}}|{args}"));
    }

    @Test
    public void testNoTemplateSyntax() {
        assertEquals("纯文本保持不变", new TemplateEngine("ignored").render("纯文本保持不变"));
    }
}
