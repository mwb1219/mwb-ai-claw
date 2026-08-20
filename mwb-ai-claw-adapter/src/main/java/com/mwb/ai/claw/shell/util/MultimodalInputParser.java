package com.mwb.ai.claw.shell.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mwb.ai.claw.domain.llm.ContentPart;

/**
 * 多模态输入解析器（D2）：从用户输入中提取图片附件。
 * <p>
 * 支持两种标记语法（可混用）：
 * <ul>
 *   <li>{@code ![描述](路径|URL)} — Markdown 图片语法：URL 转 image_url，本地路径转 base64 内联；</li>
 *   <li>{@code @路径} — 本地图片附件标记（路径存在且为图片扩展名时生效）。</li>
 * </ul>
 * 无法识别的标记保留原样；无附件时返回原文本与空片段列表。
 */
public final class MultimodalInputParser {

    private MultimodalInputParser() {
    }

    /** Markdown 图片语法：![描述](路径|URL) */
    private static final Pattern IMG_MARKDOWN = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)");
    /** @路径 附件标记：@后跟本地图片路径 */
    private static final Pattern AT_IMAGE_PATH = Pattern.compile("(^|\\s)@(\\S+)");
    /** 单张图片 base64 内联上限（10MB，避免请求体膨胀） */
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** 解析结果：纯文本（已去除附件标记）+ 图片片段 */
    public static final class Result {
        private final String text;
        private final List<ContentPart> parts;

        Result(String text, List<ContentPart> parts) {
            this.text = text;
            this.parts = parts;
        }

        public String text() {
            return text;
        }

        public List<ContentPart> parts() {
            return parts;
        }

        public boolean hasImages() {
            return parts != null && !parts.isEmpty();
        }
    }

    /**
     * 解析输入中的图片附件标记，返回纯文本与图片片段（无附件时 parts 为 null）。
     */
    public static Result parse(String input) {
        if (input == null || input.isEmpty()) {
            return new Result(input, null);
        }
        List<ContentPart> parts = new ArrayList<>();
        String text = input;
        // 1) Markdown 图片语法
        Matcher md = IMG_MARKDOWN.matcher(text);
        if (md.find()) {
            StringBuilder sb = new StringBuilder();
            int last = 0;
            do {
                ContentPart part = resolveImagePart(md.group(1).trim());
                if (part != null) {
                    parts.add(part);
                    sb.append(text, last, md.start());
                    last = md.end();
                    // 合并边界空格：标记前尾部与标记后头部均为空格时，只保留一个
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' '
                            && last < text.length() && text.charAt(last) == ' ') {
                        last++;
                    }
                }
            } while (md.find());
            if (last > 0) {
                sb.append(text, last, text.length());
                text = sb.toString();
            }
        }
        // 2) @路径 附件标记（文本中仍有 @ 时）
        if (text.contains("@")) {
            Matcher at = AT_IMAGE_PATH.matcher(text);
            if (at.find()) {
                StringBuilder sb = new StringBuilder();
                int last = 0;
                do {
                    ContentPart part = resolveImagePart(at.group(2));
                    if (part != null) {
                        parts.add(part);
                        sb.append(text, last, at.start(1)); // 保留 @ 前的分隔字符
                        last = at.end();
                        // 合并边界空格（与 Markdown 替换一致）
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' '
                                && last < text.length() && text.charAt(last) == ' ') {
                            last++;
                        }
                    }
                } while (at.find());
                if (last > 0) {
                    sb.append(text, last, text.length());
                    text = sb.toString();
                }
            }
        }
        text = text.trim();
        return new Result(text, parts.isEmpty() ? null : parts);
    }

    /** 将图片引用解析为内容片段：URL → image_url；本地图片文件 → image_base64；无法识别返回 null */
    public static ContentPart resolveImagePart(String target) {
        if (target == null || target.isEmpty()) {
            return null;
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return ContentPart.imageUrl(target);
        }
        if (target.startsWith("file://")) {
            target = target.substring("file://".length());
        }
        if (!isImageFile(target)) {
            return null;
        }
        Path path = Paths.get(target);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir"), target);
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return null; // 忽略空文件与超大图片（>10MB）
            }
            return ContentPart.imageBase64(mimeForFile(target),
                    java.util.Base64.getEncoder().encodeToString(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    /** 是否为支持的图片扩展名 */
    public static boolean isImageFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    /** 按扩展名推断 MIME 类型（默认 image/png） */
    public static String mimeForFile(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }
}
