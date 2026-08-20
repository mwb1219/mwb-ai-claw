package com.mwb.ai.claw.domain.llm;

import lombok.Data;

/**
 * LLM 消息内容片段（D2 多模态输入）。
 * <p>
 * 支持文本与图片两类片段；图片可用 URL 或 base64 内联（二选一）：
 * <ul>
 *   <li>{@code text} — 文本片段</li>
 *   <li>{@code image_url} — 远程图片 URL</li>
 *   <li>{@code image_base64} — base64 内联图片（需 {@code mimeType}）</li>
 * </ul>
 */
@Data
public class ContentPart {

    /** 片段类型：text / image_url / image_base64 */
    private String type;

    /** 文本内容（type=text） */
    private String text;

    /** 图片 URL（type=image_url） */
    private String imageUrl;

    /** base64 图片数据（type=image_base64） */
    private String base64Data;

    /** 图片 MIME 类型（type=image_base64，如 image/png） */
    private String mimeType;

    public static ContentPart text(String text) {
        ContentPart p = new ContentPart();
        p.type = "text";
        p.text = text;
        return p;
    }

    public static ContentPart imageUrl(String url) {
        ContentPart p = new ContentPart();
        p.type = "image_url";
        p.imageUrl = url;
        return p;
    }

    public static ContentPart imageBase64(String mimeType, String base64Data) {
        ContentPart p = new ContentPart();
        p.type = "image_base64";
        p.mimeType = mimeType;
        p.base64Data = base64Data;
        return p;
    }
}
