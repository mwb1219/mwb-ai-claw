package com.mwb.ai.claw.shell.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.Test;

import com.mwb.ai.claw.domain.llm.ContentPart;

/**
 * 多模态输入解析单测（D2）：Markdown 图片语法 + @路径 附件标记。
 */
public class MultimodalInputParserTest {

    @Test
    public void testPlainTextNoAttachment() {
        MultimodalInputParser.Result r = MultimodalInputParser.parse("帮我写个排序算法");
        assertEquals("帮我写个排序算法", r.text());
        assertNull(r.parts());
    }

    @Test
    public void testMarkdownUrl() {
        MultimodalInputParser.Result r = MultimodalInputParser.parse("请看这张图 ![示意图](https://example.com/a.png) 并分析");
        assertEquals("请看这张图 并分析", r.text());
        assertTrue(r.hasImages());
        ContentPart part = r.parts().get(0);
        assertEquals("image_url", part.getType());
        assertEquals("https://example.com/a.png", part.getImageUrl());
    }

    @Test
    public void testMarkdownLocalFileToBase64() throws Exception {
        Path img = Files.createTempFile("mwb-test-", ".png");
        Files.write(img, new byte[]{1, 2, 3, 4});
        String abs = img.toAbsolutePath().toString();
        try {
            MultimodalInputParser.Result r = MultimodalInputParser.parse("分析图片 ![图](file://" + abs + ") 内容");
            assertEquals("分析图片 内容", r.text());
            ContentPart part = r.parts().get(0);
            assertEquals("image_base64", part.getType());
            assertEquals("image/png", part.getMimeType());
            assertEquals(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}), part.getBase64Data());
        } finally {
            Files.deleteIfExists(img);
        }
    }

    @Test
    public void testMarkdownMissingFileKeptAsIs() {
        MultimodalInputParser.Result r = MultimodalInputParser.parse("分析 ![图](/no/such/file.png)");
        // 文件不存在 → 标记保留，不解析为附件
        assertEquals("分析 ![图](/no/such/file.png)", r.text());
        assertNull(r.parts());
    }

    @Test
    public void testAtPathAttachment() throws Exception {
        Path img = Files.createTempFile("mwb-test-", ".jpg");
        Files.write(img, new byte[]{9, 8, 7});
        String abs = img.toAbsolutePath().toString();
        try {
            MultimodalInputParser.Result r = MultimodalInputParser.parse("诊断这张截图 @ " + abs);
            // @ 与路径间有空格 → 不作为附件标记
            assertNull(r.parts());
            r = MultimodalInputParser.parse("诊断这张截图 @" + abs);
            assertEquals("诊断这张截图", r.text());
            ContentPart part = r.parts().get(0);
            assertEquals("image_base64", part.getType());
            assertEquals("image/jpeg", part.getMimeType());
        } finally {
            Files.deleteIfExists(img);
        }
    }

    @Test
    public void testAtPathMissingFileKept() {
        MultimodalInputParser.Result r = MultimodalInputParser.parse("看 @/no/such/img.png");
        // 文件不存在 → 不解析，保留原文
        assertEquals("看 @/no/such/img.png", r.text());
        assertNull(r.parts());
    }

    @Test
    public void testMixedSyntax() throws Exception {
        Path img = Files.createTempFile("mwb-test-", ".png");
        Files.write(img, new byte[]{1});
        String abs = img.toAbsolutePath().toString();
        try {
            MultimodalInputParser.Result r = MultimodalInputParser.parse(
                    "文本A ![一](https://example.com/1.png) 文本B @" + abs + " 文本C");
            assertEquals("文本A 文本B 文本C", r.text());
            List<ContentPart> parts = r.parts();
            assertNotNull(parts);
            assertEquals(2, parts.size());
            assertEquals("image_url", parts.get(0).getType());
            assertEquals("image_base64", parts.get(1).getType());
        } finally {
            Files.deleteIfExists(img);
        }
    }

    @Test
    public void testMimeForFile() {
        assertEquals("image/png", MultimodalInputParser.mimeForFile("a.png"));
        assertEquals("image/jpeg", MultimodalInputParser.mimeForFile("a.jpg"));
        assertEquals("image/jpeg", MultimodalInputParser.mimeForFile("a.jpeg"));
        assertEquals("image/gif", MultimodalInputParser.mimeForFile("a.gif"));
        assertEquals("image/webp", MultimodalInputParser.mimeForFile("a.webp"));
        assertEquals("image/bmp", MultimodalInputParser.mimeForFile("a.bmp"));
        assertEquals("image/png", MultimodalInputParser.mimeForFile("a.unknown"));
    }
}
